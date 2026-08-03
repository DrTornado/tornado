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

    /**
     * يقسّم النصّ إلى جُمل — وحدة التشغيل الحقيقية.
     *
     * المقطع الكبير (١٢٠٠ حرف) كان وحدة الطابور، فيُبنى كاملاً قبل أن يُسمع
     * حرفٌ واحد: عشرون ثانية صمتٍ بعد ضغطة التشغيل، ويظنّ المستخدم الزرّ
     * معطّلاً. والجملة تُبنى في ثانية أو اثنتين، فيبدأ الصوت بعد جملتين
     * ويستمرّ البناء خلفه — وهو ما يفعله المشغّل مع الكلمات أصلاً.
     *
     * والجمل القصيرة جداً تُضمّ لما بعدها: «نعم.» وحدها تقطّع السماع بلا داعٍ.
     */
    /**
     * وحدات التشغيل — فقرات أو جُملاً، كما يختار المستخدم بزرّ FULL/SHORT.
     *
     * وأول ما يجب حفظه هو الأسطر الجديدة. كان التقسيم يبدأ بـ`\s+ → " "` فيمحو
     * كل فاصل بين الفقرات قبل أن يراه أحد — ثم يُسأل «كرّر الفقرة» ولا فقرات
     * في النص أصلاً. فبقي FULL بلا أثر، وسأل المستخدم محقاً: «هل تعرف حدود
     * الفقرة أصلاً؟» والجواب كان: لا، لأنني أتلفها في السطر الأول.
     *
     * والفقرة تُعرَّف بسطر فارغ، فإن لم يوجد فبسطر واحد — فمن يلصق نصاً من
     * صفحة ويب تصله فقراته بسطر مفرد لا بسطرين.
     */
    fun units(text: String, byParagraph: Boolean): List<String> {
        val src = text.trim()
        if (src.isBlank()) return emptyList()

        val paragraphs = when {
            src.contains(Regex("\\n\\s*\\n")) -> src.split(Regex("\\n\\s*\\n+"))
            src.contains('\n') -> src.split('\n')
            else -> listOf(src)
        }.map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotBlank() }

        if (byParagraph) return paragraphs

        /*
         * الجملة جملةٌ واحدة — لا كتلة من جمل.
         *
         * كنت أجمعها في كتل من مئة وثمانين حرفاً ليبدأ الصوت أسرع، فصارت
         * «الجملة» بحجم الفقرة تقريباً: يضغط المستخدم SHORT فيسمع نفس ما يسمعه
         * مع FULL، ويقول محقاً «لا يكرّر الجملة». والسرعة لا تُشترى بإفساد
         * المعنى الذي طلبه صراحةً.
         *
         * والجملة القصيرة جداً وحدها تُضمّ لما بعدها — «نعم.» مقطعاً مستقلاً
         * تقطيعٌ لا قراءة.
         */
        val out = ArrayList<String>()
        paragraphs.forEach { p ->
            val paragraphStart = out.size
            splitSentences(p).forEach { s ->
                val tooShort = s.length < MIN_SENTENCE_CHARS
                if (tooShort && out.size > paragraphStart) {
                    out[out.lastIndex] = out.last() + " " + s
                } else {
                    out += s
                }
            }
        }
        return out
    }

    /** يُبقى للتوافق — الجُمل هي الوضع الافتراضي */
    fun sentences(text: String): List<String> = units(text, byParagraph = false)

    /**
     * ما دون هذا ليس جملة بل شذرة — «نعم.» أو «حسناً.».
     *
     * كان الحدّ خمسة وعشرين فابتلع جملاً حقيقية من ثلاثة وعشرين حرفاً، وهو ما
     * أمسكه الاختبار: «And a third one closes.» جملة تامّة لا شذرة.
     */
    private const val MIN_SENTENCE_CHARS = 12

    /*
     * ليست كل نقطة نهايةَ جملة.
     *
     * القطع عند كل `.` يشطر «a groundbreaking U.S. study» نصفين، فيسمع
     * المستخدم جملةً مبتورة عند «U.S.» ثم شذرةً تبدأ بـ«study» — وهذا ما ظهر
     * على الجوال فعلاً عند أول قراءة حقيقية.
     *
     * فنُخفي نقاط الاختصارات خلف حرفٍ لا يَرِد في نصّ بشري، ثم نقطع، ثم نعيدها.
     * والخطأ هنا مقصود في اتجاه واحد: أن نصل جملتين خيرٌ من أن نبتر واحدة.
     */
    private const val DOT = ''

    /** ما يتكرّر فيه الحرف والنقطة: U.S. · e.g. · a.m. */
    private val DOTTED = Regex("\\b(?:[A-Za-z]\\.){2,}")

    /** ألقاب واختصارات شائعة تليها نقطة ولا تنتهي بها جملة */
    private val ABBREV = Regex(
        "\\b(?:Mr|Mrs|Ms|Dr|Prof|Rev|Hon|St|Jr|Sr|Inc|Ltd|Co|Corp|Univ|Dept|" +
            "vs|etc|approx|est|No|Fig|Vol|Ch|Sec|Gen|Sen|Rep|Gov|Col|Capt|Lt|Sgt|" +
            "Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec)\\."
    )

    /** الحرف الأول من اسم — «John F. Kennedy» */
    private val INITIAL = Regex("\\b[A-Z]\\.(?=\\s+[A-Z])")

    fun splitSentences(paragraph: String): List<String> {
        var masked = paragraph
        listOf(DOTTED, ABBREV, INITIAL).forEach { r ->
            masked = r.replace(masked) { it.value.replace('.', DOT) }
        }
        return masked.split(Regex("(?<=[.!?؟])\\s+"))
            .map { it.replace(DOT, '.') }
            .filter { it.isNotBlank() }
    }

    fun split(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        if (clean.length <= MAX_CHARS) return listOf(clean)

        val sentences = splitSentences(clean)
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
