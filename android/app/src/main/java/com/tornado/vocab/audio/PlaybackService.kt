package com.tornado.vocab.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * خدمة تشغيل أمامية حقيقية.
 * هذه هي النقطة التي تحل مشكلة انقطاع الصوت جذرياً: النظام نفسه يشغّل الصوت
 * عبر ExoPlayer داخل خدمة أمامية، فيستمر مع إطفاء الشاشة بلا حدود زمنية،
 * ويظهر في شاشة القفل والإشعارات، ويستجيب لأزرار البلوتوث والسماعات تلقائياً.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val player = ExoPlayer.Builder(this)
            // إدارة تركيز الصوت: يخفض عند الإشعارات ويستأنف بعدها
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            // إيقاف تلقائي عند نزع السماعة (سلوك متوقّع من مشغّلات الصوت)
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }
}
