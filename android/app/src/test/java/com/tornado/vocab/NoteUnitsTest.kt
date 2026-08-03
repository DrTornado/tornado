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
