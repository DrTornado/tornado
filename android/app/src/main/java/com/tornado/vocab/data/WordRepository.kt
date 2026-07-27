package com.tornado.vocab.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * مستودع البيانات — الطبقة الوحيدة التي تعرف مصدر البيانات.
 * الواجهة لا تتعامل مع Room ولا JSON مباشرة (فصل تام بين البيانات والعرض).
 */
class WordRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).wordDao()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun all(): Flow<List<Word>> = dao.all()
    fun favorites(): Flow<List<Word>> = dao.favorites()
    fun search(q: String): Flow<List<Word>> = dao.search(q.trim())
    fun byId(id: Long): Flow<Word?> = dao.byId(id)

    suspend fun toggleFavorite(w: Word) = dao.setFavorite(w.id, !w.favorite)

    /** يستورد البيانات من assets مرة واحدة فقط عند أول تشغيل */
    suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.count() > 0) return@withContext
        val raw = context.assets.open("words.json").bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(raw)
        val arr = when {
            root is JsonArray -> root
            root is JsonObject && root["words"] is JsonArray -> root["words"] as JsonArray
            else -> return@withContext
        }
        val items = arr.mapNotNull { el -> runCatching { parse(el.jsonObject) }.getOrNull() }
        if (items.isNotEmpty()) dao.insertAll(items)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.csv(k: String): String =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.joinToString(", ").orEmpty()

    private fun parse(o: JsonObject): Word {
        val meanings = (o["meanings"] as? JsonArray) ?: JsonArray(emptyList())
        val blob = buildString {
            append(o.str("word")).append(' ')
            meanings.forEach { m ->
                val mo = m as? JsonObject ?: return@forEach
                append(mo.str("en")).append(' ').append(mo.str("ar")).append(' ')
            }
        }.lowercase()
        return Word(
            id = (o["id"] as? JsonPrimitive)?.longOrNull ?: o.str("word").hashCode().toLong(),
            word = o.str("word"),
            ipa = o.str("ipa"),
            arabicPron = o.str("arabicPron"),
            oxford = o.str("oxford"),
            cefr = o.str("cefr"),
            audioUS = o.str("audioUS"),
            audioUK = o.str("audioUK"),
            posCsv = o.csv("pos"),
            meaningsJson = meanings.toString(),
            inflectionsCsv = o.csv("inflections"),
            synonymsJson = (o["synonyms"] ?: JsonArray(emptyList())).toString(),
            collocationsJson = (o["collocations"] ?: JsonArray(emptyList())).toString(),
            examplesJson = (o["examples"] ?: JsonArray(emptyList())).toString(),
            searchBlob = blob
        )
    }

    /** يفكّ ترميز قائمة أزواج (إنجليزي/عربي) للعرض */
    fun pairs(jsonText: String): List<Pair2> = runCatching {
        json.decodeFromString<List<Pair2>>(jsonText)
    }.getOrElse { emptyList() }

    fun meanings(jsonText: String): List<Meaning> = runCatching {
        json.decodeFromString<List<Meaning>>(jsonText)
    }.getOrElse { emptyList() }
}
