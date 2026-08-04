package com.tornado.vocab.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val DAY_MS = 86_400_000L

/** ترتيب القائمة — يُمرَّر للاستعلام مباشرة بدل الفرز في الذاكرة */
enum class SortOrder(val key: String, val label: String) {
    ALPHA("alpha", "A → Z"),
    NEWEST("newest", "Newest first"),
    DUE("due", "Due first"),
    HARDEST("hardest", "Hardest first")
}

data class MergeResult(val added: Int, val updated: Int, val removed: Int)

/**
 * الطبقة الوحيدة التي تعرف مصدر البيانات.
 * الواجهة لا تلمس Room ولا JSON إطلاقاً.
 */
class WordRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).wordDao()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // ===== قراءة =====

    fun rows(status: WordStatus?, favOnly: Boolean, sort: SortOrder): Flow<List<WordRow>> =
        dao.rowsFiltered(status.toKey(), if (favOnly) 1 else 0, sort.key)

    /**
     * البحث الفوري. نبني استعلام FTS بلواحق نجمة حتى يطابق البادئات أثناء الكتابة،
     * فيرى المستخدم نتائج من أول حرفين بدل انتظار كلمة كاملة.
     */
    fun search(query: String, status: WordStatus?, favOnly: Boolean, sort: SortOrder): Flow<List<WordRow>> {
        val raw = query.trim().lowercase()
        val fts = buildFtsQuery(raw)
        return if (fts == null) {
            dao.searchRowsLike(raw)
        } else {
            // نتائج البحث تُرتَّب بالصلة لا بترتيب القائمة — التطابق الحرفي أولاً دائماً
            dao.searchRows(fts, raw, "$raw%", status.toKey(), if (favOnly) 1 else 0)
        }
    }

    /**
     * المُجزّئ النصي في SQLite يتجاهل الرموز، فأي محرف غير حرفي يُزال قبل البناء.
     * لو لم يبقَ شيء صالح نُرجع null ليتحوّل البحث للطريقة الاحتياطية.
     */
    private fun buildFtsQuery(raw: String): String? {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"" + it.replace("\"", "") + "\"*" }
    }

    fun observeWord(id: Long): Flow<Word?> = dao.observeById(id)
    suspend fun word(id: Long): Word? = dao.byId(id)
    suspend fun wordByName(name: String): Word? = dao.byWord(name.trim())
    fun stats(): Flow<LibraryStats> = dao.stats(System.currentTimeMillis())
    suspend fun count(): Int = dao.count()
    suspend fun allWords(): List<Word> = dao.allOnce()

    /** صفوف قائمة التشغيل — خفيفة بما يسمح بجلسة من آلاف الكلمات */
    suspend fun playlistRows(status: WordStatus?, favOnly: Boolean): List<WordRow> =
        dao.playlistRows(status.toKey(), if (favOnly) 1 else 0)

    /** يقرأ دفعة بطاقات كاملة عند الحاجة لبناء صوتها */
    suspend fun wordsByIds(ids: List<Long>): List<Word> =
        if (ids.isEmpty()) emptyList() else dao.byIds(ids)

    suspend fun reviewQueue(
        dueOnly: Boolean, status: WordStatus?, favOnly: Boolean, limit: Int
    ): List<Word> = dao.reviewQueue(
        System.currentTimeMillis(), if (dueOnly) 1 else 0, status.toKey(), if (favOnly) 1 else 0, limit
    )

    // ===== كتابة =====

    suspend fun toggleFavorite(id: Long, fav: Boolean) = dao.setFavorite(id, fav)

    /** ينقل الكلمة لتصنيف اختاره المستخدم بيده، ويضبط جدولتها بما يوافقه */
    suspend fun setStatus(id: Long, status: WordStatus) {
        val now = System.currentTimeMillis()
        when (status) {
            WordStatus.NEW -> dao.setStatus(id, null, 0, 0)
            WordStatus.MISSED -> dao.setStatus(id, "wrong", 0, now)
            WordStatus.KNOWN -> dao.setStatus(id, "right", 3, now + 3 * DAY_MS)
        }
    }

    suspend fun addWord(word: Word) = dao.insert(word.derive())

    /**
     * ينقّي معاني المكتبة الموجودة.
     *
     * إصلاح مصدر البيانات يخدم الكلمات الجديدة وحدها، ومكتبة المستخدم مبنية
     * بالمصدر القديم — فتبقى المعاني المشوّهة أمامه رغم الإصلاح. هذا المرور
     * يطبّق التنقية نفسها على المحفوظ، ولا يمسّ التقدّم ولا الجدولة.
     *
     * @return عدد البطاقات التي تغيّرت فعلاً
     */
    suspend fun refineStoredMeanings(): Int {
        var changed = 0
        dao.allOnce().forEach { w ->
            val refined = MeaningQuality.refine(w.meanings)
            if (refined.isEmpty() || refined == w.meanings) return@forEach
            dao.update(w.copy(meanings = refined).derive())
            changed++
        }
        return changed
    }

    /**
     * يمسح روابط النطق المملوكة من البطاقات المحفوظة.
     *
     * إيقاف كتابتها لا يكفي: البطاقات المبنيّة قبل الإصلاح تحمل الروابط في
     * قاعدة البيانات، فيظل التطبيق يجلب تسجيلات لا رخصة لنا فيها من خوادم
     * أكسفورد — والمخالفة قائمة وإن خلت الشيفرة منها.
     *
     * والمرور ثابت النتيجة: يعمل مرة ولا يجد شيئاً بعدها.
     */
    suspend fun purgeUnlicensedAudio(): Int {
        var cleared = 0
        dao.allOnce().forEach { w ->
            val us = if (w.audioUS.contains(UNLICENSED_HOST)) "" else w.audioUS
            val uk = if (w.audioUK.contains(UNLICENSED_HOST)) "" else w.audioUK
            if (us == w.audioUS && uk == w.audioUK) return@forEach
            dao.update(w.copy(audioUS = us, audioUK = uk).derive())
            cleared++
        }
        return cleared
    }

    private companion object {
        const val UNLICENSED_HOST = "oxfordlearnersdictionaries.com"
    }

    suspend fun delete(word: WordRow) = dao.deleteWithTombstone(word.id, word.word)

    suspend fun deleteById(id: Long, name: String) = dao.deleteWithTombstone(id, name)

    /** شواهد الحذف — تمنع المزامنة من إحياء كلمة حذفها المستخدم عمداً */
    suspend fun tombstones(): List<Tombstone> = dao.tombstones()

    /**
     * يحفظ شاهدةً وصلت من جهاز آخر.
     *
     * كانت الشواهد البعيدة تُطبَّق ولا تُحفظ: يحذف الجوال الكلمة المطابقة ثم
     * يرفع شواهده المحلّية وحدها. فالشاهدة التي لا تطابق كلمةً موجودة عنده
     * تضيع من الملف — نزلت من إحدى وعشرين إلى خمس، وقِست ذلك.
     *
     * ونتيجته أن كلمةً حُذفت عمداً تعود: يفقد الجوال شاهدتها، فيراها الكمبيوتر
     * ناقصةً لا محذوفة، فيرفعها من جديد.
     */
    suspend fun rememberDeletion(id: Long, word: String, deletedAt: Long) =
        dao.addTombstone(Tombstone(id, word, deletedAt))

    /** يحدّث بطاقة كاملة — يخدم الإثراء الذي يملأ نواقص البطاقات القديمة */
    suspend fun update(word: Word) = dao.update(word.derive())

    suspend fun resetAllProgress() = dao.resetAllProgress()

    /**
     * جدولة متباعدة مطابقة لتطبيق الويب حرفاً بحرف.
     *
     * الفاصل يُضرب في ٢٫٢ عند الإجابة الصحيحة فينتج:
     * `١ · ٢ · ٤ · ٩ · ٢٠ · ٤٤ · ٦٠` — والسقف ستون يوماً.
     * والخطأ يصفّره فتعود الكلمة غداً.
     *
     * وكان مكتوباً هنا `١ ← ٣ ← ٧ ← ١٦` وهو وصف لا يطابق الحساب ولا تطبيق
     * الويب. التعليق الخاطئ أسوأ من غيابه: يجعل القارئ يصلح كوداً سليماً.
     */
    /**
     * يسجّل نتيجة بلا أن يمسّ الجدولة.
     *
     * التدريب الحرّ يجري على كلمات **غير مستحقة** — راجعتَها أمس وموعدها بعد
     * أسبوع. واحتساب إجابتك فيه كمراجعة حقيقية يضاعف الفاصل مرة ثانية بلا
     * وجه حق، فتُدفع الكلمة إلى شهرين وأنت لم تُختبر عليها فعلاً.
     *
     * المراجعة المتباعدة تقوم على أن الفاصل يعكس تمكّنك بمرور الوقت، لا عدد
     * مرات مرورك على البطاقة. فالعدّاد يُحدَّث والجدولة تُترك كما هي.
     */
    suspend fun recordAnswerOnly(word: Word, knew: Boolean): Word {
        val updated = word.copy(
            right = word.right + if (knew) 1 else 0,
            wrong = word.wrong + if (knew) 0 else 1
        )
        dao.updateProgress(
            updated.id, updated.right, updated.wrong,
            word.lastResult, word.interval, word.due
        )
        return updated
    }

    suspend fun answer(word: Word, knew: Boolean): Word {
        var interval = if (knew) {
            (Math.round((word.interval.coerceAtLeast(0)) * 2.2).toInt()).coerceIn(1, 60)
        } else 0
        if (knew && word.interval <= 0) interval = 1
        val updated = word.copy(
            right = word.right + if (knew) 1 else 0,
            wrong = word.wrong + if (knew) 0 else 1,
            lastResult = if (knew) "right" else "wrong",
            interval = interval,
            due = System.currentTimeMillis() + interval * DAY_MS
        )
        dao.updateProgress(updated.id, updated.right, updated.wrong, updated.lastResult, updated.interval, updated.due)
        return updated
    }

    /** يعيد الكلمة لحالة سابقة بالضبط — يخدم زر التراجع في الاختبار */
    suspend fun restoreProgress(word: Word) =
        dao.updateProgress(word.id, word.right, word.wrong, word.lastResult, word.interval, word.due)

    // ===== الاستيراد الأولي =====

    /** يستورد الحزمة المرفقة مع التطبيق مرة واحدة فقط عند أول تشغيل */
    suspend fun seedIfEmpty(): Int = withContext(Dispatchers.IO) {
        if (dao.count() > 0) return@withContext 0
        val raw = runCatching {
            context.assets.open("words.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext 0
        val items = parseExport(raw)
        if (items.isNotEmpty()) dao.insertAll(items)
        items.size
    }

    // ===== نسخ احتياطي / استعادة =====

    /** يُنتج ملفاً بنفس صيغة تطبيق الويب بالضبط، فالنسخ متبادلة بين المنصتين */
    suspend fun exportJson(): String = withContext(Dispatchers.Default) {
        val all = dao.allOnce()
        val root = buildJsonObject {
            put("app", "tornado")
            put("version", 1)
            put("exportedAt", java.time.Instant.now().toString())
            put("words", buildJsonArray { all.forEach { add(it.toJson()) } })
        }
        json.encodeToString(JsonObject.serializer(), root)
    }

    suspend fun parseExport(text: String): List<Word> = withContext(Dispatchers.Default) {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return@withContext emptyList()
        val arr: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject && root["words"] is JsonArray -> root["words"]!!.jsonArray
            else -> return@withContext emptyList()
        }
        arr.mapNotNull { el -> runCatching { el.jsonObject.toWord() }.getOrNull() }
    }

    /**
     * دمج آمن: التقدّم الأعلى يفوز دائماً، والحذف المسجَّل يمنع عودة كلمة أزالها المستخدم
     * من جهاز آخر. لا يُنشئ تكراراً ولا يفقد إجابات.
     */
    suspend fun mergeIncoming(incoming: List<Word>): MergeResult = withContext(Dispatchers.Default) {
        val existing = dao.allOnce().associateBy { it.word.lowercase() }
        val deleted = dao.tombstones().associateBy { it.word.lowercase() }
        var added = 0
        var updated = 0
        var skipped = 0
        val toWrite = mutableListOf<Word>()

        for (inc in incoming) {
            val key = inc.word.lowercase()
            val old = existing[key]
            if (old == null) {
                if (deleted.containsKey(key)) { skipped++; continue }
                toWrite += inc.derive()
                added++
            } else {
                val incTotal = inc.right + inc.wrong
                val oldTotal = old.right + old.wrong
                // البطاقة الواردة تفوز فقط إن حملت تقدّماً أكبر أو محتوى أغنى
                val merged = if (incTotal > oldTotal) {
                    inc.copy(id = old.id, favorite = old.favorite || inc.favorite)
                } else {
                    old.copy(
                        meanings = old.meanings.ifEmpty { inc.meanings },
                        examples = old.examples.ifEmpty { inc.examples },
                        synonyms = old.synonyms.ifEmpty { inc.synonyms },
                        collocations = old.collocations.ifEmpty { inc.collocations },
                        derivatives = old.derivatives.ifEmpty { inc.derivatives },
                        inflections = old.inflections.ifEmpty { inc.inflections },
                        audioUS = old.audioUS.ifBlank { inc.audioUS },
                        audioUK = old.audioUK.ifBlank { inc.audioUK },
                        ipaUS = old.ipaUS.ifBlank { inc.ipaUS },
                        ipaUK = old.ipaUK.ifBlank { inc.ipaUK },
                        arabicPron = old.arabicPron.ifBlank { inc.arabicPron },
                        favorite = old.favorite || inc.favorite
                    )
                }
                if (merged != old) { toWrite += merged.derive(); updated++ }
            }
        }
        if (toWrite.isNotEmpty()) dao.insertAll(toWrite)
        MergeResult(added, updated, skipped)
    }

    suspend fun replaceAll(items: List<Word>) = withContext(Dispatchers.IO) {
        dao.deleteAll()
        dao.insertAll(items.map { it.derive() })
    }

    // ===== تحويل JSON =====

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.strList(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun JsonObject.pairList(k: String): List<LangPair> =
        (this[k] as? JsonArray)?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }?.let { LangPair(it, "") }
                is JsonObject -> {
                    val en = (el["en"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val ar = (el["ar"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (en.isBlank() && ar.isBlank()) null else LangPair(en, ar)
                }
                else -> null
            }
        } ?: emptyList()

    private fun JsonObject.meaningList(): List<Meaning> =
        (this["meanings"] as? JsonArray)?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }?.let { Meaning(null, it, "") }
                is JsonObject -> {
                    val en = (el["en"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val ar = (el["ar"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val pos = (el["pos"] as? JsonPrimitive)?.contentOrNull
                    if (en.isBlank() && ar.isBlank()) null else Meaning(pos?.takeIf { it.isNotBlank() }, en, ar)
                }
                else -> null
            }
        } ?: emptyList()

    private fun JsonObject.toWord(): Word {
        val name = str("word")
        val id = (this["id"] as? JsonPrimitive)?.longOrNull ?: name.lowercase().hashCode().toLong()
        val lastRaw = (this["last"] as? JsonPrimitive)?.contentOrNull
        return Word(
            id = id,
            word = name,
            ipa = str("ipa"),
            ipaUS = str("ipaUS"),
            ipaUK = str("ipaUK"),
            arabicPron = str("arabicPron"),
            audioUS = str("audioUS"),
            audioUK = str("audioUK"),
            audioGen = str("audioGen"),
            oxford = str("oxford"),
            cefr = str("cefr"),
            estCefr = str("estCefr"),
            freqLabel = str("freqLabel"),
            pos = strList("pos"),
            meanings = meaningList(),
            inflections = strList("inflections"),
            derivatives = pairList("derivatives"),
            synonyms = pairList("synonyms"),
            collocations = pairList("collocations"),
            examples = pairList("examples"),
            differences = pairList("differences"),
            right = (this["right"] as? JsonPrimitive)?.intOrNull ?: 0,
            wrong = (this["wrong"] as? JsonPrimitive)?.intOrNull ?: 0,
            lastResult = lastRaw?.takeIf { it.isNotBlank() && it != "null" },
            interval = (this["interval"] as? JsonPrimitive)?.intOrNull ?: 0,
            due = (this["due"] as? JsonPrimitive)?.longOrNull ?: 0L,
            favorite = str("favorite") == "true",
            createdAt = id,
            engineVersion = (this["engineVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        ).derive()
    }

    private fun Word.toJson(): JsonElement = buildJsonObject {
        put("id", id)
        put("word", word)
        put("ipa", ipa); put("ipaUS", ipaUS); put("ipaUK", ipaUK)
        put("arabicPron", arabicPron)
        put("oxford", oxford); put("cefr", cefr); put("estCefr", estCefr); put("freqLabel", freqLabel)
        put("audioUS", audioUS); put("audioUK", audioUK); put("audioGen", audioGen)
        put("pos", buildJsonArray { pos.forEach { add(JsonPrimitive(it)) } })
        put("meanings", buildJsonArray {
            meanings.forEach {
                add(buildJsonObject {
                    put("pos", it.pos ?: ""); put("en", it.en); put("ar", it.ar)
                })
            }
        })
        put("inflections", buildJsonArray { inflections.forEach { add(JsonPrimitive(it)) } })
        put("derivatives", pairsJson(derivatives))
        put("synonyms", pairsJson(synonyms))
        put("collocations", pairsJson(collocations))
        put("examples", pairsJson(examples))
        put("differences", pairsJson(differences))
        put("right", right); put("wrong", wrong)
        if (lastResult == null) put("last", JsonPrimitive(null as String?)) else put("last", lastResult)
        put("interval", interval); put("due", due)
        put("favorite", favorite.toString())
        put("engineVersion", engineVersion)
    }

    private fun pairsJson(list: List<LangPair>): JsonArray = buildJsonArray {
        list.forEach { add(buildJsonObject { put("en", it.en); put("ar", it.ar) }) }
    }
}

private fun WordStatus?.toKey(): String? = when (this) {
    null -> null
    WordStatus.NEW -> "new"
    WordStatus.KNOWN -> "right"
    WordStatus.MISSED -> "wrong"
}

/**
 * يحسب الأعمدة المشتقة. تُستدعى قبل أي إدخال، فالقوائم والبحث لا يفكّان JSON أبداً.
 */
fun Word.derive(): Word {
    val first = meanings.firstOrNull()
    val blob = buildString {
        append(word.lowercase()).append(' ')
        meanings.forEach { append(it.en).append(' ').append(it.ar).append(' ') }
        synonyms.forEach { append(it.en).append(' ').append(it.ar).append(' ') }
        inflections.forEach { append(it).append(' ') }
        append(arabicPron)
    }.lowercase().trim()
    return copy(
        primaryEn = first?.en.orEmpty(),
        primaryAr = first?.ar.orEmpty(),
        searchBlob = blob,
        createdAt = if (createdAt > 0) createdAt else id
    )
}
