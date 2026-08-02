package com.tornado.vocab

import com.tornado.vocab.audio.NarrationBuilder
import com.tornado.vocab.audio.NarrationDetail
import com.tornado.vocab.audio.NarrationMode
import com.tornado.vocab.audio.SegLang
import com.tornado.vocab.audio.SegRole
import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationBuilderTest {

    private fun word() = Word(
        id = 1,
        word = "resilient",
        arabicPron = "ريزيليينت",
        pos = listOf("adjective"),
        meanings = listOf(
            Meaning("adjective", "Able to recover quickly", "قادر على التعافي بسرعة"),
            Meaning("adjective", "Springing back into shape", "يعود لشكله")
        ),
        inflections = listOf("resilient", "resiliently"),
        synonyms = listOf(LangPair("tough", "قوي")),
        collocations = listOf(LangPair("resilient economy", "اقتصاد مرن")),
        examples = listOf(
            LangPair("She is remarkably resilient.", "إنها مرنة بشكل ملحوظ."),
            LangPair("A resilient material.", "مادة مرنة.")
        ),
        derivatives = listOf(LangPair("resilience", "مرونة"))
    )

    @Test
    fun `human only mode speaks the word and its examples`() {
        val segments = NarrationBuilder.build(
            word(), repeat = 1, mode = NarrationMode.HUMAN_ONLY,
            detail = NarrationDetail.FULL, speakArabic = true
        )
        assertEquals(SegRole.HEADWORD, segments.first().role)
        assertEquals("resilient", segments.first().text)
        assertTrue(segments.any { it.role == SegRole.EXAMPLE })
        // لا سقالة مولّدة في الوضع البشري
        assertFalse(segments.any { it.role == SegRole.GENERATED })
    }

    @Test
    fun `human only mode never emits arabic segments`() {
        val segments = NarrationBuilder.build(
            word(), repeat = 1, mode = NarrationMode.HUMAN_ONLY,
            detail = NarrationDetail.FULL, speakArabic = true
        )
        assertFalse(segments.any { it.lang == SegLang.AR })
    }

    @Test
    fun `word repeat multiplies the headword`() {
        val segments = NarrationBuilder.build(
            word(), repeat = 3, mode = NarrationMode.HUMAN_ONLY,
            detail = NarrationDetail.FULL, speakArabic = false
        )
        assertEquals(3, segments.count { it.role == SegRole.HEADWORD })
    }

    @Test
    fun `rich mode keeps every section from the web engine`() {
        val text = NarrationBuilder.build(
            word(), repeat = 1, mode = NarrationMode.RICH,
            detail = NarrationDetail.FULL, speakArabic = true
        ).joinToString(" | ") { it.text }

        listOf(
            "It has 2 meanings", "Its forms are", "Related words",
            "Similar words", "Common combinations", "Examples"
        ).forEach { assertTrue("missing section: $it", text.contains(it)) }
    }

    @Test
    fun `brief mode drops the extra sections`() {
        val text = NarrationBuilder.build(
            word(), repeat = 1, mode = NarrationMode.RICH,
            detail = NarrationDetail.BRIEF, speakArabic = true
        ).joinToString(" | ") { it.text }

        assertFalse(text.contains("Similar words"))
        assertFalse(text.contains("Its forms are"))
        assertTrue(text.contains("Able to recover quickly"))
    }

    @Test
    fun `arabic can be switched off in rich mode`() {
        val segments = NarrationBuilder.build(
            word(), repeat = 1, mode = NarrationMode.RICH,
            detail = NarrationDetail.FULL, speakArabic = false
        )
        assertFalse(segments.any { it.lang == SegLang.AR })
    }

    @Test
    fun `a word with no examples still produces the headword`() {
        val bare = Word(id = 2, word = "boon")
        val segments = NarrationBuilder.build(
            bare, repeat = 1, mode = NarrationMode.HUMAN_ONLY,
            detail = NarrationDetail.FULL, speakArabic = true
        )
        assertEquals(1, segments.size)
        assertEquals("boon", segments.first().text)
    }
}
