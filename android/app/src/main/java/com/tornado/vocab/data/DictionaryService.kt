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

/**
 * رقم إصدار المحرّك — يُرفع عند كل تحسين يجب أن يسري على ما بُني سابقاً.
 *
 * رُفع إلى ١٠ بعد ترتيب المعاني (العام قبل المتخصّص). والقفزة كبيرة عمداً:
 * الحقل كان يُزاد مع كل محاولة إثراء لا مع كل تحسين، فتضخّم على جهاز المستخدم
 * إلى ٦ و٧ بينما المحرّك عند ٣ — فأي رقم أدنى كان سيبدو أقدم من البطاقات التي
 * يريد إصلاحها، ولا يصل إلى واحدة منها.
 *
 * والمحاسبة صُحّحت في LibraryEnricher: النجاح يثبّت الرقم والفشل وحده يزيده،
 * فلن يتكرّر الانحراف.
 */
const val ENGINE_VERSION = 20

/**
 * أقلّ قفزة مسموحة عند رفع رقم المحرّك.
 *
 * عدّاد المحاولات مخبّأ في الفرق بين رقم البطاقة ورقم المحرّك، فبطاقة فشلت
 * ثلاث مرات تحمل رقماً أعلى من المحرّك بثلاثة. ورفعٌ بأقلّ من ذلك يترك تلك
 * البطاقات فوق السقف — فتبقى مقصاة رغم أن سبب فشلها قد أُصلح.
 *
 * وقد وقع هذا فعلاً: عطلٌ في المحرّك أفشل كل جلب، فتسلّقت البطاقات إلى حدّ
 * المحاولات، ثم أُصلح العطل ولم تعد أيٌّ منها مؤهّلة. فالقفزة تتجاوز السقف
 * دائماً، ليبدأ الجميع من صفر مع كل تحسين.
 */
