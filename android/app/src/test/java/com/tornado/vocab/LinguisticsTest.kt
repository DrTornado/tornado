package com.tornado.vocab

import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Linguistics
import com.tornado.vocab.data.ReferenceData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinguisticsTest {

    @Test
    fun `irregular verbs use their real past forms`() {
        val forms = Linguistics.inflect("run", listOf("verb"))
        assertTrue(forms.contains("ran"))
        assertTrue(forms.contains("running"))
        assertFalse("must not invent 'runned'", forms.contains("runned"))
    }

    @Test
    fun `regular verbs double the final consonant when short`() {
        assertTrue(Linguistics.inflect("stop", listOf("verb")).contains("stopping"))
    }

    @Test
    fun `y becomes ies for nouns`() {
        assertTrue(Linguistics.inflect("city", listOf("noun")).contains("cities"))
    }

    @Test
    fun `silent e is dropped before ing`() {
        assertTrue(Linguistics.inflect("make", listOf("verb")).contains("making"))
    }

    @Test
    fun `multi word phrases are not inflected`() {
        assertTrue(Linguistics.inflect("give up", listOf("verb")).isEmpty())
    }

    @Test
    fun `ipa converts to arabic letters`() {
        val out = Linguistics.ipaToArabic("/rɪˈzɪliənt/")
        assertTrue("expected arabic output, got '$out'", out.isNotEmpty())
        assertTrue(Linguistics.isArabic(out))
    }

    @Test
    fun `empty ipa yields empty string`() {
        assertEquals("", Linguistics.ipaToArabic(""))
        assertEquals("", Linguistics.ipaToArabic(null))
    }

    @Test
    fun `collocations need a real content partner`() {
        val junk = listOf(LangPair("the brand", ""), LangPair("brand of", ""))
        assertTrue(Linguistics.sanitizeCollocations("brand", junk).isEmpty())
    }

    @Test
    fun `genuine collocations survive sanitising`() {
        val good = listOf(
            LangPair("brand loyalty", ""),
            LangPair("global brand", ""),
            LangPair("brand awareness", "")
        )
        assertEquals(3, Linguistics.sanitizeCollocations("brand", good).size)
    }

    @Test
    fun `a single collocation is dropped as unreliable`() {
        val lonely = listOf(LangPair("brand loyalty", ""))
        assertTrue(Linguistics.sanitizeCollocations("brand", lonely).isEmpty())
    }

    @Test
    fun `word form tries reach the root`() {
        assertTrue(ReferenceData.wordFormTries("running").contains("runn"))
        assertTrue(ReferenceData.wordFormTries("cities").contains("city"))
        assertTrue(ReferenceData.wordFormTries("glaciers").contains("glacier"))
    }

    @Test
    fun `cefr estimate rises with rarity`() {
        assertEquals("A1", ReferenceData.estCefrFromRank(500))
        assertEquals("B1", ReferenceData.estCefrFromRank(4000))
        assertEquals("C1", ReferenceData.estCefrFromRank(15000))
    }
}
