package com.tornado.vocab.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** معنى واحد: نوع الكلمة + الشرح الإنجليزي + مقابله العربي */
@Serializable
data class Meaning(val pos: String? = null, val en: String = "", val ar: String = "")

/**
 * زوج إنجليزي/عربي — وكلُّ حقلٍ سطرٌ مستقلّ عند العرض.
 *
 * `note` و`ex`/`exAr` أُضيفت لأن السطر كان يجمع اللغتين: «يخالف — ضدّ
 * abide by». فيقفز البصر بين اتّجاهين في السطر الواحد وتتعب القراءة.
 * فصار التوضيح سطراً، والمثال سطرين — كلٌّ بلغةٍ واحدة.
 */
@Serializable
data class LangPair(
    val en: String = "",
    val ar: String = "",
    val note: String = "",
    val ex: String = "",
    val exAr: String = ""
)

/**
 * حالة الكلمة في نظام التكرار المتباعد.
 * تطابق ألوان تطبيق الويب: أزرق جديد، أحمر أخطأت، أخضر تعرفها.
 */
enum class WordStatus { NEW, MISSED, KNOWN;
    companion object {
        fun of(lastResult: String?): WordStatus = when (lastResult) {
            null, "" -> NEW
            "right" -> KNOWN
            else -> MISSED
        }
    }
}

/**
 * كيان الكلمة الكامل — يطابق مخطط بيانات تطبيق الويب حقلاً بحقل،
 * مع أعمدة إضافية مشتقة تخدم الأداء عند آلاف الكلمات.
 *
 * الأعمدة المشتقة (primaryEn / primaryAr / searchBlob / statusRank) محسوبة عند الإدخال
 * حتى لا تحتاج شاشات القوائم لفكّ ترميز JSON لأي صف — وهذا هو الفرق بين قائمة
 * سلسة وقائمة متقطعة عند ٥٠٠٠ كلمة.
 */
@Entity(
    tableName = "words",
    indices = [
        Index(value = ["word"], unique = true),
        Index("due"),
        Index("lastResult"),
        Index("favorite"),
        Index("createdAt")
    ]
)
data class Word(
    @PrimaryKey val id: Long,
    val word: String,

    // النطق
    val ipa: String = "",
    val ipaUS: String = "",
    val ipaUK: String = "",
    val arabicPron: String = "",
    val audioUS: String = "",
    val audioUK: String = "",
    val audioGen: String = "",

    // التصنيف والمستوى
    val oxford: String = "",
    val cefr: String = "",
    val estCefr: String = "",
    val freqLabel: String = "",

    // المحتوى
    val pos: List<String> = emptyList(),
    val meanings: List<Meaning> = emptyList(),
    val inflections: List<String> = emptyList(),
    val derivatives: List<LangPair> = emptyList(),
    val synonyms: List<LangPair> = emptyList(),
    val collocations: List<LangPair> = emptyList(),
    val examples: List<LangPair> = emptyList(),
    val differences: List<LangPair> = emptyList(),

    // التكرار المتباعد
    val right: Int = 0,
    val wrong: Int = 0,
    val lastResult: String? = null,
    val interval: Int = 0,
    val due: Long = 0,

    // حالة محلية
    val favorite: Boolean = false,
    val createdAt: Long = 0,
    val engineVersion: Int = 0,

    // أعمدة مشتقة للأداء
    val primaryEn: String = "",
    val primaryAr: String = "",
    val searchBlob: String = ""
) {
    val status: WordStatus get() = WordStatus.of(lastResult)

    /** أفضل رابط نطق أمريكي متاح — صوت أكسفورد الرسمي أولاً */
    val bestUsAudio: String get() = audioUS.ifBlank { audioGen }

    val totalAnswers: Int get() = right + wrong

    val accuracy: Float get() = if (totalAnswers == 0) 0f else right.toFloat() / totalAnswers
}

/**
 * صف خفيف للقوائم — لا يحمل أي حقل JSON.
 * استعلام القائمة يقرأ هذه الأعمدة فقط، فيبقى التمرير سلساً مهما كبرت قاعدة البيانات.
 */
/** ما الذي يمثّله عنصر التشغيل — كلمة من المكتبة أم مقطع من ملاحظة */
enum class RowKind { WORD, NOTE_CHUNK }

/**
 * عنصر واحد في طابور المشغّل.
 *
 * الطابور كان يُمرَّر كـ[WordRow] — وهو إسقاط من جدول الكلمات لا أكثر. وحين
 * احتاجت الملاحظات الصوتية نفس المشغّل، بدت إضافة حقلين إليه أقصر طريق، لكنّ
 * Room يشترط أن يُرجع كل استعلام كل حقل — فانكسر كل استعلام في التطبيق مقابل
 * حقلين لا وجود لهما في أي جدول.
 *
 * والدرس أن الطابور ليس «كلمات» بل «عناصر تُشغَّل». الفصل هنا يجعل إضافة أي
 * محتوى جديد لاحقاً مسألة نوع جديد لا تعديلاً في طبقة البيانات.
 */
data class PlayItem(
    val id: Long,
    val title: String,
    val subtitle: String,
    val kind: RowKind = RowKind.WORD,
    /** رقم المقطع داخل الملاحظة — يخصّ [RowKind.NOTE_CHUNK] وحده */
    val chunkIndex: Int = 0,
    val status: String = "NEW",
    val favorite: Boolean = false
)

/** يحوّل صفّ كلمة إلى عنصر تشغيل — الجسر بين طبقة البيانات والمشغّل */
fun WordRow.toPlayItem(): PlayItem = PlayItem(
    id = id,
    title = word,
    subtitle = primaryAr.ifBlank { primaryEn },
    kind = RowKind.WORD,
    status = status.name,
    favorite = favorite
)

data class WordRow(
    val id: Long,
    val word: String,
    val primaryEn: String,
    val primaryAr: String,
    val lastResult: String?,
    val due: Long,
    val cefr: String,
    val estCefr: String,
    val oxford: String,
    val favorite: Boolean,
    val right: Int,
    val wrong: Int
) {
    val status: WordStatus get() = WordStatus.of(lastResult)
}

/** جدول البحث النصي الكامل — يبني فهرساً معكوساً فوق جدول الكلمات */
@Fts4(contentEntity = Word::class)
@Entity(tableName = "words_fts")
data class WordFts(
    val word: String,
    val searchBlob: String
)

/** ملخص إحصائي تحسبه قاعدة البيانات دفعة واحدة بدل تحميل كل الكلمات للذاكرة */
data class LibraryStats(
    val total: Int = 0,
    val newCount: Int = 0,
    val missed: Int = 0,
    val known: Int = 0,
    val dueNow: Int = 0,
    val favorites: Int = 0,
    val totalRight: Int = 0,
    val totalWrong: Int = 0
)

/** شاهد حذف — يمنع عودة كلمة محذوفة عند الدمج مع نسخة احتياطية أقدم */
@Entity(tableName = "tombstones")
data class Tombstone(
    @PrimaryKey val id: Long,
    val word: String,
    val deletedAt: Long
)
