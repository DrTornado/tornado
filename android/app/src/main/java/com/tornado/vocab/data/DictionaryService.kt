package com.tornado.vocab.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** رقم إصدار المحرّك — يُرفع عند أي تحسين حقيقي في منطق الجلب */
// رُفع إلى ٣ بعد تبديل المصدر: الرقم يجعل الإثراء يعيد المرور على المكتبة
// كلها فتُبنى بطاقاتها من المصدر المرخَّص بدل بقائها على القديم
const val ENGINE_VERSION = 3

sealed interface LookupResult {
    data class Success(val word: Word) : LookupResult
    data class NotFound(val query: String) : LookupResult
    data class Failed(val query: String, val reason: String) : LookupResult
}

/**
 * محرّك بناء البطاقات — منقول عن تطبيق الويب بنفس المصادر والترتيب.
 *
 * المصادر كلها مجانية ومفتوحة بلا حساب ولا مفتاح، وكلها معلَنة الرخصة:
 *  - freedictionaryapi  : ويكاموس · CC BY-SA 4.0 — المعاني والأمثلة والنطق
 *  - Wiktionary REST    : CC BY-SA 4.0 — مصدر ثانٍ يغطي الكلمات النادرة
 *  - Datamuse           : المرادفات والأضداد والمتلازمات والتصحيح الإملائي
 *  - Tatoeba            : CC BY 2.0 FR — أمثلة كتبها بشر
 *  - MyMemory           : الترجمة العربية
 *  - قائمتا أكسفورد والتردد المدمجتان: المستوى وحده، بلا إنترنت
 *
 * وحُذف من هنا مصدران: تسجيلات نطق من خوادم أكسفورد بلا أي رخصة، وواجهة
 * `dictionaryapi.dev` التي لا يعلن مستودعها رخصةً لبياناتها. كلاهما كان
 * يعمل جيداً — والعمل الجيد لا يكفي في تطبيق يُنشر على المتجر.
 */
class DictionaryService(private val context: Context) {

