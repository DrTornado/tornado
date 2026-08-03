package com.tornado.vocab.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * نصّ طويل يُستمع إليه.
 *
 * الكلمة والملاحظة يشتركان في المشغّل ولا يشتركان في شيء آخر: الكلمة لها معنى
 * ونطق وتصريف وجدولة مراجعة، والملاحظة نصّ يُقرأ من أوّله إلى آخره. حشرهما في
 * جدول واحد كان سيُدخل الملاحظات في الاختبار وفي إحصاءات المفردات وفي شاشة
 * الكلمات — فيفسد الثلاثة معاً.
 *
 * الجدول منفصل، والمشغّل مشترك.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: Long,
    val title: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** آخر مقطع وصل إليه المستخدم — نصّ طويل يُستأنف لا يُعاد من أوّله */
    val lastChunk: Int = 0,
    val favorite: Boolean = false
) {
    /** عدد المقاطع — يُحسب ولا يُخزَّن، فالنص هو مصدر الحقيقة الوحيد */
    val chunkCount: Int get() = NoteChunker.split(text).size

    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/** صفّ خفيف لقوائم الملاحظات — لا يحمل النص كاملاً */
/**
 * شاهدة حذف ملاحظة.
 *
 * بدونها كان الحذف يُنقض في اللحظة نفسها: تُمسح الملاحظة محلياً، ثم تسحب
 * المزامنة نسخة المستودع فتعود — والمستخدم يرى نوتةً تُمسح وترجع أمام عينيه
 * ولا يفهم لماذا.
 *
 * والجدول منفصل عن شواهد الكلمات عمداً: كلاهما يشتقّ معرّفه من الوقت الحالي،
 * فجمعهما في جدول واحد بمفتاح أساسي واحد يجعل حذف ملاحظة قادراً على إخفاء
 * كلمة أُنشئت في نفس الملّي ثانية.
 */
@Entity(tableName = "note_tombstones")
data class NoteTombstone(
    @PrimaryKey val id: Long,
    val deletedAt: Long
)

data class NoteRow(
    val id: Long,
    val title: String,
    val preview: String,
    val wordCount: Int,
    val chunkCount: Int,
    val lastChunk: Int,
    val favorite: Boolean
)

/**
 * يقسّم النص الطويل إلى مقاطع صالحة للاستماع.
 *
 * نصّ من ساعة كملف صوتي واحد عبء من كل وجه: لا يُبحث فيه، ولا يُستأنف من موضع،
 * ولا يُرفع للمستودع، وبناؤه يعني انتظاراً طويلاً قبل أول صوت.
 *
 * والقطع عند حدود الجُمل لا عند عدد الحروف: مقطع يبدأ من منتصف جملة يُربك
 * السامع أكثر مما يفيده.
 */
object NoteChunker {

    /** حجم يوازن بين زمن البناء وقابلية التنقّل — نحو دقيقة ونصف من الكلام */
    private const val TARGET_CHARS = 1_200
    private const val MAX_CHARS = 1_800

    fun split(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        if (clean.length <= MAX_CHARS) return listOf(clean)

        val sentences = clean.split(Regex("(?<=[.!?؟])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val current = StringBuilder()

        for (s in sentences) {
            // جملة أطول من الحدّ وحدها: تُقطع عند مسافة لا في منتصف كلمة
            if (s.length > MAX_CHARS) {
                if (current.isNotEmpty()) { out += current.toString().trim(); current.clear() }
                out += hardSplit(s)
                continue
            }
            if (current.length + s.length + 1 > TARGET_CHARS && current.isNotEmpty()) {
                out += current.toString().trim()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(s)
        }
        if (current.isNotEmpty()) out += current.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    private fun hardSplit(long: String): List<String> {
        val out = ArrayList<String>()
        var rest = long
        while (rest.length > MAX_CHARS) {
            val cut = rest.lastIndexOf(' ', MAX_CHARS).takeIf { it > MAX_CHARS / 2 } ?: MAX_CHARS
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    /** عنوان مقترح حين لا يعطي المستخدم واحداً — أول سطر أو أول بضع كلمات */
    fun titleFrom(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isNotBlank() && firstLine.length <= 60) return firstLine
        return firstLine.take(57).substringBeforeLast(' ').ifBlank { "Note" } + "…"
    }
}
