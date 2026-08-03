package com.tornado.vocab.audio

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tornado.vocab.MainActivity
import com.tornado.vocab.R
import com.tornado.vocab.data.ListRepeat
import com.tornado.vocab.data.SettingsStore
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.WordRepository
import com.tornado.vocab.data.NoteChunker
import com.tornado.vocab.data.PlayItem
import com.tornado.vocab.data.RowKind
import com.tornado.vocab.data.WordRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * خدمة التشغيل الأمامية.
 *
 * مبدآن يحكمان التصميم:
 *  ١ — المشغّل لا يتوقف داخل الجلسة. قائمة ExoPlayer حقيقية تُبنى تدريجياً،
 *      والتشغيل لا يبدأ قبل جهوز بطاقتين، ونافذة متدحرجة تُبقي المولّد متقدّماً.
 *      كل توقّف يعني نزول الخدمة من الحالة الأمامية ثم قتلها بالخلفية.
 *  ٢ — ما لا يُنطق بصوت بشري لا يُنطق أصلاً (افتراضياً). البطاقة بلا تسجيل
 *      تُتخطّى وتُعدّ، ويُعرض عددها للمستخدم بدل تزييفها بصوت مولّد.
 */
class PlaybackService : MediaSessionService() {

    private companion object {
        const val HEAD_START = 2
        const val WINDOW_AHEAD = 5
        const val SEEK_STEP_MS = 10_000L
    }

    private lateinit var player: ExoPlayer
    private lateinit var pronPlayer: ExoPlayer
    private var mediaSession: MediaSession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var tts: TtsSynthesizer
    private lateinit var humanAudio: HumanAudioRepository
    private lateinit var kokoro: KokoroEngine
    private lateinit var voices: VoiceChain
    private lateinit var narration: NarrationRepository
    private lateinit var settings: SettingsStore
    private lateinit var repository: WordRepository
    private lateinit var notes: com.tornado.vocab.data.NoteRepository
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * الجلسة المخطّطة بترتيب التشغيل — صفوف خفيفة لا بطاقات كاملة.
     * خمسة آلاف صف تكلّف بضع مئات الكيلوبايت؛ خمسة آلاف بطاقة مفكوكة تكلّف
     * عشرات الميجابايت وتُسقط التطبيق. البطاقة تُقرأ عند بناء صوتها فقط.
     */
    private var session: List<PlayItem> = emptyList()

    /**
     * الكلمات الموجودة فعلاً في قائمة المشغّل — موازية تماماً لعناصر ExoPlayer.
     * الفصل بينها وبين [session] ضروري: البطاقة المتخطّاة تبقى في الخطة ولا
     * تدخل المشغّل، وخلط الاثنين كان يفسد كل حسابات الفهارس.
     */
    private val rendered = mutableListOf<PlayItem>()
    private val renderedSources = mutableListOf<VoiceSource>()

    /** حدود جمل كل عنصر — تُبقي النص المعروض مطابقاً لما يُنطق الآن */
    private val renderedLines = mutableListOf<List<NarrationLine>>()

    /** موضع كل عنصر مشغَّل داخل الخطة — يربط فهرس ExoPlayer برقم الكلمة الحقيقي */
    private val renderedToSession = mutableListOf<Int>()
    private var skipped = 0
    private var planCursor = 0

    private var scopeMode = PlayScope.LIST
    private var loopsLeft = 0
    private var renderJob: Job? = null
    private var tickerJob: Job? = null
    private var sleepJob: Job? = null
    private var prepNotificationActive = false
    private var waitingForRender = false

    private var speed = 1.0f
    private var detailed = true
    private var shuffle = false
    private var wordRepeat = 1
    private var listRepeat = 1
    private var speakArabic = true
    private var humanOnly = true
    private var voiceTag = ""
    private var sleepMinutes = 0
    private var sleepDeadline = 0L

