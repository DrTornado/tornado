package com.tornado.vocab.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * أدوات WAV منخفضة المستوى.
 *
 * محرّك النطق في أندرويد يُخرج ملف WAV لكل مقطع، وقد تختلف صيغة المخرجات بين
 * الصوت الإنجليزي والصوت العربي (تردد أو عدد قنوات مختلف). لذلك نحوّل كل مقطع
 * إلى صيغة موحّدة قبل الدمج: أحادي القناة، ٤٤١٠٠ هرتز، ١٦ بت.
 * بدون هذا التوحيد ينتج الدمج ضجيجاً أو تسارعاً في الصوت العربي.
 *
 * التردد القياسي هو ٤٤١٠٠ لأنه تردد الصوت السحابي نفسه — وهو المصدر الأساسي.
 * خفضه إلى ٢٢٠٥٠ كان يعني إعادة تشكيل كل مقطع مدفوع بمرشّح بسيط، وهذا تحديداً
 * مصدر الخشخشة التي تُسمع وتذهب: الطبقات الدنيا فقط تُرفع الآن، ورفع التردد
 * لا يولّد تشوّهاً كما يفعل خفضه.
 */
object WavUtils {

    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 1
    const val BITS = 16
    private const val HEADER_SIZE = 44

    data class Pcm(val samples: ShortArray, val sampleRate: Int) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private class Fmt(
        val audioFormat: Int, val channels: Int, val sampleRate: Int, val bits: Int,
        val dataOffset: Int, val dataLength: Int
    )

