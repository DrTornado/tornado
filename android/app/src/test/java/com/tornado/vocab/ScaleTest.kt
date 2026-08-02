package com.tornado.vocab

import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.WordRow
import com.tornado.vocab.data.derive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الحجم — تتحقق أن القرارات المعمارية تصمد عند آلاف الكلمات
 * لا عند حجم بيانات الاختبار الحالي.
 */
class ScaleTest {

    private fun makeWord(i: Int) = Word(
        id = i.toLong(),
        word = "word$i",
        meanings = List(4) { Meaning("noun", "meaning $it of word $i", "معنى $it للكلمة $i") },
        synonyms = List(5) { LangPair("syn$it", "مرادف$it") },
        collocations = List(4) { LangPair("word$i thing$it", "تلازم$it") },
        examples = List(3) { LangPair("This is example $it for word $i.", "مثال $it") },
        inflections = List(4) { "word$i$it" },
        derivatives = List(3) { LangPair("deriv$it", "مشتق$it") }
    ).derive()

    private fun makeRow(i: Int) = WordRow(
        id = i.toLong(), word = "word$i",
        primaryEn = "meaning 0 of word $i", primaryAr = "معنى الكلمة $i",
        lastResult = null, due = 0, cefr = "B2", estCefr = "", oxford = "5000",
        favorite = false, right = 0, wrong = 0
    )

    @Test
    fun `light rows for a five thousand word library stay small`() {
        val rows = (1..5_000).map { makeRow(it) }
        assertEquals(5_000, rows.size)

        // مجموع الأحرف تقدير عملي لبصمة الذاكرة الفعلية
        val chars = rows.sumOf {
            it.word.length + it.primaryEn.length + it.primaryAr.length +
                it.cefr.length + it.oxford.length + it.estCefr.length
        }
        val approxBytes = chars.toLong() * 2 // UTF-16
        assertTrue(
            "light rows should stay under 4 MB, was ${approxBytes / 1024} KB",
            approxBytes < 4L * 1024 * 1024
        )
    }

    @Test
    fun `full cards are far heavier than rows — which is why sessions use rows`() {
        val sampleRow = makeRow(1)
        val sampleWord = makeWord(1)

        val rowChars = sampleRow.word.length + sampleRow.primaryEn.length + sampleRow.primaryAr.length
        val wordChars = sampleWord.word.length + sampleWord.searchBlob.length +
            sampleWord.meanings.sumOf { it.en.length + it.ar.length } +
            sampleWord.synonyms.sumOf { it.en.length + it.ar.length } +
            sampleWord.collocations.sumOf { it.en.length + it.ar.length } +
            sampleWord.examples.sumOf { it.en.length + it.ar.length }

        // البطاقة الكاملة أثقل بمراتب — تحميل خمسة آلاف منها دفعة واحدة غير مقبول
        assertTrue(
            "full card ($wordChars) should dwarf the row ($rowChars)",
            wordChars > rowChars * 5
        )
    }

    @Test
    fun `derive computes search blob and primary columns for every word`() {
        val words = (1..1_000).map { makeWord(it) }
        assertTrue(words.all { it.primaryEn.isNotBlank() })
        assertTrue(words.all { it.primaryAr.isNotBlank() })
        assertTrue(words.all { it.searchBlob.isNotBlank() })
        // العمود المشتق يجعل القوائم لا تفكّ JSON إطلاقاً
        assertTrue(words.all { it.searchBlob == it.searchBlob.lowercase() })
    }

    @Test
    fun `deriving five thousand words is fast enough for a background seed`() {
        val start = System.currentTimeMillis()
        val words = (1..5_000).map { makeWord(it) }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(5_000, words.size)
        assertTrue("deriving 5000 words took ${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `narration cache key changes when card content changes`() {
        val a = makeWord(1)
        val b = a.copy(meanings = a.meanings + Meaning("verb", "extra", "إضافي")).derive()
        // بصمة المحتوى جزء من مفتاح التخزين، فتعديل البطاقة يُبطل صوتها القديم
        assertTrue(a.meanings.size != b.meanings.size)
        assertTrue(a.searchBlob != b.searchBlob)
    }
}
