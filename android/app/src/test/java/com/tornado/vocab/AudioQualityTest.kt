package com.tornado.vocab

import com.tornado.vocab.audio.WavUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * اختبارات تعزل مصدر الخشخشة في خط الصوت.
 *
 * كل اختبار يقيس شيئاً واحداً بالأرقام بدل الحكم بالأذن: قفزات السعة عند
 * حدود الدمج، والتشويش الناتج عن خفض معدل العينة.
 */
class AudioQualityTest {

    /** موجة جيبية بسعة ثابتة — تنتهي عند قيمة عالية عمداً لتكشف الطقطقة */
    private fun tone(freq: Double, samples: Int, rate: Int = WavUtils.SAMPLE_RATE): ShortArray =
        ShortArray(samples) { i ->
            (sin(2 * PI * freq * i / rate) * 12000).toInt().toShort()
        }

    @Test
    fun `joining segments without fade creates an audible click`() {
        // مقطع ينتهي عند ذروة الموجة، يليه صمت: قفزة حادة = طقطقة
        val a = tone(440.0, 1000).copyOf().also { arr ->
            for (i in arr.indices) arr[i] = 12000
        }
        val silence = WavUtils.silence(50)
        val joined = a + silence

        val jump = WavUtils.maxSampleJump(joined)
        assertTrue(
            "expected a large discontinuity to prove the problem exists, got $jump",
            jump > 8000
        )
    }

    @Test
    fun `edge fade removes the click at segment boundaries`() {
        val a = ShortArray(1000) { 12000 }
        WavUtils.applyEdgeFade(a)
        val joined = a + WavUtils.silence(50)

        val jump = WavUtils.maxSampleJump(joined)
        assertTrue("fade should keep jumps small, got $jump", jump < 500)
    }

    @Test
    fun `fade keeps the middle of the segment untouched`() {
        val a = ShortArray(WavUtils.SAMPLE_RATE) { 10000 }
        WavUtils.applyEdgeFade(a, fadeMs = 5)
        // منتصف المقطع يجب أن يبقى بكامل السعة — التلاشي عند الأطراف فقط
        assertTrue(a[a.size / 2].toInt() == 10000)
    }

    /*
     * هدفان مختلفان لا يجوز خلطهما.
     *
     * هذان الاختباران يفحصان المرشّح المضاد للطيّ، لا التردد القياسي للتطبيق.
     * ربطهما بـSAMPLE_RATE جعلهما يفقدان معناهما لحظة صار التردد القياسي ٤٤١٠٠:
     * تحوّل الخفض إلى عملية فارغة ونجح الاختبار أو فشل لسبب لا علاقة له بالمرشّح.
     * الهدف هنا مكتوب صراحةً ليبقى الاختبار يقيس ما وُضع لقياسه.
     */
    private val downsampleTarget = 22_050

    @Test
    fun `downsampling without filtering aliases high frequencies`() {
        // ٩ كيلوهرتز فوق نايكويست الجديد (١١٠٢٥) بعد الخفض للنصف
        val high = tone(9000.0, 44_100, rate = 44_100)
        val out = WavUtils.resampleTo(high, 44_100, downsampleTarget)

        // المرشّح يجب أن يخفض طاقة هذا التردد بوضوح بدل طيّه داخل النطاق
        val energy = out.sumOf { abs(it.toInt()).toLong() } / out.size.toDouble()
        assertTrue(
            "aliased energy should be attenuated, average amplitude was $energy",
            energy < 6000
        )
    }

    @Test
    fun `downsampling preserves speech-range frequencies`() {
        // ٥٠٠ هرتز في قلب نطاق الكلام — يجب أن ينجو من الترشيح
        val speech = tone(500.0, 44_100, rate = 44_100)
        val out = WavUtils.resampleTo(speech, 44_100, downsampleTarget)

        val energy = out.sumOf { abs(it.toInt()).toLong() } / out.size.toDouble()
        assertTrue("speech range must survive, average amplitude was $energy", energy > 3000)
    }

    @Test
    fun `cloud audio passes through untouched at the canonical rate`() {
        /*
         * الصوت السحابي يصل بـ٤٤١٠٠ وهو تردد التطبيق القياسي، فيجب ألا يمرّ
         * بإعادة تشكيل إطلاقاً. أي تغيير في العيّنات هنا يعني أننا نعالج صوتاً
         * مدفوعاً بلا داعٍ — وهذا بالضبط ما كان ينتج التلوّن المعدني.
         */
        val cloud = tone(440.0, 44_100, rate = 44_100)
        val out = WavUtils.resampleTo(cloud, 44_100, WavUtils.SAMPLE_RATE)
        assertTrue("canonical rate must be the cloud rate", WavUtils.SAMPLE_RATE == 44_100)
        assertTrue("cloud audio must not be resampled", out === cloud)
    }

    @Test
    fun `normalisation removes clipping at the ceiling`() {
        // مقطع مقصوص عند السقف تماماً — هذا ما كان يُسمع تشوّهاً
        val clipped = ShortArray(2000) { i -> if (i % 2 == 0) 32767 else -32768 }
        assertTrue(WavUtils.peakOf(clipped) >= 32767)

        WavUtils.normalize(clipped)
        val peak = WavUtils.peakOf(clipped)
        assertTrue("peak must sit below the ceiling, was $peak", peak <= 27_000)
    }

    @Test
    fun `quiet audio is never amplified`() {
        // الرفع القسري هو ما كان يُنتج الخشخشة — المقطع الهادئ يمرّ كما هو
        val quiet = ShortArray(2000) { 3_000 }
        val before = WavUtils.peakOf(quiet)
        WavUtils.normalize(quiet)
        assertEquals(before, WavUtils.peakOf(quiet))
    }

    @Test
    fun `normal audio passes through untouched`() {
        val normal = ShortArray(2000) { i -> (sin(2 * PI * 440 * i / 22050.0) * 20000).toInt().toShort() }
        val copy = normal.copyOf()
        WavUtils.normalize(normal)
        assertTrue("audio below the ceiling must not be altered", normal.contentEquals(copy))
    }

    @Test
    fun `resampling still produces the right length`() {
        val src = tone(440.0, 44_100, rate = 44_100)
        val out = WavUtils.resampleTo(src, 44_100, WavUtils.SAMPLE_RATE)
        assertTrue(
            "expected ~22050 samples, got ${out.size}",
            abs(out.size - WavUtils.SAMPLE_RATE) <= 4
        )
    }
}
