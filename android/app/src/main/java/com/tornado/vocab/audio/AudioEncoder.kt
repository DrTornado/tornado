package com.tornado.vocab.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * يرمّز السرد إلى AAC داخل حاوية m4a.
 *
 * البطاقة الواحدة تخرج من التوليد بصيغة WAV خام: ثلاثة ميغابايت لعشرين ثانية
 * كلام. عند ألف كلمة تصير المكتبة ثلاثة جيجابايت على الهاتف — ولا يمكن نقلها
 * ولا نسخها احتياطياً ولا مشاركتها بين جهازين.
 *
 * الكلام أحادي القناة يُرمَّز بأربعين كيلوبت في الثانية بلا فرق مسموع، فتنزل
 * البطاقة إلى نحو مئة كيلوبايت — ثلاثون ضعفاً أصغر. والفارق ليس في المساحة
 * وحدها: هذا ما يجعل الصوت المولَّد قابلاً لأن يُرفع مرة ويُنزَّل على أي جهاز
 * بدل أن يُعاد توليده من الصفر في كل مرة.
 *
 * والمرمّز عتادي في أندرويد، فالكلفة الزمنية أجزاء من الثانية للبطاقة.
 */
object AudioEncoder {

    const val EXTENSION = "m4a"

    /** أربعون كيلوبت للكلام الأحادي: حدّ الشفافية عملياً، وما فوقه مساحة بلا فائدة */
    private const val BIT_RATE = 40_000
    private const val TIMEOUT_US = 10_000L

    /**
     * @return true إن نجح الترميز وكُتب ملف صالح.
     * الفشل ليس كارثة: المستدعي يُبقي الصيغة الخام ويعمل كما كان.
     */
    fun encodeToAac(samples: ShortArray, target: File): Boolean {
        if (samples.isEmpty()) return false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, WavUtils.SAMPLE_RATE, 1
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            target.parentFile?.mkdirs()
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var sampleIndex = 0
            var inputDone = false
            var outputDone = false
            var presentationUs = 0L
            var guard = 0

            while (!outputDone && guard++ < 200_000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer: ByteBuffer = codec.getInputBuffer(inIndex) ?: continue
                        buffer.clear()
                        val capacityShorts = buffer.capacity() / 2
                        val count = minOf(capacityShorts, samples.size - sampleIndex)
                        if (count <= 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val shorts = buffer.asShortBuffer()
                            shorts.put(samples, sampleIndex, count)
                            codec.queueInputBuffer(inIndex, 0, count * 2, presentationUs, 0)
                            sampleIndex += count
                            presentationUs += count * 1_000_000L / WavUtils.SAMPLE_RATE
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return false
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)
                        // إطار الإعداد ليس صوتاً بل وصف للمسار، والحاوية تكتبه بنفسها
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (out != null && info.size > 0 && !isConfig && muxerStarted) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, out, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            muxerStarted && target.length() > 256
        } catch (e: Exception) {
            runCatching { target.delete() }
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