    /** يقرأ ملف WAV ويعيده عيّنات أحادية القناة بالتردد القياسي */
    fun decodeToCanonical(file: File): ShortArray? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeToCanonical(bytes)
    }

    fun decodeToCanonical(bytes: ByteArray): ShortArray? {
        val fmt = parseHeader(bytes) ?: return null
        val mono = readMono(bytes, fmt) ?: return null
        return if (fmt.sampleRate == SAMPLE_RATE) mono else resample(mono, fmt.sampleRate, SAMPLE_RATE)
    }

    /**
     * يمشي على أجزاء الملف بدل افتراض ترويسة ٤٤ بايت ثابتة — بعض المحركات
     * تُدرج أجزاء إضافية (LIST/fact) قبل البيانات، وتجاهلها يُنتج طقطقة في البداية.
     */
    private fun parseHeader(b: ByteArray): Fmt? {
        if (b.size < 12) return null
        if (str(b, 0, 4) != "RIFF" || str(b, 8, 4) != "WAVE") return null
        var pos = 12
        var audioFormat = 1; var channels = 1; var sampleRate = SAMPLE_RATE; var bits = 16
        var dataOffset = -1; var dataLength = 0
        while (pos + 8 <= b.size) {
            val id = str(b, pos, 4)
            val size = le32(b, pos + 4)
            val body = pos + 8
            if (size < 0 || body > b.size) break
            when (id) {
                "fmt " -> if (body + 16 <= b.size) {
                    audioFormat = le16(b, body)
                    channels = le16(b, body + 2).coerceAtLeast(1)
                    sampleRate = le32(b, body + 4).coerceAtLeast(8000)
                    bits = le16(b, body + 14)
                }
                "data" -> { dataOffset = body; dataLength = minOf(size, b.size - body) }
            }
            if (dataOffset >= 0 && id == "data") break
            pos = body + size + (size and 1) // الأجزاء محاذاة زوجية
        }
        if (dataOffset < 0 || dataLength <= 0) return null
        return Fmt(audioFormat, channels, sampleRate, bits, dataOffset, dataLength)
    }

    private fun readMono(b: ByteArray, f: Fmt): ShortArray? {
        val bytesPerSample = when (f.bits) {
            8 -> 1; 16 -> 2; 24 -> 3; 32 -> 4
            else -> return null
        }
        val frameSize = bytesPerSample * f.channels
        if (frameSize <= 0) return null
        val frames = f.dataLength / frameSize
        if (frames <= 0) return null
        val out = ShortArray(frames)
        var p = f.dataOffset
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until f.channels) {
                val v = when (f.bits) {
                    8 -> ((b[p].toInt() and 0xFF) - 128) shl 8
                    16 -> ((b[p + 1].toInt() shl 8) or (b[p].toInt() and 0xFF))
                    24 -> ((b[p + 2].toInt() shl 8) or (b[p + 1].toInt() and 0xFF))
                    else -> if (f.audioFormat == 3) {
                        val bits = le32(b, p)
                        (java.lang.Float.intBitsToFloat(bits) * 32767f).toInt().coerceIn(-32768, 32767)
                    } else ((b[p + 3].toInt() shl 8) or (b[p + 2].toInt() and 0xFF))
                }
                acc += v
                p += bytesPerSample
            }
            out[i] = (acc / f.channels).coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * إعادة تشكيل مع ترشيح مضاد للتشويش.
     *
     * الاستيفاء الخطي وحده كان مصدر خشخشة حقيقية: تسجيلات ويكيميديا تأتي غالباً
     * بـ٤٤١٠٠ أو ٤٨٠٠٠ هرتز، وخفضها إلى ٢٢٠٥٠ بلا ترشيح يطوي كل تردد فوق نصف
     * التردد الجديد إلى داخل النطاق المسموع — وهو بالضبط الصفير المعدني الذي يُسمع.
     * مرشّح متوسط متحرك بسيط قبل الخفض يزيل معظمه بكلفة حسابية ضئيلة.
     */
    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input

        val source = if (to < from) lowPass(input, from, to) else input
        val ratio = to.toDouble() / from
        val outLen = (source.size * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = i / ratio
            val i0 = src.toInt()
            val i1 = (i0 + 1).coerceAtMost(source.size - 1)
            val frac = src - i0
            val v = source[i0] * (1 - frac) + source[i1] * frac
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * ترشيح ما قبل الخفض.
     *
     * العرض محسوب من تردد القطع لا من نسبة الخفض: نريد أول صفر للمرشّح عند
     * نصف التردد الجديد بالضبط. وتمريرة واحدة لا تكفي — انحدارها ضعيف ويترك
     * طاقة مسموعة فوق حدّ نايكويست تنطوي إلى داخل النطاق. تمريرتان متتاليتان
     * تضاعفان الانحدار وتخفضان التشويش إلى ما دون العتبة المسموعة، بكلفة
     * خطية تبقى مقبولة لآلاف الملفات.
     */
    private fun lowPass(input: ShortArray, from: Int, to: Int): ShortArray {
        val cutoff = to / 2
        if (cutoff <= 0) return input
        val width = (from / cutoff).coerceIn(2, 32)
        if (input.size < width) return input
        return movingAverage(movingAverage(input, width), width)
    }

    private fun movingAverage(input: ShortArray, width: Int): ShortArray {
        val out = ShortArray(input.size)
        var sum = 0L
        for (i in input.indices) {
            sum += input[i]
            if (i >= width) sum -= input[i - width]
            val n = if (i < width) i + 1 else width
            out[i] = (sum / n).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * تلاشٍ قصير عند طرفي المقطع.
     *
     * دمج مقطعين لا يبدأ ولا ينتهي عند الصفر يُحدث قفزة مفاجئة في السعة،
     * وهي طقطقة مسموعة عند كل حدّ بين مقطعين — وفي بطاقة فيها أربعون مقطعاً
     * تصير أربعين طقطقة. خمس ميلي ثانية كافية لإزالتها بلا أثر على النطق.
     */
    fun applyEdgeFade(samples: ShortArray, fadeMs: Int = 5): ShortArray {
        if (samples.isEmpty()) return samples
        val fade = ((SAMPLE_RATE.toLong() * fadeMs) / 1000).toInt()
            .coerceAtMost(samples.size / 2)
        if (fade <= 1) return samples
        for (i in 0 until fade) {
            val gain = i.toFloat() / fade
            samples[i] = (samples[i] * gain).toInt().toShort()
            val j = samples.size - 1 - i
            samples[j] = (samples[j] * gain).toInt().toShort()
        }
        return samples
    }

    /** ذروة مستهدفة تترك هامشاً تحت الحد الأقصى — تمنع القصّ نهائياً */
    private const val TARGET_PEAK = 26_000

    /**
     * حماية من القصّ فقط — بلا أي تضخيم.
     *
     * المحاولة السابقة كانت ترفع المقاطع الهادئة حتى أربعة أضعاف لتوحيد الشدة،
     * وهذا بالضبط ما أنتج الخشخشة: رفع قسري يضخّم ضجيج التسجيل ويدفع الذروات
     * نحو السقف. الصوت النقي يأتي من عدم لمسه لا من معالجته.
     *
     * نتدخّل في حالة واحدة: مقطع يلامس سقف المدى فعلاً — نخفضه قليلاً ليبتعد
     * عن السقف. أما ما دون ذلك فيمرّ كما هو، بشدته الأصلية بلا زيادة ولا ضغط.
     */
    fun normalize(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val peak = peakOf(samples)
        // لا تضخيم إطلاقاً: نخفض فقط إن كان المقطع يلامس السقف
        if (peak < 32_600) return samples
        val gain = TARGET_PEAK.toFloat() / peak
        for (i in samples.indices) {
            samples[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        return samples
    }

    /** أعلى سعة في المقطع — يخدم الاختبارات والتشخيص */
    fun peakOf(samples: ShortArray): Int {
        var peak = 0
        for (s in samples) {
            val a = if (s.toInt() == -32768) 32767 else kotlin.math.abs(s.toInt())
            if (a > peak) peak = a
        }
        return peak
    }

    /** أكبر قفزة بين عيّنتين متتاليتين — مقياس موضوعي للطقطقة */
    fun maxSampleJump(samples: ShortArray): Int {
        var max = 0
        for (i in 1 until samples.size) {
            val d = kotlin.math.abs(samples[i] - samples[i - 1])
            if (d > max) max = d
        }
        return max
    }

    /** إعادة تشكيل عيّنات جاهزة — يستخدمها محرك Piper الذي يُخرج عيّنات لا ملفاً */
    fun resampleTo(input: ShortArray, from: Int, to: Int): ShortArray = resample(input, from, to)

    fun silence(ms: Int): ShortArray =
        ShortArray((SAMPLE_RATE.toLong() * ms / 1000).toInt().coerceAtLeast(0))

    /** مدة مقطع بالميلي ثانية */
    fun durationMs(samples: Int): Long = samples.toLong() * 1000 / SAMPLE_RATE

    /**
     * يكتب ملف WAV واحداً من عدة كتل عيّنات.
     * الكتابة تدفقية: لا نحتفظ بالملف كاملاً في الذاكرة، فبطاقة طويلة لا تسبب OOM.
     */
    fun writeWav(target: File, chunks: List<ShortArray>) {
        val totalSamples = chunks.sumOf { it.size }
        val dataBytes = totalSamples * 2
        target.parentFile?.mkdirs()
        target.outputStream().buffered(1 shl 16).use { out ->
            writeHeader(out, dataBytes)
            val buf = ByteBuffer.allocate(1 shl 15).order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in chunks) {
                var i = 0
                while (i < chunk.size) {
                    buf.clear()
                    val n = minOf(buf.capacity() / 2, chunk.size - i)
                    for (k in 0 until n) buf.putShort(chunk[i + k])
                    out.write(buf.array(), 0, n * 2)
                    i += n
                }
            }
        }
    }

    private fun writeHeader(out: OutputStream, dataBytes: Int) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort((CHANNELS * BITS / 8).toShort())
        header.putShort(BITS.toShort())
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        out.write(header.array())
    }

    private fun str(b: ByteArray, off: Int, len: Int): String =
        if (off + len > b.size) "" else String(b, off, len, Charsets.US_ASCII)

    private fun le16(b: ByteArray, off: Int): Int =
        if (off + 2 > b.size) 0 else ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)

    private fun le32(b: ByteArray, off: Int): Int =
        if (off + 4 > b.size) 0 else
            ((b[off + 3].toInt() and 0xFF) shl 24) or ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)
}