const val MIN_VERSION_STEP = 5

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

        /*
         * كم يُترجَم من كل بطاقة.
         *
         * كان بريد المطوّر مكتوباً هنا ليرفع الحصة عشرة أضعاف، وكان خطأً
         * فادحاً: الحصة تُحسب على البريد لا على الجهاز، فكل نسخة من التطبيق
         * في العالم تشترك في حصة واحدة — تنفد في دقائق يوم يصير للتطبيق
         * مستخدمون، وتتعطّل الترجمة عند الجميع دفعةً واحدة. عطلٌ لا يظهر في
         * أي اختبار، ويظهر يوم النجاح وحده.
         *
         * فحُذف البريد — لكل جهاز حصته المستقلة — وقُلّص ما يُرسَل إلى ما
         * يُقرأ فعلاً: من نحو ألف ومئتي حرف للبطاقة إلى نحو مئتين.
         */
        const val TRANSLATED_MEANINGS = 3
        const val TRANSLATED_EXAMPLES = 2
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

        /*
         * ترتيب التواتر تحسينٌ لا شرط.
         *
         * يُجلب مرة واحدة للكلمة كلها لا لكل قسم كلام، وفشلُه يعيد قائمة
         * فارغة فيُرتَّب بالمجالات بدلاً منه. وقد كان استثناؤه يصعد فيُسقط
         * البطاقة كلها ويعود «القاموس لم يجب» — فامتنعت إضافة أي كلمة.
         */
        val freqOrder: List<String> =
            if (entry != null) runCatching { senseOrder(corrected) }.getOrDefault(emptyList())
            else emptyList()

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
                /*
                 * نجمع كل المعاني ثم نرتّبها، لا نأخذ أوّلها.
                 *
                 * الأخذ بالترتيب الوارد كان يعني الاستسلام لترتيب ويكاموس
                 * التاريخي: «articulation» يبدأ بالمفصل التشريحي وينتهي بوضوح
                 * النطق، فيقرأ المتعلّم «مفصل» ويظنّ التطبيق جاهلاً — وهو
                 * محقّ في ظنّه.
                 */
                val posSenses = mutableListOf<Meaning>()
                (eo["senses"] as? JsonArray)?.forEach { s ->
                    val so = s as? JsonObject ?: return@forEach
                    val def = so.str("definition")
                    if (def.isNotBlank() && meanings.none { normDef(it.en) == normDef(def) } &&
                        posSenses.none { normDef(it.en) == normDef(def) }
                    ) {
                        posSenses += Meaning(pos.ifBlank { null }, def, "")
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

                // العام قبل المتخصّص، ثم نأخذ نصيب هذا القسم من الكلام
                MeaningQuality.rankByFrequency(posSenses, freqOrder).take(PER_POS).forEach { m ->
                    if (meanings.size < MAX_MEANINGS) meanings += m
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

        /*
         * نترجم ما يُقرأ، لا كل ما جُمع.
         *
         * كانت البطاقة تُرسل نحو ألف ومئتي حرف إلى خدمة الترجمة: خمسة تعريفات
         * كاملة وثلاثة أمثلة وخمسة مرادفات ومتلازمات ومشتقات. والحصة المجانية
         * خمسة آلاف حرف يومياً — أي أربع كلمات ثم يتوقف كل شيء. وقد توقّف
         * فعلاً، فقرأ المستخدم كلمات بلا معنى عربي وظنّها عيباً في المحرّك.
         *
         * والمرادفات والمتلازمات كلماتٌ إنجليزية مفردة، وترجمتها الآلية تعطي
         * مقابلاً واحداً مضلّلاً غالباً — تكلفة عالية وقيمة منخفضة. أما التعريف
         * فيُقصَّر قبل الترجمة: الجملة الأولى تُترجَم أدقّ من فقرة كاملة، وهي
         * التي يقرؤها المتعلّم أصلاً.
         */
        val translated = coroutineScope {
            // البشري أولاً بلا حدّ، والآلي يكمل ما عجز عنه
            val wordAr = async {
                arabicFromWiktionary(finalWord).ifBlank { translateToArabic(finalWord) }
            }
            val meaningJobs = meanings.take(TRANSLATED_MEANINGS).mapIndexed { i, m ->
                async { i to (if (m.ar.isBlank()) translateToArabic(gistOf(m.en)) else m.ar) }
            }
            val exJobs = exList.take(TRANSLATED_EXAMPLES).mapIndexed { i, p ->
                async { i to translateToArabic(p.en) }
            }

            val w = wordAr.await()
            // ترجمة فاشلة تُهمَل: خانة عربية فارغة أوضح من نصّ مشوّه يُقرأ كشرح
            meaningJobs.awaitAll().forEach { (i, ar) ->
                val cleaned = MeaningQuality.cleanArabic(ar)
                if (MeaningQuality.isUsableArabic(cleaned, meanings[i].en)) {
                    meanings[i] = meanings[i].copy(ar = cleaned)
                }
            }
            exJobs.awaitAll().forEach { (i, ar) -> exList[i] = exList[i].copy(ar = ar) }
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
        val body = getOptional("https://api.datamuse.com/words?$params&max=8") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.str("word")?.takeIf { w -> w.isNotBlank() }?.let(::DmWord) }
    }

    /**
     * ترتيب المعاني بحسب شيوع الاستعمال.
     *
     * Datamuse يعيد تعريفات نفس المصدر مرتّبةً تواتُرياً لا تاريخياً، وهذا هو
     * الفرق كلّه: «articulation» تبدأ عنده بوضوح النطق لا بالمفصل، و«bank»
     * بالمصرف لا بضفة النهر، و«charge» بالرسوم لا بالهجوم. حكمٌ مبنيّ على
     * استعمال الناس الفعلي لا على شكل النصّ.
     *
     * والفشل هنا لا يكسر شيئاً: تُرجَع قائمة فارغة فيسقط الترتيب إلى قاعدة
     * «العام قبل المتخصّص»، وهي أضعف لكنها تعمل بلا شبكة.
     */
    private suspend fun senseOrder(word: String): List<String> {
        val body = getOptional("https://api.datamuse.com/words?sp=${enc(word)}&md=d&max=1") ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        val defs = (arr.firstOrNull() as? JsonObject)?.get("defs") as? JsonArray ?: return emptyList()
        return defs.mapNotNull { d ->
            // الصيغة: "n\t(uncountable) The quality…" — نُسقط بادئة قسم الكلام
            (d as? JsonPrimitive)?.contentOrNull?.substringAfter('\t')?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * لبّ التعريف — أول جملة، بلا وسم المجال ولا الفروع.
     *
     * الترجمة الآلية تتدهور بطول النص: فقرة من ثلاثين كلمة تعود ركيكة، وجملة
     * من عشر تعود مفهومة. والمتعلّم يقرأ أولها على كل حال.
     *
     * وهذا يخدم غرضين بضربة واحدة: عربية أدقّ، واستهلاك أقلّ من حصة يومية
     * محدودة أصلاً.
     */
    private fun gistOf(definition: String): String {
        var t = definition.trim()
        t = t.replace(Regex("""^\s*\([^()]{1,60}\)\s*"""), "")   // وسم المجال
        t = t.substringBefore(';').substringBefore(" — ").trim()
        // جملة واحدة تكفي؛ وما زاد على مئة وستين حرفاً يُقصّ عند آخر فاصلة
        if (t.length > 160) t = t.take(160).substringBeforeLast(',').trim()
        return t.ifBlank { definition.take(160) }
    }

    /**
     * ترجمة عربية كتبها بشر — من قسم الترجمات في ويكاموس.
     *
     * تُسأل قبل الترجمة الآلية لسببين: جودتها أعلى لأن إنساناً اختارها، وهي
     * بلا حدّ يومي فلا تستهلك حصةً محدودة أصلاً. وقياسٌ على ستّ كلمات أعطى
     * تغطية اثنتين — فهي مصدر أول لا وحيد، والآلية تكمل ما تعجز عنه.
     *
     * والقالب في ويكاموس على صورة `{{t+|ar|كلمة}}` أو `{{t|ar|كلمة}}`.
     */
    private suspend fun arabicFromWiktionary(word: String): String {
        val body = getOptional(
            "https://en.wiktionary.org/w/api.php?action=query&titles=${enc(word)}" +
                "&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2"
        ) ?: return ""
        val hit = Regex("""\{\{t\+?\|ar\|([^}|]+)""").find(body)?.groupValues?.get(1).orEmpty()
        // النصّ يعود مُهرَّباً بصيغة \uXXXX داخل JSON — نفكّه قبل استعماله
        val decoded = Regex("""\\u([0-9a-fA-F]{4})""").replace(hit) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return decoded.trim().takeIf { it.isNotBlank() && Linguistics.isArabic(it) }.orEmpty()
    }

    /**
     * ترجمة معنى واحد عند الطلب — لزرّ «عربي» حين تكون البطاقة بلا عربية.
     *
     * الإثراء الدوري يملأ الفراغات على مدى أيام، لكن من يضغط الزرّ الآن يريد
     * الآن. فنترجم في اللحظة ونحفظ، فتعمل الكلمة مرّةً بانتظار ثوانٍ وكل مرّة
     * بعدها فوراً.
     */
    suspend fun arabicFor(text: String): String = translateToArabic(text)

    /** ترجمة أفضل جهد — الفشل يرجع نصاً فارغاً بلا كسر البطاقة */
    private suspend fun translateToArabic(text: String): String {
        if (text.isBlank()) return ""
        /*
         * بلا تعريف — الحصة لكل جهاز على حدة.
         *
         * وثيقة الخدمة تعطي خمسين ألف حرف يومياً لمن يضع بريده، وخمسة آلاف
         * لمن لا يضع. والإغراء أن نضع بريد المطوّر، لكن الحصة تُحسب على
         * البريد: عشرة آلاف مستخدم يقتسمون حصةً واحدة، فتنفد لهم جميعاً.
         * خمسة آلاف لكل جهاز أكثر بكثير من خمسين ألفاً مقسومة على الجميع.
         */
        val body = getOptional(
            "https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=en|ar"
        ) ?: return ""
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return ""
        val raw = (root["responseData"] as? JsonObject)?.str("translatedText").orEmpty()
        // الخدمة تردّ بتحذير نصّي عند نفاد الحصة — نصٌّ لا ترجمة، فلا يُحفظ
        if (raw.contains("MYMEMORY WARNING", true) || raw.contains("QUERY LENGTH LIMIT", true)) return ""
        val clean = raw
            .replace(Regex("</?[a-zA-Z][^>]*>"), "")
            .replace(Regex("\\[[A-Z_ ]+]:?\\s*"), "")
            .replace(Regex("&#?\\w+;"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (Linguistics.isArabic(clean)) clean else ""
    }

    // ===== أدوات =====

    /**
     * استدعاء لمصدر مساعد — فشله لا يُسقط البطاقة أبداً.
     *
     * المصادر المساعدة (المرادفات، ترتيب المعاني، الترجمة) تُحسّن البطاقة ولا
     * تصنعها. وكانت تستدعي `get` مباشرة، وهو يرمي استثناءً عند أي استجابة غير
     * ناجحة — فيصعد الاستثناء إلى `lookup` ويعود «القاموس لم يجب».
     *
     * والنتيجة أن تعثّراً عابراً في مصدر ثانوي كان يمنع إضافة أي كلمة وإصلاح
     * أي بطاقة قديمة. عطلٌ تامّ سببه تحسينٌ اختياري — وقد وقع فعلاً.
     */
    private suspend fun getOptional(url: String): String? =
        runCatching { get(url, allow404 = true) }.getOrNull()

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
