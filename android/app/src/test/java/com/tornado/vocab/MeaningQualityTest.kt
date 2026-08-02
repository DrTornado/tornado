package com.tornado.vocab

import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.MeaningQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات تنقية المعاني.
 *
 * كُتبت بعد عطل صامت: نمط متعدد الأسطر أنتج بدائل فارغة تطابق كل نص، فاعتُبرت
 * كل المعاني ضجيجاً ولم يتغيّر شيء في المكتبة. لم يفشل بناء ولا ظهر خطأ —
 * والدليل الوحيد كان قاعدة بيانات لم تتبدّل. هذه الاختبارات تجعل مثله يُكتشف
 * في ثانية بدل أن يُكتشف بالمصادفة.
 */
class MeaningQualityTest {

    @Test
    fun `real definitions survive`() {
        assertTrue(MeaningQuality.isRealDefinition("To move on skis"))
        assertTrue(MeaningQuality.isRealDefinition("A group of sports using skis"))
        assertTrue(MeaningQuality.isRealDefinition("To endure without yielding"))
    }

    @Test
    fun `inflection references are rejected`() {
        assertFalse(MeaningQuality.isRealDefinition("present participle and gerund of ski"))
        assertFalse(MeaningQuality.isRealDefinition("plural of apothecary"))
        assertFalse(MeaningQuality.isRealDefinition("simple past of abide"))
        assertFalse(MeaningQuality.isRealDefinition("alternative form of colour"))
    }

    @Test
    fun `cross references are rejected`() {
        assertFalse(MeaningQuality.isRealDefinition("see also: synchronise"))
        assertFalse(MeaningQuality.isRealDefinition("variant of sync"))
    }

    @Test
    fun `leading usage labels are stripped`() {
        assertEquals(
            "To travel over a slope",
            MeaningQuality.cleanEnglish("(especially as a sport) To travel over a slope")
        )
    }

    @Test
    fun `semicolon alternatives are cut to the first clause`() {
        assertEquals(
            "To travel over a slope on skis",
            MeaningQuality.cleanEnglish("To travel over a slope on skis; to travel on skis at a place")
        )
    }

    @Test
    fun `arabic carrying latin terms is refused`() {
        // هذا بالضبط ما رآه المستخدم: مصطلح نحوي عجز عنه المترجم فبقي إنجليزياً
        assertFalse(
            MeaningQuality.isUsableArabic("المشاركة الحالية و gerund من التزلج", "…")
        )
    }

    @Test
    fun `untranslated echo is refused`() {
        assertFalse(MeaningQuality.isUsableArabic("To move on skis", "To move on skis"))
    }

    @Test
    fun `translator markup is stripped`() {
        // ما ظهر فعلاً في قائمة الكلمات: وسم من خدمة الترجمة داخل المعنى
        assertEquals(
            "عدم التحلي بالشجاعة",
            MeaningQuality.cleanArabic("<g id=\"1\">عدم التحلي بالشجاعة.</g>")
        )
    }

    @Test
    fun `markup that survives cleaning is refused`() {
        assertFalse(MeaningQuality.isUsableArabic("<g id=\"1\">عدم التحلي", "to discourage"))
    }

    @Test
    fun `translator failure replies are refused`() {
        // ما ظهر فعلاً تحت confederacy — ردّ عجز يمرّ كأنه ترجمة
        assertFalse(
            MeaningQuality.isUsableArabic(
                "ماذا؟",
                "A group of people or states joined together for a common purpose"
            )
        )
    }

    @Test
    fun `a translation far shorter than the source is refused`() {
        assertFalse(
            MeaningQuality.isUsableArabic("نعم", "To endure something without yielding to it")
        )
    }

    @Test
    fun `short sources are not judged by length`() {
        // «Harmony» ترجمتها كلمة واحدة بحق، فالقاعدة يجب ألا تعاقبها
        assertTrue(MeaningQuality.isUsableArabic("انسجام", "Harmony"))
    }

    @Test
    fun `clean arabic is accepted`() {
        assertTrue(MeaningQuality.isUsableArabic("للتحرك على الزلاجات", "To move on skis"))
    }

    @Test
    fun `refine drops noise and keeps order`() {
        val input = listOf(
            Meaning("verb", "To move on skis", "للتحرك على الزلاجات"),
            Meaning("verb", "present participle and gerund of ski", "المشاركة الحالية و gerund"),
            Meaning("noun", "A group of sports utilizing skis as primary equipment.", "")
        )
        val out = MeaningQuality.refine(input)
        assertEquals(2, out.size)
        assertEquals("To move on skis", out[0].en)
        assertEquals("للتحرك على الزلاجات", out[0].ar)
    }

    @Test
    fun `refine removes duplicates`() {
        val input = listOf(
            Meaning("noun", "A group of sports using skis", ""),
            Meaning("noun", "A group of sports using skis.", "")
        )
        assertEquals(1, MeaningQuality.refine(input).size)
    }

    @Test
    fun `refine removes reworded duplicates`() {
        // نفس المعنى بمفردة مختلفة — ينجو من المقارنة الحرفية ويجب أن يُمسك
        val input = listOf(
            Meaning("noun", "A group of sports utilizing skis as primary equipment", ""),
            Meaning("noun", "A group of sports using skis as primary equipment", "")
        )
        assertEquals(1, MeaningQuality.refine(input).size)
    }

    @Test
    fun `distinct meanings are not merged`() {
        val input = listOf(
            Meaning("verb", "To move on skis", ""),
            Meaning("noun", "A group of sports played on snow", "")
        )
        assertEquals(2, MeaningQuality.refine(input).size)
    }

    @Test
    fun `arabic alternatives are cut at the first separator`() {
        assertEquals(
            "السفر فوق منحدر على الزلاجات",
            MeaningQuality.cleanArabic("السفر فوق منحدر على الزلاجات؛ السفر على الزلاجات في مكان")
        )
    }

    @Test
    fun `refine never wipes a card that has real meanings`() {
        val input = listOf(Meaning("verb", "To endure without yielding", ""))
        assertEquals(1, MeaningQuality.refine(input).size)
    }
}
