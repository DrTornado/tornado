package com.tornado.vocab.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.tornado.vocab.data.Word

/** واجهة مبسّطة للتحكم بالخدمة من الواجهة الرسومية */
object PlayerController {

    private var controller: MediaController? = null

    fun connect(context: Context, onReady: (MediaController) -> Unit = {}) {
        if (controller != null) { onReady(controller!!); return }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.let(onReady)
        }, MoreExecutors.directExecutor())
    }

    /** يبني قائمة تشغيل من الكلمات — الصوت الرسمي للنطق من القاموس */
    fun playAll(words: List<Word>, startIndex: Int = 0) {
        val items = words.mapNotNull { w ->
            val url = w.audioUS.ifBlank { w.audioUK }
            if (url.isBlank()) null else MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(w.word)
                        .setArtist(w.arabicPron.ifBlank { "Tornado" })
                        .build()
                ).build()
        }
        if (items.isEmpty()) return
        controller?.apply {
            setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
            prepare()
            play()
        }
    }

    fun playOne(word: Word) = playAll(listOf(word))
    fun pause() { controller?.pause() }
    fun resume() { controller?.play() }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun setSpeed(v: Float) { controller?.setPlaybackSpeed(v) }
    fun release() { controller?.release(); controller = null }
}
