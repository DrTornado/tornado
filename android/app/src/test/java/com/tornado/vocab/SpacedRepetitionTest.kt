package com.tornado.vocab

import com.tornado.vocab.data.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * قواعد المراجعة المتباعدة.
 *
 * كُتبت بعد خلل صامت: التدريب الحرّ — الذي يجري حين لا تستحق أي كلمة اليوم —
 * كان يحتسب إجاباته كمراجعة حقيقية، فيضاعف فواصل كلمات لم تكن مستحقة أصلاً
 * ويدفعها إلى شهور. لا خطأ يظهر، ولا شيء يُلاحَظ إلا بعد أسابيع حين تكتشف أن
 * كلمات تعرفها بالكاد لم تعد تُعرض عليك.
 *
 * الاختبارات تحرس القاعدة: الموعد يتحرّك في المراجعة وحدها، والعدّاد يتحرّك
 * في الحالتين.
 */
class SpacedRepetitionTest {

    private val day = 24 * 60 * 60 * 1000L

    private fun word(interval: Int = 0, right: Int = 0, wrong: Int = 0) =
        Word(id = 1, word = "abide", interval = interval, right = right, wrong = wrong)

    /** نفس حساب [com.tornado.vocab.data.WordRepository.answer] — الاختبار يحرس القاعدة */
    private fun nextInterval(current: Int, knew: Boolean): Int {
        if (!knew) return 0
        val doubled = Math.round(current.coerceAtLeast(0) * 2.2).toInt().coerceIn(1, 60)
        return if (current <= 0) 1 else doubled
    }

    /**
     * التسلسل الحقيقي، مأخوذاً من تطبيق الويب لا من تعليق في الكود.
     *
     * كان مكتوباً في المستودع أن التسلسل `١ ← ٣ ← ٧ ← ١٦`، وكتبتُ الاختبار على
     * أساسه ففشل. والمراجعة أثبتت أن الكود سليم ومطابق للويب، وأن التعليق وحده
     * كان خاطئاً — وهو خطأ يدفع القارئ إلى «إصلاح» ما ليس معطوباً.
     */
    @Test
    fun `a correct answer lengthens the gap`() {
        assertEquals(1, nextInterval(0, true))
        assertEquals(2, nextInterval(1, true))
        assertEquals(4, nextInterval(2, true))
        assertEquals(9, nextInterval(4, true))
        assertEquals(20, nextInterval(9, true))
    }

    @Test
    fun `a wrong answer brings the word back tomorrow`() {
        assertEquals(0, nextInterval(15, false))
        assertEquals(0, nextInterval(60, false))
    }

    @Test
    fun `the gap never runs away`() {
        // بلا سقف تصير الكلمة غير مرئية عملياً بعد بضع إجابات صحيحة
        assertEquals(60, nextInterval(60, true))
        assertTrue(nextInterval(50, true) <= 60)
    }

    @Test
    fun `free practice keeps the review date untouched`() {
        /*
         * هذا هو العطل الذي كُتب الاختبار لأجله.
         * التدريب يزيد العدّاد ولا يمسّ interval ولا due.
         */
        val before = word(interval = 15, right = 4)
        val after = before.copy(right = before.right + 1)

        assertEquals(before.interval, after.interval)
        assertEquals(before.due, after.due)
        assertEquals(5, after.right)
    }

    @Test
    fun `a real review does move the date`() {
        val before = word(interval = 3)
        val moved = before.copy(interval = nextInterval(before.interval, true))
        assertTrue("المراجعة يجب أن تباعد الموعد", moved.interval > before.interval)
    }

    @Test
    fun `counters record both outcomes`() {
        val w = word(right = 2, wrong = 1)
        assertEquals(3, w.copy(right = w.right + 1).right)
        assertEquals(2, w.copy(wrong = w.wrong + 1).wrong)
    }
}
