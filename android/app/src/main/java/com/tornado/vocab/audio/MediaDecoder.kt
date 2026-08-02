package com.tornado.vocab.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * يفكّ ترميز أي ملف صوتي يدعمه النظام إلى عيّنات PCM موحّدة.
 *
 * تسجيلات ويكيميديا تأتي بصيغ مختلطة: ogg/vorbis غالباً، وflac وmp3 أحياناً.
 * MediaCodec يغطيها كلها بلا مكتبة خارجية، وهو مسرَّع عتادياً على الجهاز.
 */
object MediaDecoder {

    fun decodeToPcm(file: File, targetSampleRate: Int): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sourceRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(targetSampleRate)
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1)
                .coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ArrayList<ShortArray>()
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            var guard = 0

            while (!sawOutputEnd && guard++ < 100_000) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            out += readChunk(buffer, channels)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }

            val total = out.sumOf { it.size }
            if (total == 0) return null
            val merged = ShortArray(total)
            var offset = 0
            for (chunk in out) { chunk.copyInto(merged, offset); offset += chunk.size }

            return if (sourceRate == targetSampleRate) merged
            else WavUtils.resampleTo(merged, sourceRate, targetSampleRate)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** يخلط القنوات في قناة واحدة — السرد أحادي القناة دائماً */
    private fun readChunk(buffer: ByteBuffer, channels: Int): ShortArray {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channels
        if (frames <= 0) return ShortArray(0)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += shorts.get()
            out[i] = (acc / channels).coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