    // ===== دورة حياة =====

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        repository = WordRepository(this)
        notes = com.tornado.vocab.data.NoteRepository(this)
        tts = TtsSynthesizer(this)
        humanAudio = HumanAudioRepository(this)
        kokoro = (application as com.tornado.vocab.TornadoApp).kokoro
        voices = VoiceChain(humanAudio, tts, kokoro)
        narration = NarrationRepository(this, voices)
        PlaybackReliability.ensureChannels(this)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        pronPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, false)
            .build()

        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, QueueAwarePlayer(player))
            .setSessionActivity(sessionActivity)
            .build()
            /*
             * التسجيل الصريح للجلسة.
             *
             * بناء الجلسة لا يكفي: خدمة الوسائط لا تتبنّى جلسة إلا إذا أُضيفت
             * إليها أو اتصل بها متحكّم خارجي. وواجهتنا تخاطب الخدمة عبر جسر
             * داخلي لا عبر متحكّم، فبقيت الجلسة مجهولة عند النظام — لا إشعار
             * مشغّل، ولا تحكّم من شاشة القفل، ولا استجابة لأزرار السماعة،
             * ولا رفع للخدمة إلى المقدّمة.
             *
             * كل ما بُني من مشغّل حقيقي كان محجوباً خلف هذا السطر الغائب.
             */
            .also { addSession(it) }

        PlaybackBus.attach(commands)
        loadSettings()
        startTicker()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // الصوت يتوقف أولاً: خدمة تموت ومشغّلها يعمل تترك صوتاً بلا صاحب
        runCatching { player.stop() }
        runCatching { pronPlayer.stop() }
        PlaybackBus.detach()
        tickerJob?.cancel(); renderJob?.cancel(); sleepJob?.cancel()
        releaseWakeLock(); clearPrepNotification()
        mediaSession?.release(); mediaSession = null
        player.removeListener(playerListener)
        player.release(); pronPlayer.release()
        tts.shutdown()
        runCatching { kokoro.release() }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * نراقب الإعدادات باستمرار لا مرة واحدة.
     *
     * القراءة المفردة عند إنشاء الخدمة كانت تعني أن أي إعداد يغيّره المستخدم
     * لاحقاً لا يصل أبداً — وأخطرها مفتاح الصوت السحابي: يُحفظ في الإعدادات،
     * ويعمل في شاشة الاختبار، ولا تعرف عنه الخدمة شيئاً فتظل تولّد بالمحرك
     * القديم. المستخدم يسمع صوتاً رديئاً ولا يفهم لماذا.
     */
    private fun loadSettings() = scope.launch {
        settings.audio.collect { a -> applyAudioSettings(a) }
    }

    /**
     * بوابة تمنع أخطر سباق في المنتج.
     *
     * قراءة الإعدادات من القرص غير فورية، وحلقة التوليد كانت تنطلق قبلها فتجد
     * معرّف الصوت السحابي فارغاً، فيُرفض الطلب وتسقط البطاقة إلى محرك احتياطي —
     * ثم تُخزَّن كذلك إلى الأبد. المستخدم يدفع مقابل صوت طبيعي ويسمع صوتاً آلياً
     * بسبب أجزاء من الثانية.
     */
    private val settingsReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    private suspend fun applyAudioSettings(a: com.tornado.vocab.data.AudioSettings) {
        speed = a.speed
        detailed = a.detailed
        shuffle = a.shuffle
        wordRepeat = a.wordRepeat
        listRepeat = ListRepeat.normalize(a.listRepeat)
        applyRepeatMode()
        speakArabic = a.speakArabic
        humanOnly = a.humanOnly

        tts.englishVoiceName = a.englishVoice.ifBlank { null }
        tts.arabicVoiceName = a.arabicVoice.ifBlank { null }
        tts.preferredEngine = a.ttsEngine.ifBlank { null }
        // اختيار المستخدم يسبق الاكتشاف التلقائي، والتلقائي يسبق ألا نفعل شيئاً
        tts.arabicEngine = a.ttsArabicEngine.ifBlank { tts.bestArabicEngine() }
        kokoro.sid = a.kokoroSid
        voices.preferKokoro = a.useKokoro

        // الصوت السحابي هو المصدر الأساسي لكل شيء، والباقي شبكة أمان لا بديل
        humanAudio.enabled = a.voiceStrategy == "HUMAN_FIRST"
        voices.strategy = runCatching { VoiceStrategy.valueOf(a.voiceStrategy) }
            .getOrDefault(VoiceStrategy.UNIFIED)
        narration.maxCacheBytes = a.cacheLimitMb.toLong() * 1024 * 1024
        voiceTag = voices.signature()

        player.setPlaybackSpeed(speed)
        PlaybackBus.update {
            it.copy(
                humanOnly = humanOnly,
                piperReady = false,
                cloudReady = false
            )
        }
        pushState()
        settingsReady.complete(Unit)
    }

    /**
     * إشعار التحضير — إشعار عادي لا إشعار مقدّمة.
     *
     * كان يُرفع بـstartForeground، فينتزع ملكية المقدّمة من جلسة الوسائط.
     * والنتيجة أن إشعار المشغّل الكامل — بأزرار التشغيل والتالي والسابق —
     * لا يظهر إطلاقاً، ولا يرى المستخدم إلا سطراً فقيراً مكتوباً عليه
     * «Preparing your session». المشغّل كان موجوداً طوال الوقت ومحجوباً بإشعاري.
     *
     * الملكية الآن لجلسة الوسائط وحدها: هي تعرف متى تبدأ وماذا تعرض وكيف
     * تتصرّف عند إزالة الإشعار. وهذا التحضير مجرّد خبر عابر يسبقها.
     */
    private fun showPrepNotification(done: Int, total: Int, wordText: String) {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, PlaybackReliability.CHANNEL_PREPARE)
            .setSmallIcon(R.drawable.ic_stat_tornado)
            .setContentTitle("Preparing your session")
            .setContentText(if (wordText.isBlank()) "Loading audio…" else "Loading \"$wordText\"")
            .setOngoing(true).setSilent(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (total > 0) builder.setProgress(total, done, false) else builder.setProgress(0, 0, true)

        val notification: Notification = builder.build()
        prepNotificationActive = true
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(PlaybackReliability.NOTIFICATION_PREPARE, notification)
        }
    }

    private fun clearPrepNotification() {
        if (!prepNotificationActive) return
        prepNotificationActive = false
        runCatching {
            NotificationManagerCompat.from(this).cancel(PlaybackReliability.NOTIFICATION_PREPARE)
        }
    }

    // ===== قفل الإيقاظ =====

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tornado:render").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ===== مشغّل يعلن دائماً عن التالي والسابق =====

    private inner class QueueAwarePlayer(inner: Player) : ForwardingPlayer(inner) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> session.size > 1
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = session.size > 1
        override fun hasPreviousMediaItem(): Boolean = session.size > 1
        override fun seekToNextMediaItem() = goNext()
        override fun seekToNext() = goNext()
        override fun seekToPreviousMediaItem() = goPrevious()

        override fun seekToPrevious() {
            if (currentPosition > 3_000) seekTo(0) else goPrevious()
        }
    }

    // ===== مستمع المشغّل =====

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) clearPrepNotification()
            pushState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // الانتقال التلقائي وحده يُنهي ملاحظة؛ القفز اليدوي اختيار المستخدم
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) repeatNoteIfNeeded()
            pushState()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onPlaylistEnded()
            pushState()
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackBus.update { it.copy(message = "Playback error: ${error.errorCodeName}") }
            scope.launch {
                delay(300)
                if (player.currentMediaItemIndex < player.mediaItemCount - 1) {
                    player.seekTo(player.currentMediaItemIndex + 1, 0)
                    player.prepare(); player.play()
                }
            }
        }
    }

    private fun onPlaylistEnded() {
        if (planCursor < session.size) {
            waitingForRender = true
            showPrepNotification(planCursor, session.size, session.getOrNull(planCursor)?.title.orEmpty())
            return
        }
        /*
         * آخر فقرة في الطابور لا يلتقطها انتقالٌ بعدها.
         *
         * إعادة الملاحظة تُنفَّذ عند الانتقال إلى ما بعدها، ولا انتقال بعد
         * آخرها — فتنتهي الجلسة بلا تكرار. فنعالج هذه الحالة هنا صراحةً.
         */
        if (ListRepeat.normalize(listRepeat) == ListRepeat.ONE) {
            val last = rendered.lastOrNull()
            if (last?.kind == RowKind.NOTE_CHUNK) {
                val first = firstIndexOfNote(last.id)
                if (first >= 0) { player.seekTo(first, 0); player.play(); return }
            }
        }
        if (scopeMode == PlayScope.SINGLE) { finishSession(); return }
        when (ListRepeat.normalize(listRepeat)) {
            ListRepeat.ALL -> restartFromStart()
            else -> { finishSession(); PlaybackBus.update { it.copy(message = "Playlist finished") } }
        }
    }

    /**
     * تكرار الكلمة الواحدة يُنفَّذ في المشغّل نفسه لا في منطق نهاية القائمة.
     *
     * ExoPlayer يملك هذا الوضع أصلاً (REPEAT_MODE_ONE): يعيد العنصر الجاري
     * بلا انقطاع وبلا إعادة بناء. كتابة المنطق يدوياً فوق مشغّل يعرفه تعني
     * التعارض معه — وهذا بالضبط ما جعل الزر القديم لا يفعل شيئاً.
     */
    private fun applyRepeatMode() {
        /*
         * «تكرار واحد» يعني بطاقةً واحدة أو **ملاحظةً كاملة**.
         *
         * REPEAT_MODE_ONE في المشغّل يعيد العنصر الجاري، وهو صالح للكلمة لأنها
         * عنصر واحد. أما الملاحظة فصارت عشرات العناصر — فقرةً فقرة — فيعيد
         * الفقرة الجارية وحدها ويظنّ المستخدم الزرّ معطوباً أو مجنوناً.
         *
         * فنترك المشغّل بلا تكرار للملاحظات، ونعيد أول عنصر من الملاحظة يدوياً
         * حين تنتهي آخر فقرة فيها.
         */
        val noteQueue = rendered.any { it.kind == RowKind.NOTE_CHUNK }
        player.repeatMode = when {
            ListRepeat.normalize(listRepeat) == ListRepeat.ONE && !noteQueue ->
                Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * يعيد الملاحظة من أوّلها حين تنتهي آخر فقرة منها ووضع التكرار «واحد».
     *
     * يُستدعى عند كل انتقال بين عناصر المشغّل: إن كان العنصر التالي من ملاحظة
     * أخرى، فالملاحظة الحالية قد انتهت — فنقفز إلى أوّل فقرة فيها.
     */
    /**
     * أول فقرة في الملاحظة داخل قائمة المشغّل.
     *
     * البحث عن أول عنصر يحمل رقم الملاحظة لا يكفي: إعادة البناء تبدأ من موضع
     * المستخدم لا من الصفر، فيصير أول المبنيّ هو الفقرة الثالثة — وتُعاد
     * الملاحظة من وسطها. فنطلب الفقرة رقم صفر صراحةً، وإن لم تكن مبنيّة
     * فأقدم ما بُني منها.
     */
    private fun firstIndexOfNote(id: Long): Int {
        val exact = rendered.indexOfFirst { it.id == id && it.chunkIndex == 0 }
        return if (exact >= 0) exact else rendered.indexOfFirst { it.id == id }
    }

    private fun repeatNoteIfNeeded() {
        if (ListRepeat.normalize(listRepeat) != ListRepeat.ONE) return
        val i = player.currentMediaItemIndex
        val previous = rendered.getOrNull(i - 1) ?: return
        if (previous.kind != RowKind.NOTE_CHUNK) return
        val current = rendered.getOrNull(i)
        // ما زلنا داخل نفس الملاحظة — لا شيء يُعاد بعد
        if (current != null && current.id == previous.id) return
        val first = firstIndexOfNote(previous.id)
        if (first >= 0) player.seekTo(first, 0)
    }

    private fun restartFromStart() {
        if (player.mediaItemCount == 0) return
        player.seekTo(0, 0); player.prepare(); player.play()
    }

    private fun finishSession() {
        player.playWhenReady = false
        releaseWakeLock(); clearPrepNotification()
        /*
         * نُخلي النموذج من الذاكرة حين ينتهي التوليد.
         *
         * قياسٌ على المحاكي أظهر خمسمئة وأربعة ميغابايت في الذاكرة الأصلية
         * أثناء البناء — النموذج ومَسارِح ONNX التي تنمو مع الاستعمال. وإبقاء
         * ذلك محجوزاً بعد أن صار كل شيء مخزَّناً يجعل التطبيق أول ما يقتله
         * النظام عند الضغط، فيجد المستخدم مشغّله ميتاً بلا سبب ظاهر.
         *
         * والكلفة إعادة تحميل عند الجلسة التالية — ثوانٍ تُدفع مرة، مقابل
         * نصف غيغابايت يُفرَج عنه طوال الوقت الذي لا يُبنى فيه شيء.
         */
        runCatching { kokoro.release() }
        PlaybackBus.update { it.copy(isPlaying = false, preparing = false) }
        pushState()
    }

    // ===== بناء الجلسة =====

    /**
     * يعيد بناء الجلسة من الكلمة الجارية بإعدادات جديدة.
     *
     * تغيير مثل FULL/SHORT كان يسري «من الكلمة التالية» تفادياً لقطع الصوت،
     * لكن النتيجة أسوأ: تضغط الزر فلا يتغيّر شيء مما تسمعه، فتستنتج أنه زر
     * وهمي. زرّ لا أثر له عند الضغط زرّ معطّل مهما كان صحيحاً في الداخل.
     *
     * فنعيد البناء من موضعك الحالي لا من أول القائمة — تسمع الأثر فوراً
     * ولا تفقد مكانك.
     */
    private fun rebuildFromCurrent() {
        if (session.isEmpty()) return
        if (session.any { it.kind == RowKind.NOTE_CHUNK }) { rebuildNoteSession(); return }
        val at = renderedToSession.getOrNull(player.currentMediaItemIndex) ?: 0
        val wasPlaying = player.playWhenReady
        startSession(autoPlay = wasPlaying, from = at)
    }

    /**
     * يعيد تقسيم طابور الملاحظات بالوضع والتكرار الحاليين.
     *
     * الكلمة عنصرٌ ثابت مهما تغيّرت الإعدادات، فيكفيها إعادة بناء الصوت. أما
     * الملاحظة فتُقسَّم إلى فقرات أو جُمل حسب FULL/SHORT، وعدد عناصرها نفسه
     * يتغيّر — فإعادة البناء وحدها تُبقي التقسيم القديم وتجعل الزرّ بلا أثر.
     * وقد رأى المستخدم ذلك: يضغط SHORT فتظلّ الفقرة هي ما يتكرّر.
     *
     * والاستئناف من الملاحظة الجارية لا من أولها: من غيّر إعداده في منتصف
     * نصّ يريد إكماله، لا العودة إلى بدايته.
     */
    private fun rebuildNoteSession() = scope.launch {
        val currentNoteId = rendered.getOrNull(player.currentMediaItemIndex)?.id
        val wasPlaying = player.playWhenReady
        val ids = session.map { it.id }.distinct()
        val rebuilt = withContext(Dispatchers.IO) {
            ids.mapNotNull { id -> notes.byId(id) }.flatMap { note ->
                NoteChunker.units(note.text, detailed).flatMapIndexed { i, text ->
                    List(wordRepeat.coerceIn(1, 10)) {
                        PlayItem(
                            id = note.id,
                            title = note.title,
                            subtitle = text.take(70),
                            kind = RowKind.NOTE_CHUNK,
                            chunkIndex = i,
                            favorite = note.favorite
                        )
                    }
                }
            }
        }
        if (rebuilt.isEmpty()) return@launch
        session = rebuilt
        val from = rebuilt.indexOfFirst { it.id == currentNoteId }.coerceAtLeast(0)
        startSession(autoPlay = wasPlaying, from = from)
    }

    private fun startSession(autoPlay: Boolean, from: Int = 0) {
        renderJob?.cancel()
        waitingForRender = false
        rendered.clear(); renderedSources.clear(); renderedToSession.clear(); renderedLines.clear()
        skipped = 0; planCursor = from.coerceIn(0, (session.size - 1).coerceAtLeast(0))
        player.clearMediaItems()

        if (session.isEmpty()) { finishSession(); pushState(); return }

        // حزام أمان: أي جلسة تبدأ بمستوى صوت كامل مهما حدث قبلها
        player.volume = 1f

        acquireWakeLock()
        showPrepNotification(planCursor, session.size, session[planCursor].title)
        PlaybackBus.update { it.copy(preparing = true, prepareDone = 0, prepareTotal = 0, skippedCount = 0) }
        renderJob = scope.launch { renderLoop(autoPlay) }
    }

    /**
     * حلقة التوليد المتدحرجة.
     * [planCursor] يتقدّم في الخطة، و[rendered] لا ينمو إلا بنجاح فعلي —
     * فتبقى فهارس المشغّل صحيحة مهما تخطّينا من بطاقات.
     */
    private suspend fun renderLoop(autoPlay: Boolean) {
        // لا نولّد حرفاً واحداً قبل أن نعرف بأي صوت نولّده
        settingsReady.await()
        var started = false

        while (planCursor < session.size) {
            coroutineContext.ensureActive()

            val ahead = rendered.size - player.currentMediaItemIndex
            if (started && ahead >= WINDOW_AHEAD) { delay(700); continue }

            val row = session[planCursor]
            // الإعدادات تُقرأ لكل بطاقة على حدة، فتغييرها يسري من التالية بلا قطع
            val spec = currentSpec()
            if (!started || waitingForRender) {
                showPrepNotification(planCursor, session.size, row.title)
            }

            /*
             * المصدر يُقرأ حسب نوع الصفّ.
             *
             * البطاقة الكاملة تُقرأ الآن فقط وتُحرَّر فور بناء الصوت — وهذا ما
             * يجعل جلسة من خمسة آلاف كلمة ممكنة أصلاً. والملاحظة تُعامَل مثلها
             * تماماً: يُقرأ مقطعها المطلوب وحده لا النصّ كله.
             */
            val result = withContext(Dispatchers.IO) {
                when (row.kind) {
                    RowKind.WORD -> {
                        val full = repository.word(row.id) ?: return@withContext null
                        narration.getOrBuild(full, spec) { done, total ->
                            PlaybackBus.update { it.copy(prepareDone = done, prepareTotal = total) }
                        }
                    }
                    RowKind.NOTE_CHUNK -> {
                        val note = notes.byId(row.id) ?: return@withContext null
                        // نفس الوحدة التي بنى بها الطابور — وإلا نُطق نصٌّ غير المعروض
                        val chunk = NoteChunker.units(note.text, detailed).getOrNull(row.chunkIndex)
                            ?: return@withContext null
                        /*
                         * Say يحدّد العدد وSHORT/FULL تحدّد الوحدة.
                         *
                         * للكلمات يعني detailed «شرحاً مفصّلاً»، وللنصوص لا
                         * معنى لذلك — فيُعاد استعماله وحدةً للتكرار: الفقرة
                         * مع FULL والجملة مع SHORT. زرّان موجودان يكتسبان
                         * معنى في سياقهما بدل زرّين جديدين يزدحم بهما المشغّل.
                         */
                        /*
                         * بصمة النص جزء من المفتاح.
                         *
                         * كان المفتاح رقم الملاحظة ورقم المقطع فقط، فتُعدَّل
                         * الملاحظة ويجد التطبيق ملفاً بنفس الاسم فيعيده كما
                         * هو — نصٌّ جديد بصوت قديم إلى الأبد. والمستخدم عدّل
                         * نوتة فسمع القديمة، ولا سبيل عنده لإجبار البناء.
                         */
                        /*
                         * الجملة تُبنى مرة واحدة، بلا تكرار ولا وضع.
                         *
                         * كان التكرار والوضع يدخلان الملف نفسه، فكل تبديل
                         * لـSay أو FULL يبني صوتاً جديداً — وهو ما شكا منه
                         * المستخدم مراراً: «المفروض تنزل الجملة خلاص، ما
                         * يحتاج إعادة بناء، فقط استدعاء حسب التكرار».
                         *
                         * والتكرار صار طابوراً: نفس الجملة تُضاف مرتين أو
                         * ثلاثاً، وتُقرأ من نفس الملف المخزَّن. فتبديل Say
                         * لا يولّد شيئاً — يعيد ترتيب الطابور فحسب.
                         */
                        narration.getOrBuildText(
                            key = "note-${note.id}-${row.chunkIndex}-${chunk.hashCode()}",
                            text = chunk,
                            voiceTag = voiceTag
                        ) { done, total ->
                            PlaybackBus.update { it.copy(prepareDone = done, prepareTotal = total) }
                        }
                    }
                }
            }
            coroutineContext.ensureActive()
            planCursor++

            if (result == null) {
                skipped++
                PlaybackBus.update { it.copy(skippedCount = skipped) }
            } else {
                rendered.add(row)
                renderedSources.add(result.source)
                renderedLines.add(result.lines)
                renderedToSession.add(planCursor - 1)
                appendItem(row, planCursor - 1, result)
            }

            if (!started && rendered.size >= minOf(HEAD_START, session.size)) {
                started = true
                // نوع المحتوى يُعرف بعد أول بناء، ووضع التكرار يعتمد عليه
                applyRepeatMode()
                beginPlayback(autoPlay)
            }
            if (waitingForRender && rendered.isNotEmpty()) {
                waitingForRender = false
                resumeAfterWait()
            }
        }

        releaseWakeLock()
        PlaybackBus.update { it.copy(preparing = false) }
        if (!started) {
            if (rendered.isEmpty()) {
                clearPrepNotification()
                PlaybackBus.update {
                    it.copy(
                        preparing = false, isPlaying = false,
                        message = "No human recordings found for these words"
                    )
                }
            } else beginPlayback(autoPlay)
        }
        if (player.isPlaying) clearPrepNotification()
        pushState()
    }

    private fun beginPlayback(autoPlay: Boolean) {
        PlaybackBus.update { it.copy(preparing = false) }
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = autoPlay
        pushState()
    }

    private fun resumeAfterWait() {
        val next = player.currentMediaItemIndex + 1
        if (next < player.mediaItemCount) {
            player.seekTo(next, 0); player.prepare(); player.play()
        }
    }

    private fun appendItem(word: PlayItem, position: Int, result: NarrationResult) {
        val metadata = MediaMetadata.Builder()
            .setTitle(word.title)
            .setArtist(word.subtitle)
            .setAlbumTitle("Tornado — ${position + 1} of ${session.size}")
            .setDisplayTitle(word.title)
            .setIsBrowsable(false).setIsPlayable(true)
            .build()

        /*
         * لكل عنصر معرّف فريد — ولو كان صوته نفس الملف.
         *
         * كان المعرّف رقم الكلمة أو الملاحظة، وهو صالح حين تكون كل كلمة عنصراً
         * واحداً. أما الملاحظة فتدخل الطابور بعشر جمل تحمل رقمها نفسه، ومع
         * Say ×٢ تتكرّر كل جملة — فتصير في الطابور عناصر لا يميّز بينها شيء.
         *
         * وجلسة الوسائط تبني طابورها على هذه المعرّفات، فتراها عنصراً واحداً:
         * يُشغَّل الأول ولا ينتقل إلى ما بعده. والمستخدم يسمع ثلاث عشرة ثانية
         * ثم صمتاً، ويظنّ النصّ كلّه هذا.
         */
        player.addMediaItem(
            MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(result.file))
                .setMediaId("${word.id}-${word.chunkIndex}-$position")
                .setMediaMetadata(metadata)
                .build()
        )
        pushState()
    }

    /**
     * FULL و SHORT يحكمان محتوى السرد، لا مصدر الصوت.
     *
     * كان الوضع البشري الخالص يتجاهل هذا الخيار كلياً — يقرأ الكلمة وأمثلتها
     * فقط مهما اختار المستخدم، فيبدو الزر بلا أثر. المحتوى الآن:
     *   FULL  = البطاقة كما هي مكتوبة في القائمة: المعاني والتصريفات
     *           والمشتقات والمرادفات والمتلازمات والأمثلة والفروق.
     *   SHORT = الكلمة ومعانيها الرئيسية فقط.
     * أما تفضيل الصوت البشري فيبقى مستقلاً تماماً ويحكم مصدر النطق لا محتواه.
     */
    private fun currentSpec() = NarrationSpec(
        repeat = wordRepeat,
        mode = NarrationMode.RICH,
        detail = if (detailed) NarrationDetail.FULL else NarrationDetail.BRIEF,
        speakArabic = speakArabic,
        voiceTag = voiceTag
    )

    private fun goNext() {
        if (player.mediaItemCount == 0) return
        val next = player.currentMediaItemIndex + 1
        when {
            next < player.mediaItemCount -> { player.seekTo(next, 0); player.play() }
            planCursor < session.size -> {
                waitingForRender = true
                showPrepNotification(planCursor, session.size, session.getOrNull(planCursor)?.title.orEmpty())
            }
            else -> { player.seekTo(0, 0); player.play() }
        }
        pushState()
    }

    private fun goPrevious() {
        if (player.mediaItemCount == 0) return
        val prev = player.currentMediaItemIndex - 1
        if (prev >= 0) player.seekTo(prev, 0) else player.seekTo(player.mediaItemCount - 1, 0)
        player.play()
        pushState()
    }

    private fun rebuildSession() {
        if (session.isEmpty()) return
        val currentId = rendered.getOrNull(player.currentMediaItemIndex)?.id
        val wasPlaying = player.playWhenReady
        val startAt = session.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        session = session.drop(startAt) + session.take(startAt)
        startSession(wasPlaying)
    }

    // ===== مؤقّت النوم =====

    private fun applySleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes <= 0) { sleepDeadline = 0; pushState(); return }
        sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (isActive) {
                val remaining = sleepDeadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    // تلاشٍ قصير بدل قطع مفاجئ — الفرق ملموس عند النوم
                    fadeOutAndPause()
                    sleepMinutes = 0; sleepDeadline = 0
                    PlaybackBus.update { it.copy(message = "Sleep timer finished") }
                    pushState()
                    return@launch
                }
                delay(1_000)
                pushState()
            }
        }
    }

    /**
     * تلاشٍ ثم إيقاف.
     *
     * الاستعادة داخل finally ضرورية لا تجميلية: إلغاء الكوروتين في منتصف
     * التلاشي (تغيير المؤقّت، إيقاف الجلسة، إنهاء الخدمة) كان يترك مستوى
     * الصوت عند الصفر إلى الأبد — فيبدو كل تشغيل لاحق صامتاً تماماً بينما
     * المشغّل يعمل وشريط التقدّم يتحرّك. عيب صامت يصعب تفسيره من الواجهة.
     */
    private suspend fun fadeOutAndPause() {
        val steps = 12
        try {
            repeat(steps) { i ->
                player.volume = (1f - (i + 1f) / steps).coerceIn(0f, 1f)
                delay(150)
            }
            player.playWhenReady = false
        } finally {
            player.volume = 1f
        }
    }

    // ===== مؤقّت الحالة =====

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                if (player.isPlaying || player.playbackState == Player.STATE_READY) pushState()
                delay(400)
            }
        }
    }

    private fun pushState() {
        val dur = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }

        // الجملة الجارية تُشتقّ من الموضع الفعلي للمشغّل — لا تخمين ولا تقريب
        val spoken = runCatching {
            val lines = renderedLines.getOrNull(player.currentMediaItemIndex).orEmpty()
            if (lines.isEmpty()) "" else {
                val pos = player.currentPosition
                (lines.firstOrNull { pos in it.startMs until it.endMs } ?: lines.last()).text
            }
        }.getOrDefault("")

        /**
         * الطابور المعروض هو الجلسة كاملة لا ما بُني منها فقط.
         * عرض المبني وحده كان يُظهر "٠/٠" وشاشة فارغة أثناء التحضير، فيبدو
         * المشغّل معطّلاً بينما هو يعمل. الآن يرى المستخدم الكلمة ورقمها فوراً.
         */
        val sessionSource = renderedToSession.withIndex()
            .associate { (i, plan) -> plan to renderedSources.getOrElse(i) { VoiceSource.NONE } }

        val items = session.mapIndexed { i, w ->
            QueueItem(
                id = w.id,
                word = w.title,
                subtitle = w.subtitle,
                status = w.status,
                source = sessionSource[i] ?: VoiceSource.NONE,
                ready = sessionSource.containsKey(i),
                favorite = w.favorite,
                isNote = w.kind == RowKind.NOTE_CHUNK
            )
        }
        // فهرس المشغّل يشير للمبني؛ نترجمه لموضعه الحقيقي في الجلسة
        val playerIndex = player.currentMediaItemIndex
        val displayIndex = renderedToSession.getOrElse(playerIndex) {
            planCursor.coerceAtMost((session.size - 1).coerceAtLeast(0))
        }

        PlaybackBus.update {
            it.copy(
                spokenLine = spoken,
                queue = items,
                index = displayIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                readyCount = rendered.size,
                skippedCount = skipped,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = dur,
                speed = speed, detailed = detailed, shuffle = shuffle,
                wordRepeat = wordRepeat, listRepeat = listRepeat,
                scope = scopeMode, loopsLeft = loopsLeft,
                humanOnly = humanOnly,
                sleepTimerMinutes = sleepMinutes,
                sleepRemainingMs = if (sleepDeadline > 0)
                    (sleepDeadline - System.currentTimeMillis()).coerceAtLeast(0) else 0
            )
        }
    }

    // ===== واجهة الأوامر =====

    private val commands = object : PlaybackCommands {

        override fun setQueue(rows: List<PlayItem>, startIndex: Int, scope: PlayScope, autoPlay: Boolean) {
            scopeMode = scope
            // نجهّز الصوت العصبي بهدوء حتى لا تسقط أي كلمة إلى محرك النظام
            val ordered = when {
                scope == PlayScope.SINGLE -> rows.getOrNull(startIndex)?.let { listOf(it) } ?: emptyList()
                shuffle -> rows.shuffled()
                else -> rows
            }
            session = if (scope == PlayScope.LIST && ordered.isNotEmpty() && !shuffle &&
                startIndex in ordered.indices
            ) ordered.drop(startIndex) + ordered.take(startIndex) else ordered
            // loopsLeft لم يعد مستعملاً بعد الانتقال لأوضاع التكرار الثلاثة
            startSession(autoPlay)
        }

        override fun playPause() {
            if (session.isEmpty()) return
            if (player.isPlaying) player.playWhenReady = false
            else if (player.mediaItemCount == 0) startSession(true)
            else { player.prepare(); player.playWhenReady = true }
            pushState()
        }

        override fun play() {
            if (player.mediaItemCount == 0) startSession(true)
            else { player.prepare(); player.playWhenReady = true }
            pushState()
        }

        override fun pause() { player.playWhenReady = false; pushState() }
        override fun next() = goNext()
        override fun previous() = goPrevious()

        /** [i] فهرس داخل الجلسة المعروضة — نترجمه لفهرس المشغّل الفعلي */
        override fun jumpTo(i: Int) {
            if (i !in session.indices) return
            val playerIndex = renderedToSession.indexOf(i)
            if (playerIndex >= 0 && playerIndex < player.mediaItemCount) {
                player.seekTo(playerIndex, 0); player.prepare(); player.play(); pushState()
            } else {
                // لم تُبنَ بعد: نعيد ترتيب الجلسة لتبدأ من هذه الكلمة
                session = session.drop(i) + session.take(i)
                startSession(true)
            }
        }

        override fun seekTo(ms: Long) { player.seekTo(ms.coerceAtLeast(0)); pushState() }

        /** تقديم وترجيع نسبيان — يعبران حدود المقطع بدل التوقف عندها */
        override fun seekBy(deltaMs: Long) {
            if (player.mediaItemCount == 0) return
            val target = player.currentPosition + deltaMs
            val duration = player.duration
            when {
                target < 0 -> {
                    if (player.currentMediaItemIndex > 0) {
                        player.seekTo(player.currentMediaItemIndex - 1, 0)
                    } else player.seekTo(0)
                }
                duration != C.TIME_UNSET && target > duration -> goNext()
                else -> player.seekTo(target)
            }
            pushState()
        }

        override fun stopPlayback() {
            renderJob?.cancel()
            player.playWhenReady = false
            releaseWakeLock(); clearPrepNotification()
            PlaybackBus.update { it.copy(isPlaying = false, preparing = false) }
            pushState()
        }

        override fun setSpeed(v: Float) {
            speed = v
            player.setPlaybackSpeed(v)
            scope.launch { settings.setSpeed(v) }
            pushState()
        }

        /**
         * تغيير المحتوى يسري من البطاقة التالية، لا فوراً.
         *
         * إعادة بناء الجلسة أثناء التشغيل كانت تمسح قائمة المشغّل وتبدأ توليداً
         * جديداً — أي صمت تام يمتد حتى ينتهي بناء بطاقة كاملة قد تبلغ دقيقتين.
         * المستخدم يضغط زراً فينقطع الصوت. المشغّلات الحقيقية تطبّق مثل هذه
         * الإعدادات على المقطع التالي وتترك الجاري يكمل، وهذا ما نفعله الآن.
         */
        override fun setDetailed(v: Boolean) {
            if (detailed == v) return
            detailed = v
            scope.launch { settings.setDetailed(v) }
            PlaybackBus.update {
                it.copy(detailed = v, message = if (v) "Full explanation" else "Short: meanings only")
            }
            rebuildFromCurrent()
            pushState()
        }

        override fun setWordRepeat(v: Int) {
            val next = v.coerceIn(1, 10)
            if (wordRepeat == next) return
            wordRepeat = next
            scope.launch { settings.setWordRepeat(wordRepeat) }
            PlaybackBus.update {
                it.copy(wordRepeat = wordRepeat, message = "Each word ×$wordRepeat")
            }
            rebuildFromCurrent()
            pushState()
        }

        override fun setShuffle(v: Boolean) {
            shuffle = v
            scope.launch { settings.setShuffle(v) }
            if (session.isNotEmpty()) {
                val currentId = rendered.getOrNull(player.currentMediaItemIndex)?.id
                val reordered = if (v) session.shuffled() else session.sortedBy { it.title.lowercase() }
                val at = reordered.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                session = reordered.drop(at) + reordered.take(at)
                startSession(player.playWhenReady)
            }
            pushState()
        }

        override fun setListRepeat(v: Int) {
            listRepeat = ListRepeat.normalize(v)
            // الوضع يُطبَّق على المشغّل فوراً — زرّ تكرار لا يغيّر السلوك الجاري زرّ معطّل
            applyRepeatMode()
            scope.launch { settings.setListRepeat(listRepeat) }
            pushState()
        }

        override fun setSleepTimer(minutes: Int) = applySleepTimer(minutes)

        override fun speakCard(word: Word, full: Boolean) {
            // الزرّ يفرض مستوى التفصيل الذي طلبه المستخدم لا الذي في الإعدادات
            detailed = full
            scopeMode = PlayScope.SINGLE
            session = listOf(
                PlayItem(
                    id = word.id,
                    title = word.word,
                    subtitle = word.primaryAr.ifBlank { word.primaryEn },
                    status = word.status.name,
                    favorite = word.favorite
                )
            )
            loopsLeft = 0
            startSession(true)
        }

        override fun playPronunciation(url: String, fallbackWord: String, british: Boolean) {
            // التسجيل البشري أولاً دائماً، حتى لزر النطق المفرد
            scope.launch(Dispatchers.IO) {
                val tag = if (british) "uk" else "us"
                val f = java.io.File(cacheDir, "pron-$tag-${fallbackWord.lowercase().hashCode()}.wav")
                val ok = f.length() > 512 || humanAudio.fetchWord(fallbackWord, f, british)
                if (ok) {
                    withContext(Dispatchers.Main) { playPron(android.net.Uri.fromFile(f)) }
                    return@launch
                }
                if (url.isNotBlank()) {
                    withContext(Dispatchers.Main) { playPron(android.net.Uri.parse(url)) }
                    return@launch
                }
                val seg = Segment(fallbackWord, SegLang.EN, role = SegRole.HEADWORD)
                val source = voices.synthesize(seg, f, headword = fallbackWord)
                if (source != VoiceSource.NONE) {
                    withContext(Dispatchers.Main) { playPron(android.net.Uri.fromFile(f)) }
                }
            }
        }

        override fun speakText(text: String, arabic: Boolean) = speakTextInternal(text, arabic)

        override fun removeFromSession(id: Long) {
            if (session.none { it.id == id }) return
            /*
             * الإزالة من ثلاثة مواضع متوازية دفعة واحدة.
             *
             * الجلسة والمبنيّ وقائمة المشغّل مرايا لبعضها بفهارس متطابقة،
             * وحذف من واحدة دون البقية يفسد كل حسابات المواضع — وهي نفس
             * الفهارس التي يقوم عليها التنقّل والعدّاد وشريط التقدّم.
             */
            val playingId = rendered.getOrNull(player.currentMediaItemIndex)?.id
            val removedBeforeCursor = session.take(planCursor).count { it.id == id }
            session = session.filter { it.id != id }
            planCursor -= removedBeforeCursor

            var i = rendered.size - 1
            while (i >= 0) {
                if (rendered[i].id == id) {
                    rendered.removeAt(i)
                    renderedSources.removeAt(i)
                    if (i < renderedLines.size) renderedLines.removeAt(i)
                    renderedToSession.removeAt(i)
                    if (i < player.mediaItemCount) player.removeMediaItem(i)
                }
                i--
            }
            // مواضع الخطة بعد المحذوف تتقدّم — نُعيد بناء الربط من الجلسة الجديدة
            for (k in renderedToSession.indices) {
                val item = rendered[k]
                renderedToSession[k] = session.indexOfFirst { it === item }
                    .takeIf { it >= 0 } ?: renderedToSession[k].coerceAtMost(session.size - 1)
            }
            if (session.isEmpty()) { finishSession() } else if (playingId == id) {
                // كان المحذوف هو المسموع: ننتقل لما بعده بدل ترك صوت شبح
                player.seekTo(player.currentMediaItemIndex.coerceAtMost(player.mediaItemCount - 1), 0)
            }
            pushState()
        }
    }

    /**
     * نطق نص مفرد على المشغّل الجانبي.
     * العربية لا تملك تسجيلات بشرية للكلمات، فهذه الحالة تسمح بالتوليد صراحةً
     * لأنها ضغطة زر واعية من المستخدم لا سرداً تلقائياً.
     */
    private fun speakTextInternal(text: String, arabic: Boolean) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val lang = if (arabic) SegLang.AR else SegLang.EN
            val f = java.io.File(cacheDir, "say-${lang.name}-${text.hashCode()}.wav")
            if (f.length() < 512) {
                val seg = Segment(text, lang, role = SegRole.GENERATED)
                val source = voices.synthesize(seg, f)
                if (source == VoiceSource.NONE) return@launch
            }
            withContext(Dispatchers.Main) { playPron(android.net.Uri.fromFile(f)) }
        }
    }

    private fun playPron(uri: android.net.Uri) {
        runCatching {
            pronPlayer.setMediaItem(MediaItem.fromUri(uri))
            pronPlayer.prepare()
            pronPlayer.play()
        }
    }
}
