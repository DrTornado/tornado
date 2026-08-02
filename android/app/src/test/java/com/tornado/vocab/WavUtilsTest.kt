package com.tornado.vocab

import com.tornado.vocab.audio.WavUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات أنبوب الصوت — تعمل على الحاسوب بلا جهاز ولا محاكي.
 * تغطّي الجزء الذي يكسر الصوت فعلياً حين يُخطئ: قراءة الترويسة، توحيد
 * الصيغة، والدمج.
 */
class WavUtilsTest {

    @get:Rule val temp = TemporaryFolder()

    private fun tone(samples: Int, value: Short = 8000): ShortArray =
        ShortArray(samples) { if (it % 2 == 0) value else (-value).toShort() }

    @Test
    fun `writes and reads back a canonical wav`() {
        val file = File(temp.newFolder(), "a.wav")
        val pcm = tone(WavUtils.SAMPLE_RATE) // ثانية واحدة
        WavUtils.writeWav(file, listOf(pcm))

        assertTrue("file must exist", file.exists())
        // ٤٤ بايت ترويسة + عيّنتان لكل عيّنة
        assertEquals(44L + pcm.size * 2, file.length())

        val decoded = WavUtils.decodeToCanonical(file)
        assertNotNull(decoded)
        assertEquals(pcm.size, decoded!!.size)
        assertEquals(pcm[0], decoded[0])
        assertEquals(pcm[1], decoded[1])
    }

    @Test
    fun `silence length matches requested duration`() {
        val half = WavUtils.silence(500)
        assertEquals(WavUtils.SAMPLE_RATE / 2, half.size)
        assertTrue(half.all { it.toInt() == 0 })
    }

    @Test
    fun `duration is reported correctly`() {
        assertEquals(1000L, WavUtils.durationMs(WavUtils.SAMPLE_RATE))
        assertEquals(500L, WavUtils.durationMs(WavUtils.SAMPLE_RATE / 2))
    }

    @Test
    fun `concatenation preserves total length`() {
        val file = File(temp.newFolder(), "joined.wav")
        val a = tone(1000)
        val gap = WavUtils.silence(200)
        val b = tone(1500)
        WavUtils.writeWav(file, listOf(a, gap, b))

        val decoded = WavUtils.decodeToCanonical(file)
        assertNotNull(decoded)
        assertEquals(a.size + gap.size + b.size, decoded!!.size)
    }

    @Test
    fun `resampling scales sample count proportionally`() {
        val source = tone(16_000)
        val out = WavUtils.resampleTo(source, 16_000, 22_050)
        // نسمح بانحراف عيّنة واحدة من التقريب
        assertTrue("expected ~22050, got ${out.size}", kotlin.math.abs(out.size - 22_050) <= 2)
    }

    @Test
    fun `resampling is a no-op at the same rate`() {
        val source = tone(500)
        val out = WavUtils.resampleTo(source, 22_050, 22_050)
        assertEquals(source.size, out.size)
    }

    @Test
    fun `garbage input decodes to null instead of crashing`() {
        val file = File(temp.newFolder(), "bad.wav")
        file.writeBytes(ByteArray(200) { 0x7F })
        assertEquals(null, WavUtils.decodeToCanonical(file))
    }
}
