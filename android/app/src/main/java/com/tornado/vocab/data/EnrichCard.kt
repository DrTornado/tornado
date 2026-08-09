package com.tornado.vocab.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import org.json.JSONObject

/**
 * بطاقة الإثراء كما تصل من المستودع.
 *
 * تُبنى على خوادم GitHub من قاعدة معرفة بنصف مليون مدخل — لا على جهاز
 * المستخدم ولا على جواله. ويصل الجوال إليها شرائحَ مفهرسة، فإضافة كلمةٍ
 * تكلّف تنزيل شريحةٍ واحدة لا الملف كلّه.
 *
 * وتُخزَّن نصاً كما وصلت: البطاقة تُقرأ ككتلة واحدة عند فتح الكلمة، فلا
 * فائدة من تفكيكها إلى جداول تُجمع ثانيةً عند كل عرض. وهذا نفس ما فعلته
 * `Word` بقوائمها منذ البداية.
 */
@Entity(tableName = "enrich_cards")
data class EnrichCard(
    @PrimaryKey val word: String,
    val json: String
)

/** بصمة شريحة — بها نعرف ما تغيّر فلا ننزّل ما لم يتغيّر */
@Entity(tableName = "enrich_shards")
data class EnrichShard(
    @PrimaryKey val key: String,
    val hash: String
)

@Dao
interface EnrichDao {

    @Query("SELECT json FROM enrich_cards WHERE word = :word LIMIT 1")
    suspend fun cardJson(word: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCards(cards: List<EnrichCard>)

    @Query("SELECT * FROM enrich_shards")
    suspend fun shards(): List<EnrichShard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putShard(shard: EnrichShard)

    @Query("SELECT COUNT(*) FROM enrich_cards")
    suspend fun count(): Int
}

/** زوجٌ بعنوان — للأفعال المركّبة والتعابير: العبارة وشرحها */
data class Phrase(val phrase: String, val gloss: String)

/**
 * البطاقة بعد الفكّ — للعرض وحده.
 *
 * وما لا مصدر له يُذكر في [absent] صراحةً بدل أن يُختلق أو يُطوى بصمت:
 * القارئ يستحق أن يعرف أن المصدر خالٍ، لا أن يظنّ التطبيق كسل.
 */
data class Enrichment(
    val word: String = "",
    val ipaUS: String = "",
    val ipaUK: String = "",
    val ipaGen: String = "",
    val arabicPron: String = "",
    val oxford: String = "",
    val cefr: String = "",
    val cefrEst: String = "",
    val pos: List<String> = emptyList(),
    val meanings: List<Meaning> = emptyList(),
    val inflections: List<String> = emptyList(),
    val derivatives: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val examples: List<LangPair> = emptyList(),
    val collocations: List<LangPair> = emptyList(),
    val phrasalVerbs: List<Phrase> = emptyList(),
    val idioms: List<Phrase> = emptyList(),
    val usageNotes: List<String> = emptyList(),
    val register: List<String> = emptyList(),
    val absent: List<String> = emptyList()
) {
    companion object {

        private fun strings(o: JSONObject, key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull {
                a.optString(it).takeIf { s -> s.isNotBlank() }
            }
        }

        private fun objects(o: JSONObject, key: String): List<JSONObject> {
            val a: JSONArray = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }

        /** لا يرمي أبداً: بطاقةٌ معطوبة تعني كلمةً بلا إثراء، لا شاشةً ساقطة */
        fun parse(json: String?): Enrichment? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val ipa = o.optJSONObject("ipa")
            return Enrichment(
                word = o.optString("word"),
                ipaUS = ipa?.optString("us").orEmpty(),
                ipaUK = ipa?.optString("uk").orEmpty(),
                ipaGen = ipa?.optString("gen").orEmpty(),
                arabicPron = o.optString("arabicPron"),
                oxford = o.optString("oxford"),
                cefr = o.optString("cefr"),
                cefrEst = o.optString("cefrEst"),
                pos = strings(o, "pos"),
                meanings = objects(o, "meanings").map {
                    Meaning(
                        pos = it.optString("pos").takeIf { p -> p.isNotBlank() },
                        en = it.optString("en"),
                        ar = it.optString("ar")
                    )
                },
                inflections = strings(o, "inflections"),
                derivatives = strings(o, "derivatives"),
                synonyms = strings(o, "synonyms"),
                antonyms = strings(o, "antonyms"),
                examples = objects(o, "examples").map {
                    LangPair(it.optString("en"), it.optString("ar"))
                },
                // المتلازمة نمطٌ ومصاحب: «soil · compound» — والنمط يوضّح العلاقة
                collocations = objects(o, "collocations").mapNotNull {
                    val col = it.optString("col")
                    if (col.isBlank()) null else LangPair(col, it.optString("pat"))
                },
                phrasalVerbs = objects(o, "phrasalVerbs").map {
                    Phrase(it.optString("phrase"), it.optString("gloss"))
                },
                idioms = objects(o, "idioms").map {
                    Phrase(it.optString("phrase"), it.optString("gloss"))
                },
                usageNotes = strings(o, "usageNotes"),
                register = strings(o, "register"),
                absent = strings(o, "absent")
            )
        }

        /** أسماء الأقسام الغائبة بالعربية — تُعرض كما هي إن لم تُعرف */
        fun absentLabel(key: String): String = when (key) {
            "antonyms" -> "الأضداد"
            "idioms" -> "التعابير"
            "usageNotes" -> "ملاحظات الاستعمال"
            "collocations" -> "المتلازمات"
            "phrasalVerbs" -> "الأفعال المركّبة"
            "examples" -> "الأمثلة"
            "derivatives" -> "المشتقّات"
            "synonyms" -> "المرادفات"
            "inflections" -> "التصريفات"
            "ipa" -> "النطق الصوتي"
            "cefr" -> "المستوى"
            else -> key
        }
    }
}
