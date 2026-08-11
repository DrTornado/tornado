package com.tornado.vocab

import com.tornado.vocab.ui.CardQueue
import com.tornado.vocab.ui.waited
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * العطل الصامت — الحالة التي لا يشكو منها أحد لأن أحداً لا يراها.
 *
 * البطاقة تصل في دقيقتين. فإن انتهت صلاحية الرمز أو سقط المسار أو نفدت
 * الدقائق، بقي السطر يقول «كلمة تنتظر بطاقة» إلى الأبد بنفس اللهجة الهادئة —
 * فيظنّ صاحبها الكتابةَ بطيئة، ولا يعرف أن شيئاً انكسر إلا بإيميلٍ قد لا
 * يقرؤه. فهذه الاختبارات تحرس انقلاب اللهجة بعد الساعة.
 */
class CardQueueTest {

    private val HOUR = 3_600_000L

    @Test
    fun `الطابور الفارغ لا يتعثّر مهما طال`() {
        assertFalse(CardQueue(emptyList(), 10 * HOUR, synced = true).stalled)
    }

    @Test
    fun `انتظارٌ قصير ليس عطلاً`() {
        assertFalse(CardQueue(listOf("abide"), 5 * 60_000L, synced = true).stalled)
        assertFalse(CardQueue(listOf("abide"), 59 * 60_000L, synced = true).stalled)
    }

    @Test
    fun `ما تجاوز الساعة عطلٌ يُقال`() {
        assertFalse(CardQueue(listOf("abide"), HOUR, synced = true).stalled)
        assertTrue(CardQueue(listOf("abide"), HOUR + 1, synced = true).stalled)
        assertTrue(CardQueue(listOf("abide"), 3 * HOUR, synced = true).stalled)
    }

    /*
     * نسخةٌ بلا مزامنة مكتبتُها كلّها بلا بطاقات، وأعمارُ كلماتها أسابيع —
     * فلو حكم العمرُ وحده لصرخ الإنذار على مكتبة البداية. والإنذار الكاذب
     * يُعلّم صاحبَه تجاهلَه، فيضيع حين يصدق.
     */
    @Test
    fun `بلا مزامنة لا إنذار مهما طال العمر`() {
        assertFalse(CardQueue(listOf("abide", "tenacity"), 30 * 24 * HOUR).stalled)
        assertFalse(CardQueue(listOf("abide"), 500 * HOUR, synced = false).stalled)
    }

    @Test
    fun `الطابور الافتراضي ساكن`() {
        assertFalse(CardQueue().stalled)
        assertTrue(CardQueue().words.isEmpty())
    }

    @Test
    fun `المدّة تُقال بأخشن وحدةٍ تصفها`() {
        assertEquals("1h", waited(HOUR + 60_000L))
        assertEquals("3h", waited(3 * HOUR))
        assertEquals("23h", waited(23 * HOUR))
        assertEquals("1d", waited(24 * HOUR))
        assertEquals("2d", waited(50 * HOUR))
    }
}