    private companion object {
        /*
         * سعة البطاقة — شمول الشرح وغزارته.
         *
         * كانت أرقاماً متفرّقة في الشيفرة (٢ و٦ و٤ و٨) تتكرّر في ثلاثة مواضع،
         * فرفعُ أحدها دون البقية كان يعني بطاقة نصفها موسَّع ونصفها مقصوص.
         * جُمعت هنا لتُرفع معاً.
         *
         * والحدّ الأعلى ليس تجميلاً: بطاقة بعشرين معنى تُتلى صوتاً في دقائق
         * ويملّها المستخدم قبل نصفها. الغزارة مطلوبة إلى حيث تنفع.
         */
        const val PER_POS = 3        // معانٍ لكل قسم كلام (كان ٢)
        const val MAX_MEANINGS = 8   // إجمالي المعاني (كان ٦)
        const val MAX_EXAMPLES = 6   // الأمثلة (كان ٤)
        const val MAX_SYNONYMS = 10  // المرادفات (كان ٨)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** محاولتان بفاصل قصير — يعالج انقطاعاً عابراً بلا إزعاج المستخدم */
    suspend fun lookup(query: String): LookupResult {
        val w = query.trim().replace(Regex("\\s+"), " ")
        if (w.isBlank()) return LookupResult.Failed(query, "Empty word")
        var lastError: String? = null
        repeat(2) { attempt ->
            if (attempt > 0) delay(1_200)
            runCatching { buildCard(w, 0) }
                .onSuccess { card -> return card?.let { LookupResult.Success(it) } ?: LookupResult.NotFound(w) }
                .onFailure { lastError = it.message ?: it::class.simpleName }
        }
        return LookupResult.Failed(w, lastError ?: "Lookup failed")
    }

    private suspend fun buildCard(input: String, depth: Int): Word? = withContext(Dispatchers.IO) {
        var entry = fetchDict(input)
        var corrected = input

        // تصحيح إملائي: نقترح الأقرب ونعيد المحاولة مرة واحدة
        if (entry == null) {
            val best = datamuse("sp=" + enc(input)).firstOrNull()?.word
            if (!best.isNullOrBlank() && !best.equals(input, true)) {
                entry = fetchDict(best)
                if (entry != null) corrected = best
            }
        }

        var ipa = ""; var ipaUS = ""; var ipaUK = ""
        var audioUS = ""; var audioUK = ""; var audioGen = ""
        val posSet = LinkedHashSet<String>()
        val examples = mutableListOf<LangPair>()
        val synSet = LinkedHashSet<String>()
        var meanings = mutableListOf<Meaning>()

        fun normDef(s: String) = s.lowercase().replace(Regex("[.!?;:]+$"), "").replace(Regex("\\s+"), " ").trim()

        entry?.let { e ->
            /*
             * قراءة بنية freedictionaryapi.com.
             *
             * بديل المصدر السابق الذي لم يكن يعلن رخصةً لبياناته إطلاقاً. وهذا
             * يعلنها صراحةً — ويكاموس تحت CC BY-SA 4.0 — وبنيته أغنى: النطق
             * موسوم بلهجته، والأمثلة قائمة داخل كل معنى لا حقلاً مفرداً،
             * والمرادفات مفصولة عن الأضداد.
             */
            (e["entries"] as? JsonArray)?.forEach { en ->
                val eo = en as? JsonObject ?: return@forEach
                if ((eo["language"] as? JsonObject)?.str("code").orEmpty()
                        .let { it.isNotBlank() && it != "en" }
                ) return@forEach

                val pos = eo.str("partOfSpeech")
                if (pos.isNotBlank()) posSet += pos

                // النطق موسوم باللهجة: نصنّفه بدل أن نخمّنه من اسم ملف صوت
                (eo["pronunciations"] as? JsonArray)?.forEach { p ->
                    val po = p as? JsonObject ?: return@forEach
                    if (po.str("type").lowercase() != "ipa") return@forEach
                    val text = po.str("text")
                    if (text.isBlank()) return@forEach
                    val tags = (po["tags"] as? JsonArray)
                        ?.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                        ?.lowercase().orEmpty()
                    when {
                        tags.contains("us") || tags.contains("general american") ->
                            if (ipaUS.isBlank()) ipaUS = text
                        tags.contains("received") || tags.contains("uk") || tags.contains("british") ->
                            if (ipaUK.isBlank()) ipaUK = text
                        else -> if (ipa.isBlank()) ipa = text
                    }
                }

                /*
                 * السقف على ما نُضيفه لا على ما نقرؤه.
                 *
                 * المصدر يضع المثال حيث اتفق — أحياناً في المعنى الرابع. فقصّ
                 * القراءة عند حدّ المعاني كان يرمي المثال معه، فتُحسب الكلمة
                 * «بلا مثال» ومثالها في الاستجابة نفسها.
                 */
                var taken = 0
                (eo["senses"] as? JsonArray)?.forEach { s ->
                    val so = s as? JsonObject ?: return@forEach
                    val def = so.str("definition")
                    if (def.isNotBlank() && taken < PER_POS && meanings.size < MAX_MEANINGS &&
                        meanings.none { normDef(it.en) == normDef(def) }
                    ) {
                        meanings += Meaning(pos.ifBlank { null }, def, "")
                        taken++
                    }
                    (so["examples"] as? JsonArray)?.forEach { x ->
                        val ex = (x as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                        if (ex.length in 11..200 && examples.size < MAX_EXAMPLES &&
                            examples.none { normDef(it.en) == normDef(ex) }
                        ) examples += LangPair(ex, "")
                    }
                    (so["synonyms"] as? JsonArray)?.forEach { y ->
                        (y as? JsonPrimitive)?.contentOrNull?.let {
                            if (synSet.size < MAX_SYNONYMS && it.isNotBlank()) synSet += it
                        }
                    }
                }
            }
        }

        // المصدر الثاني: أساسي للكلمات النادرة، ومكمّل دائماً
        val wikt = fetchWiktionary(corrected)
        if (entry == null && wikt == null) return@withContext null
        wikt?.let { w ->
            w.pos.forEach { posSet += it }
            w.meanings.forEach { m ->
                if (meanings.size < MAX_MEANINGS && meanings.none { normDef(it.en) == normDef(m.en) }) meanings += m
            }
            w.examples.forEach { ex ->
                if (examples.size < MAX_EXAMPLES && examples.none { normDef(it.en) == normDef(ex.en) }) examples += ex
            }
        }
        if (meanings.isEmpty()) return@withContext null

        // المعاني التي هي مجرد "صيغة مصرّفة من X" لا قيمة لها — نستخرج الجذر ونحذفها
        var lemma: String? = null
        val realMeanings = mutableListOf<Meaning>()
        meanings.forEach { m ->
            val match = Linguistics.INFLECTED_RE.find(m.en)
            if (match != null) { if (lemma == null) lemma = match.groupValues[1].lowercase() }
            else realMeanings += m
        }
        if (depth < 2 && realMeanings.isEmpty() && lemma != null &&
            !lemma.equals(input, true) && !lemma.equals(corrected, true)
        ) {
            buildCard(lemma!!, depth + 1)?.let { return@withContext it }
        }
        if (realMeanings.isNotEmpty()) meanings = realMeanings

        /*
         * التنقية تسبق الترجمة لا تتبعها.
         *
         * ترجمة «present participle and gerund of ski» تُنتج «المشاركة الحالية
         * و gerund من التزلج» — نصاً بلا معنى يظنّه المستخدم شرحاً. المترجم لم
         * يخطئ؛ نحن أطعمناه وسماً نحوياً وطلبنا منه معنى. فنُسقط ما ليس معنى
         * أولاً، ثم نترجم ما يستحق الترجمة وحده.
         */
        meanings = MeaningQuality.refine(meanings).toMutableList()
        if (meanings.isEmpty()) return@withContext null

        val finalWord = entry?.str("word")?.takeIf { it.isNotBlank() } ?: corrected
        val base = (lemma ?: finalWord).lowercase()

        // إثراء متوازٍ
        val enrich = coroutineScope {
            val synJob = async { if (synSet.size >= 5) emptyList() else datamuse("rel_syn=" + enc(base)) }
            val afterJob = async { datamuse("rel_bga=" + enc(base)) }
            val beforeJob = async { datamuse("rel_bgb=" + enc(base)) }
            Triple(synJob.await(), afterJob.await(), beforeJob.await())
        }
        enrich.first.forEach { if (synSet.size < 5) synSet += it.word }

        val colls = mutableListOf<String>()
        enrich.second.forEach { if (colls.size < 3 && Linguistics.isGoodCollocationPartner(it.word)) colls += "$base ${it.word}" }
        enrich.third.forEach { if (colls.size < 5 && Linguistics.isGoodCollocationPartner(it.word)) colls += "${it.word} $base" }
        val collPairs = Linguistics.sanitizeCollocations(base, colls.map { LangPair(it, "") })

        val derivWords = Linguistics.buildDerivatives(context, lemma ?: finalWord)

        // الترجمة العربية بالتوازي لكل الأقسام دفعة واحدة
        val synPairs = synSet.take(5).map { LangPair(it, "") }.toMutableList()
        val exList = examples.take(3).toMutableList()
        val collList = collPairs.toMutableList()
        val derivPairs = derivWords.map { LangPair(it, "") }.toMutableList()

        val translated = coroutineScope {
            val wordAr = async { translateToArabic(finalWord) }
            val meaningJobs = meanings.take(5).mapIndexed { i, m ->
                async { i to (if (m.ar.isBlank()) translateToArabic(m.en) else m.ar) }
            }
            val synJobs = synPairs.mapIndexed { i, p -> async { i to translateToArabic(p.en) } }
            val collJobs = collList.mapIndexed { i, p -> async { i to translateToArabic(p.en) } }
            val exJobs = exList.mapIndexed { i, p -> async { i to translateToArabic(p.en) } }
            val derJobs = derivPairs.mapIndexed { i, p -> async { i to translateToArabic(p.en) } }

            val w = wordAr.await()
            // ترجمة فاشلة تُهمَل: خانة عربية فارغة أوضح من نصّ مشوّه يُقرأ كشرح
            meaningJobs.awaitAll().forEach { (i, ar) ->
                val cleaned = MeaningQuality.cleanArabic(ar)
                if (MeaningQuality.isUsableArabic(cleaned, meanings[i].en)) {
                    meanings[i] = meanings[i].copy(ar = cleaned)
                }
            }
            synJobs.awaitAll().forEach { (i, ar) -> synPairs[i] = synPairs[i].copy(ar = ar) }
            collJobs.awaitAll().forEach { (i, ar) -> collList[i] = collList[i].copy(ar = ar) }
            exJobs.awaitAll().forEach { (i, ar) -> exList[i] = exList[i].copy(ar = ar) }
            derJobs.awaitAll().forEach { (i, ar) -> derivPairs[i] = derivPairs[i].copy(ar = ar) }
            w
        }
        if (meanings.isNotEmpty() && meanings[0].ar.isBlank() &&
            MeaningQuality.isUsableArabic(translated, meanings[0].en)
        ) {
            meanings[0] = meanings[0].copy(ar = MeaningQuality.cleanArabic(translated))
        }

        // المستوى الرسمي من قائمة أكسفورد — المستوى وحده، بلا أي صوت من خوادمها
        var oxford = ""; var cefr = ""
        ReferenceData.lookupOxford(context, finalWord)?.let { ox ->
            cefr = ox.level
            oxford = ox.listName
        }
        // شارة التردد تظهر فقط عند غياب أكسفورد، تفادياً لازدواج الشارات
        val freq = if (oxford.isBlank()) ReferenceData.lookupFreq(context, lemma ?: finalWord) else null

        val now = System.currentTimeMillis()
        Word(
            id = now,
            word = finalWord,
            ipa = ipa, ipaUS = ipaUS, ipaUK = ipaUK,
            arabicPron = Linguistics.ipaToArabic(ipaUS.ifBlank { ipa.ifBlank { ipaUK } }),
            audioUS = audioUS, audioUK = audioUK, audioGen = audioGen,
            oxford = oxford, cefr = cefr,
            estCefr = freq?.estCefr.orEmpty(), freqLabel = freq?.label.orEmpty(),
            pos = posSet.toList(),
            meanings = meanings,
            inflections = Linguistics.inflect(lemma ?: finalWord, posSet.toList()),
            derivatives = derivPairs,
            synonyms = synPairs,
            collocations = collList,
            examples = exList,
            differences = emptyList(),
            createdAt = now,
            engineVersion = ENGINE_VERSION
        ).derive()
    }

    // ===== مصادر الشبكة =====

    /**
     * المصدر الأساسي — ويكاموس عبر واجهة معلَنة الرخصة.
     *
     * سبقه `api.dictionaryapi.dev`، ولم يكن مستودعه يذكر رخصةً لبياناته
     * إطلاقاً وكان يعيد روابط صوت من نطاق جوجل. مصدر بلا رخصة معلَنة مخاطرةٌ
     * صامتة في تطبيق يُنشر على المتجر: لا شيء يبدو معطلاً حتى يصل الإنذار.
     */
    private suspend fun fetchDict(w: String): JsonObject? {
        val body = get(
            "https://freedictionaryapi.com/api/v1/entries/en/${enc(w)}",
            allow404 = true
        ) ?: return null
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private data class WiktResult(
        val pos: List<String>,
        val meanings: List<Meaning>,
        val examples: List<LangPair>
    )

    private suspend fun fetchWiktionary(w: String): WiktResult? {
        val body = get(
            "https://en.wiktionary.org/api/rest_v1/page/definition/${enc(w.lowercase())}",
            allow404 = true
        ) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val en = (root["en"] as? JsonArray) ?: return null
        val pos = mutableListOf<String>()
        val meanings = mutableListOf<Meaning>()
        val examples = mutableListOf<LangPair>()
        en.forEach { sec ->
            val so = sec as? JsonObject ?: return@forEach
            val p = so.str("partOfSpeech").lowercase()
            if (p.isNotBlank() && p !in pos) pos += p
            // نفس المبدأ: السقف على ما نُضيفه من معانٍ، والقراءة تكمل للأمثلة
            var taken = 0
            (so["definitions"] as? JsonArray)?.forEach { d ->
                val dobj = d as? JsonObject ?: return@forEach
                val t = stripHtml(dobj.str("definition"))
                if (t.length > 3 && taken < PER_POS && meanings.size < MAX_MEANINGS) {
                    meanings += Meaning(p.ifBlank { null }, t, "")
                    taken++
                }
                (dobj["examples"] as? JsonArray)?.forEach { x ->
                    val s = stripHtml((x as? JsonPrimitive)?.contentOrNull.orEmpty())
                    if (s.length in 11..200 && examples.size < MAX_EXAMPLES) examples += LangPair(s, "")
                }
            }
        }
        return if (meanings.isEmpty()) null else WiktResult(pos, meanings, examples)
    }

    private data class DmWord(val word: String)

    private suspend fun datamuse(params: String): List<DmWord> {
        val body = get("https://api.datamuse.com/words?$params&max=8") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.str("word")?.takeIf { w -> w.isNotBlank() }?.let(::DmWord) }
    }

    /** ترجمة أفضل جهد — الفشل يرجع نصاً فارغاً بلا كسر البطاقة */
    private suspend fun translateToArabic(text: String): String {
        if (text.isBlank()) return ""
        val body = get(
            "https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=en|ar"
        ) ?: return ""
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return ""
        val raw = (root["responseData"] as? JsonObject)?.str("translatedText").orEmpty()
        val clean = raw
            .replace(Regex("</?[a-zA-Z][^>]*>"), "")
            .replace(Regex("\\[[A-Z_ ]+]:?\\s*"), "")
            .replace(Regex("&#?\\w+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (Linguistics.isArabic(clean)) clean else ""
    }

    // ===== أدوات =====

    private suspend fun get(url: String, allow404: Boolean = false): String? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        if (allow404) cont.resume(null) else cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (!cont.isActive) return
                        when {
                            r.code == 404 -> cont.resume(null)
                            !r.isSuccessful ->
                                if (allow404) cont.resume(null)
                                else cont.resumeWithException(IOException("HTTP ${r.code}"))
                            else -> cont.resume(runCatching { r.body?.string() }.getOrNull())
                        }
                    }
                }
            })
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun stripHtml(h: String) =
        h.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
