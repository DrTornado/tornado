package com.tornado.vocab

import com.tornado.vocab.data.ENGINE_VERSION
import com.tornado.vocab.data.MIN_VERSION_STEP
import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * قواعد اختيار البطاقات للإثراء.
 *
 * كُتبت بعد خلل في تصميمي: ربطتُ الإثراء بإصدار المحرك، فصارت الكلمة المضافة
 * اليوم مستثناة منه للأبد لأنها «حديثة» — حتى لو وُلدت بلا مثال ولا نطق. أي
 * أن النظام كان يعالج نقص الأمس ويسمح بنقص الغد.
 *
 * القاعدة الصحيحة لا تسأل عن عمر البطاقة بل عن فراغها، مع سقف للمحاولات حتى
 * لا تدور الجولة على كلمة لا يملك المصدر ما ينقصها.
 */
class EnrichmentEligibilityTest {

    private fun complete(attempts: Int = 0) = Word(
        id = 1,
        word = "abide",
        meanings = listOf(Meaning("verb", "To endure without yielding", "التحمل")),
        examples = listOf(LangPair("She could not abide the noise", "")),
        synonyms = listOf(LangPair("tolerate", "")),
        audioUS = "https://example.com/abide.mp3",
        cefr = "B2",
        engineVersion = ENGINE_VERSION + attempts
    )

    // تُعيد المنطق نفسه الموجود في LibraryEnricher — الاختبار يحرس القاعدة لا التنفيذ
    private fun gapCount(w: Word): Int {
        var g = 0
        if (w.examples.isEmpty()) g++
        if (w.audioUS.isBlank() && w.audioUK.isBlank()) g++
        if (w.cefr.isBlank() && w.estCefr.isBlank()) g++
        if (w.synonyms.isEmpty()) g++
        if (w.meanings.isEmpty()) g++
        return g
    }

    private fun attempts(w: Word) = (w.engineVersion - ENGINE_VERSION).coerceAtLeast(0)
    private fun stale(w: Word) = w.engineVersion < ENGINE_VERSION
    private fun eligible(w: Word) = (gapCount(w) > 0 || stale(w)) && attempts(w) < 3

    @Test
    fun `a complete card is left alone`() {
        assertEquals(0, gapCount(complete()))
        assertFalse(eligible(complete()))
    }

    @Test
    fun `a brand new card with gaps is still picked up`() {
        // هذا بالضبط ما كان يفلت: بطاقة بالإصدار الحالي لكنها ناقصة
        val fresh = complete().copy(examples = emptyList())
        assertEquals(ENGINE_VERSION, fresh.engineVersion)
        assertTrue(eligible(fresh))
    }

    @Test
    fun `missing pronunciation counts as a gap`() {
        assertTrue(eligible(complete().copy(audioUS = "", audioUK = "")))
    }

    @Test
    fun `missing level counts as a gap`() {
        assertTrue(eligible(complete().copy(cefr = "", estCefr = "")))
    }

    @Test
    fun `several gaps are counted separately`() {
        val bare = complete().copy(
            examples = emptyList(), synonyms = emptyList(),
            audioUS = "", audioUK = "", cefr = "", estCefr = ""
        )
        assertEquals(4, gapCount(bare))
    }

    @Test
    fun `attempts are capped so a hopeless word stops being asked`() {
        val stubborn = complete(attempts = 3).copy(examples = emptyList())
        assertTrue(gapCount(stubborn) > 0)
        assertFalse("ثلاث محاولات تكفي", eligible(stubborn))
    }

    @Test
    fun `a card still under the cap keeps its turn`() {
        assertTrue(eligible(complete(attempts = 2).copy(examples = emptyList())))
    }
}

/**
 * البطاقة الكاملة لكن القديمة تُعاد.
 *
 * الحالة مأخوذة من «articulation»: معنىً وأمثلة ونطق ومستوى — فجواتها صفر،
 * فكانت تنجو من كل تحسين في المحرّك إلى الأبد. والمستخدم رأى الترتيب الخاطئ
 * بعد إصلاحه وقال إن كل الكلمات ما زالت على حالها. وكان محقاً.
 */
class StaleCardRebuildTest {

    private fun card(engine: Int) = Word(
        id = 1, word = "articulation",
        meanings = listOf(Meaning("noun", "A joint at which something bends.", "مفصل")),
        examples = listOf(LangPair("The articulation was clear.", "")),
        synonyms = listOf(LangPair("enunciation", "")),
        audioUS = "https://example.org/a.mp3",
        cefr = "C1",
        engineVersion = engine
    )

    private fun gapCount(w: Word): Int {
        var g = 0
        if (w.examples.isEmpty()) g++
        if (w.audioUS.isBlank() && w.audioUK.isBlank()) g++
        if (w.cefr.isBlank() && w.estCefr.isBlank()) g++
        if (w.synonyms.isEmpty()) g++
        if (w.meanings.isEmpty()) g++
        return g
    }

    private fun attempts(w: Word) = (w.engineVersion - ENGINE_VERSION).coerceAtLeast(0)
    private fun eligible(w: Word) =
        (gapCount(w) > 0 || w.engineVersion < ENGINE_VERSION) && attempts(w) < 3

    @Test
    fun `complete card built by an older engine is rebuilt`() {
        val old = card(ENGINE_VERSION - 1)
        assertEquals("لا فجوات فيها إطلاقاً", 0, gapCount(old))
        assertTrue("ومع ذلك يجب أن تُعاد لأن محرّكها أقدم", eligible(old))
    }

    @Test
    fun `complete card on the current engine is left alone`() {
        val fresh = card(ENGINE_VERSION + 1)
        assertFalse("محدَّثة وكاملة — لمسها إهدار", eligible(fresh))
    }

    @Test
    fun `rebuilding stops after the attempt limit`() {
        val exhausted = card(ENGINE_VERSION + 3)
        assertFalse("ثلاث محاولات تكفي؛ الرابعة دوران بلا نهاية", eligible(exhausted))
    }
}

/**
 * رفع رقم المحرّك يعيد تأهيل ما استُبعد بالمحاولات.
 *
 * الحالة وقعت فعلاً: عطلٌ في المحرّك أفشل كل جلب، فسجّلت كل بطاقة ثلاث
 * محاولات فاشلة وخرجت من الأهلية. ثم أُصلح العطل، فبقيت المكتبة كلها على
 * معانيها الخاطئة — والمستخدم يضغط «مزامنة» ولا يتغيّر شيء.
 */
class VersionStepResetsAttemptsTest {

    private fun attempts(engineVersion: Int, current: Int) =
        (engineVersion - current).coerceAtLeast(0)

    @Test
    fun `a card exhausted under the old engine is eligible again after a bump`() {
        val oldEngine = 10
        val exhausted = oldEngine + 3          // ثلاث محاولات فاشلة تحت المحرّك القديم
        assertEquals("مستنفَدة تماماً", 3, attempts(exhausted, oldEngine))

        val bumped = oldEngine + MIN_VERSION_STEP
        assertEquals("والقفزة تعيدها إلى الصفر", 0, attempts(exhausted, bumped))
        assertTrue("فتصير قديمة ومؤهّلة", exhausted < bumped)
    }

    @Test
    fun `the step always clears the attempt ceiling`() {
        assertTrue(
            "القفزة يجب أن تتجاوز حدّ المحاولات وإلا بقيت بطاقات مقصاة",
            MIN_VERSION_STEP > 3
        )
    }
}
