package com.tornado.vocab.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
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
    val json: String,
    /*
     * مكتوبةٌ بيدٍ ومكتملة.
     *
     * خانةٌ مستقلّة لا قراءةٌ من داخل النصّ: طابور الكلمات التي تنتظر بطاقة
     * يُحسب عند كل عرضٍ للقائمة، وتحليل مئةٍ وسبعين بطاقة في كل مرّة ليقرأ
     * منها حقلاً واحداً عبثٌ يُدفع ثمنه في كل تمرير.
     */
    val curated: Boolean = false,
    /**
     * المستوى كما في البطاقة المكتوبة — الرسميّ إن وُجد، وإلا التقدير.
     *
     * صفُّ القائمة خفيف: مشروعٌ من جدول الكلمات لا بطاقةٌ كاملة، فلا يمرّ
     * بنقطة الدمج. فكان يعرض مستوى القاموس القديم بينما تعرض البطاقة
     * المفتوحة تحته المستوى المكتوب — «≈ C1» فوق و«CEFR B2» تحت، في شاشةٍ
     * واحدة. وخانةٌ صغيرة هنا أرخص من قراءة ستّمائة كيلوبايت لتُرسم شارة.
     */
    val level: String = "",
    /** true إن كان `level` مستوىً رسمياً لا تقديراً — فتُرسم الشارة بلا «≈» */
    val levelExact: Boolean = false
)

/** مستوى كلمةٍ مكتوبة — مشروعٌ صغير لا بطاقةٌ كاملة */
data class CardLevel(val word: String, val level: String, val levelExact: Boolean)

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

    /** الكلمات التي وصلتها بطاقةٌ مكتوبة بيد — ما عداها ينتظر في الطابور */
    @Query("SELECT word FROM enrich_cards WHERE curated = 1")
    fun curatedWords(): Flow<List<String>>

    /** نفسها لقراءةٍ واحدة — لمن لا يراقب التغيّر */
    @Query("SELECT word FROM enrich_cards WHERE curated = 1")
    suspend fun curatedWordsOnce(): List<String>

    /** مستويات البطاقات المكتوبة — خريطةٌ صغيرة تخدم شارة صفّ القائمة */
    @Query("SELECT word, level, levelExact FROM enrich_cards WHERE curated = 1 AND level != ''")
    fun curatedLevels(): Flow<List<CardLevel>>
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
    /*
     * أزواج لا نصوص.
     *
     * القاعدة كانت تكتب «coping» وحدها، والمكتوب بيدٍ يكتب
     * «coping (noun)» ومعه «التأقلم». والقارئ العربي يحتاج الثاني —
     * ومشتقٌّ بلا معنىً نصفُ فائدة.
     */
    val derivatives: List<LangPair> = emptyList(),
    val synonyms: List<LangPair> = emptyList(),
    val antonyms: List<LangPair> = emptyList(),
    val examples: List<LangPair> = emptyList(),
    val collocations: List<LangPair> = emptyList(),
    /** الفروق: ما يميّز الكلمة عمّا يُخلَط بها */
    val differences: List<LangPair> = emptyList(),
    /*
     * أنماط التركيب: حرف الجرّ الصحيح وما يتبعه.
     *
     * أهمّ ما يحتاجه المتعلّم عملياً — «be patient with + شخص» تمنع
     * خطأً يتكرّر أكثر ممّا يمنعه أي تعريف. وكانت غائبةً عن البطاقات كلّها.
     */
    val grammarPatterns: List<LangPair> = emptyList(),
    /** تنبيه نطقٍ يفرّق بين متشابهين: patient ≠ patience */
    val pronunciationNote: List<LangPair> = emptyList(),
    val phrasalVerbs: List<Phrase> = emptyList(),
    val idioms: List<Phrase> = emptyList(),
    val usageNotes: List<LangPair> = emptyList(),
    val register: List<String> = emptyList(),
    val absent: List<String> = emptyList(),
    /** مكتوبةٌ بيدٍ ومكتملة — لا تُضمّ إليها بطاقة التطبيق القديمة */
    val curated: Boolean = false
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

        /**
         * يقبل الشكلين معاً: نصّاً مفرداً أو زوجاً.
         *
         * البطاقات القديمة تكتب «coping»، والجديدة {en, ar}. وهما في
         * المستودع جنباً إلى جنب ما دامت المراجعة لم تكتمل — فقارئٌ
         * يعرف أحدهما وحده يُفرغ نصف البطاقات بلا أن يشتكي.
         */
        private fun pairs(o: JSONObject, key: String): List<LangPair> {
            val a: JSONArray = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let {
                    val en = it.optString("en").ifBlank { it.optString("col") }
                    val ar = it.optString("ar").ifBlank { it.optString("pat") }
                    if (en.isBlank() && ar.isBlank()) null
                    else LangPair(en, ar, it.optString("note"),
                                  it.optString("ex"), it.optString("exAr"))
                } ?: a.optString(i).takeIf { it.isNotBlank() }
                    ?.let { LangPair(it, "") }
            }
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
                derivatives = pairs(o, "derivatives"),
                synonyms = pairs(o, "synonyms"),
                antonyms = pairs(o, "antonyms"),
                examples = pairs(o, "examples"),
                collocations = pairs(o, "collocations"),
                differences = pairs(o, "differences"),
                grammarPatterns = pairs(o, "grammarPatterns"),
                pronunciationNote = pairs(o, "pronunciationNote"),
                phrasalVerbs = objects(o, "phrasalVerbs").map {
                    Phrase(it.optString("phrase"), it.optString("gloss"))
                },
                idioms = objects(o, "idioms").map {
                    Phrase(it.optString("phrase"), it.optString("gloss"))
                },
                usageNotes = pairs(o, "usageNotes"),
                register = strings(o, "register"),
                absent = strings(o, "absent"),
                curated = o.optBoolean("curated", false)
            )
        }

        /** أسماء الأقسام الغائبة بالعربية — تُعرض كما هي إن لم تُعرف */
        fun absentLabel(key: String): String = when (key) {
            "antonyms" -> "الأضداد"
            "idioms" -> "التعابير"
            "usageNotes" -> "ملاحظات الاستعمال"
            "collocations" -> "المتلازمات"
            "grammarPatterns" -> "أنماط التركيب"
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
