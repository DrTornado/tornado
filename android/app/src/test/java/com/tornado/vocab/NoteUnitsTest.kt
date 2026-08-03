package com.tornado.vocab

import com.tornado.vocab.data.NoteChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حدود الفقرة والجملة.
 *
 * كان التقسيم يبدأ بمحو كل مسافة بيضاء — بما فيها الأسطر الجديدة — فتختفي
 * الفقرات قبل أن يراها أحد. ثم يُسأل زرّ FULL أن «يكرّر الفقرة» ولا فقرة في
 * النصّ أصلاً، فلا يفعل شيئاً. والمستخدم سأل: «هل تعرف حدود الفقرة أصلاً؟»
 * وكان الجواب لا. هذه الاختبارات تمنع عودة ذلك.
 */
class NoteUnitsTest {

    private val twoParagraphs = """
        First paragraph. It has two sentences.

        Second paragraph here. And a second sentence in it.
    """.trimIndent()

    @Test
    fun `FULL keeps each paragraph whole`() {
        val units = NoteChunker.units(twoParagraphs, byParagraph = true)
        assertEquals("فقرتان لا أكثر ولا أقل", 2, units.size)
        assertTrue(units[0].startsWith("First paragraph"))
        assertTrue(units[1].startsWith("Second paragraph"))
    }

    @Test
    fun `SHORT never merges across a paragraph break`() {
        val units = NoteChunker.units(twoParagraphs, byParagraph = false)
        assertTrue("كل مقطع من فقرة واحدة", units.none {
            it.contains("two sentences") && it.contains("Second paragraph")
        })
    }

    @Test
    fun `a single newline still separates paragraphs`() {
        // نصّ ملصوق من صفحة ويب: أسطره مفردة لا مزدوجة
        val pasted = "Line one is a paragraph.\nLine two is another one."
        assertEquals(2, NoteChunker.units(pasted, byParagraph = true).size)
    }

    @Test
    fun `text without any break is one paragraph`() {
        val flat = "One sentence. Two sentences. Three sentences."
        assertEquals(1, NoteChunker.units(flat, byParagraph = true).size)
    }
}

/**
 * الجملة جملة، والفقرة فقرة — ولا يلتبسان.
 *
 * كانت الجمل تُجمع في كتل من مئة وثمانين حرفاً «ليبدأ الصوت أسرع»، فصارت
 * الكتلة بحجم الفقرة تقريباً: يضغط المستخدم SHORT فيسمع ما يسمعه مع FULL
 * حرفاً بحرف، ويقول محقاً «لا يكرّر الجملة».
 */
class SentenceIsNotParagraphTest {

    private val oneParagraph =
        "The first sentence is here. The second sentence follows it. And a third one closes."

    @Test
    fun `SHORT gives one unit per sentence`() {
        val units = NoteChunker.units(oneParagraph, byParagraph = false)
        assertEquals("ثلاث جمل، ثلاث وحدات", 3, units.size)
        assertTrue(units[0].startsWith("The first"))
        assertTrue(units[1].startsWith("The second"))
    }

    @Test
    fun `FULL gives one unit for that same paragraph`() {
        assertEquals(1, NoteChunker.units(oneParagraph, byParagraph = true).size)
    }

    @Test
    fun `SHORT and FULL must differ when a paragraph holds several sentences`() {
        val short = NoteChunker.units(oneParagraph, byParagraph = false)
        val full = NoteChunker.units(oneParagraph, byParagraph = true)
        assertTrue("لو تساويا لكان زرّ SHORT بلا معنى", short.size > full.size)
    }
}

/**
 * الطابور الفعلي — لا التقسيم وحده.
 *
 * التقسيم صحّ ثلاث مرات ثم انكسر التكرار، لأنني كنت أختبر `units()` وأثق
 * بالطابور بلا اختبار. وهذا يعيد بناء ما يبنيه المشغّل بالضبط: كل وحدة
 * تُكرَّر N مرة متتالية، فإن كرّر الفقرة بدل الجملة سقط هنا لا عند المستخدم.
 */
class NoteQueueTest {

    /** فقرتان، ثلاث جمل في كل فقرة — بنية معروفة سلفاً */
    private val note = """
        First sentence of paragraph one. Second sentence of paragraph one. Third sentence of paragraph one.

        First sentence of paragraph two. Second sentence of paragraph two. Third sentence of paragraph two.
    """.trimIndent()

    /** نسخة طبق الأصل من بناء الطابور في PlaybackService */
    private fun queue(detailed: Boolean, say: Int): List<String> =
        NoteChunker.units(note, detailed).flatMap { text -> List(say) { text } }

    @Test fun `SHORT مع Say ×2 يكرر كل جملة مرتين`() {
        val q = queue(detailed = false, say = 2)
        assertEquals("ست جمل × مرتين", 12, q.size)
        assertEquals(q[0], q[1])                 // الجملة الأولى مرتين متتاليتين
        assertTrue("ثم تنتقل للثانية", q[2] != q[1])
        assertTrue(q[0].startsWith("First sentence of paragraph one"))
        assertTrue(q[2].startsWith("Second sentence of paragraph one"))
    }

    @Test fun `FULL مع Say ×2 يكرر كل فقرة مرتين`() {
        val q = queue(detailed = true, say = 2)
        assertEquals("فقرتان × مرتين", 4, q.size)
        assertEquals(q[0], q[1])
        assertTrue("الفقرة تحوي جملها الثلاث", q[0].contains("Third sentence of paragraph one"))
        assertTrue("والثانية فقرة أخرى", q[2].startsWith("First sentence of paragraph two"))
    }

    @Test fun `Say ×1 لا يكرر شيئاً في الوضعين`() {
        assertEquals(6, queue(detailed = false, say = 1).size)
        assertEquals(2, queue(detailed = true, say = 1).size)
    }
}

/**
 * الاختصارات — عيبٌ وجدته على الجوال لا في الاختبارات.
 *
 * ظهرت على الشاشة جملة «In particular, a groundbreaking U.S.» ثم شذرة تبدأ
 * بـ«study»: القاطع رأى النقطة في «U.S.» فظنّها نهاية. والمستخدم يسمع بتراً
 * لا جملة.
 */
class AbbreviationTest {

    @Test fun `U S لا تقطع الجملة`() {
        val s = NoteChunker.units(
            "In particular, a groundbreaking U.S. study confirmed it. A second sentence follows.",
            byParagraph = false
        )
        assertEquals(2, s.size)
        assertTrue("الجملة الأولى كاملة", s[0].contains("U.S. study confirmed"))
    }

    @Test fun `الألقاب والحروف الأولى لا تقطع`() {
        val s = NoteChunker.units(
            "Dr. Smith met John F. Kennedy in the U.K. yesterday evening. Then he left.",
            byParagraph = false
        )
        assertEquals(2, s.size)
        assertTrue(s[0].startsWith("Dr. Smith met John F. Kennedy"))
    }

    @Test fun `النقطة الحقيقية ما زالت تقطع`() {
        val s = NoteChunker.units("One ends here. Two starts here and ends.", byParagraph = false)
        assertEquals(2, s.size)
    }
}
