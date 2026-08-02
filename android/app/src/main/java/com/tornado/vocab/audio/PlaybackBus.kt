package com.tornado.vocab.audio

import android.content.Context
import android.content.Intent
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.PlayItem
import com.tornado.vocab.data.WordStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** عنصر واحد في طابور التشغيل — خفيف بما يكفي لإعادة التركيب بلا كلفة */
data class QueueItem(
    val id: Long,
    val word: String,
    val subtitle: String,
    val status: String,
    /** من نطق هذه البطاقة فعلاً — يظهر للمستخدم كشارة */
    val source: VoiceSource = VoiceSource.NONE,
    val ready: Boolean = false,
    val favorite: Boolean = false,
    /**
     * هل هذا العنصر مقطع ملاحظة لا كلمة؟
     *
     * الواجهة تقرّر به ما تعرضه: أزرار التصنيف (جديدة/أخطأت/معروفة) شأن
     * كلمات تُراجَع، وعرضها فوق مقطع نصّ يوحي بأن للنصوص جدولة — وليس لها.
     */
    val isNote: Boolean = false
)

/** نطاق التشغيل: القائمة كاملة أم كلمة واحدة تتوقف بعدها */
enum class PlayScope { LIST, SINGLE }

data class PlaybackUiState(
    /**
     * الجملة التي تُنطق في هذه اللحظة — للملاحظات وحدها.
     *
     * المشغّل كان يعرض أول سبعين حرفاً من المقطع ثابتة مهما تقدّم الصوت،
     * فيقرأ المستخدم شيئاً ويسمع شيئاً آخر بعد ثوانٍ. والنص الذي لا يلاحق
     * الصوت أسوأ من غياب النص: يوهم بالمواكبة ولا يواكب.
     */
    val spokenLine: String = "",
    val queue: List<QueueItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val preparing: Boolean = false,
    val prepareDone: Int = 0,
    val prepareTotal: Int = 0,
    val readyCount: Int = 0,
    val skippedCount: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1.0f,
    val detailed: Boolean = true,
    val shuffle: Boolean = false,
    val wordRepeat: Int = 1,
    val listRepeat: Int = 1,
    val scope: PlayScope = PlayScope.LIST,
    val loopsLeft: Int = 0,
    val sleepTimerMinutes: Int = 0,
    val sleepRemainingMs: Long = 0,
    val message: String? = null,
    val humanOnly: Boolean = true,
    /** أُبقي للتوافق مع الواجهة؛ الطبقة العصبية على الجهاز حُذفت */
    val piperReady: Boolean = false,
    /** الصوت السحابي مهيّأ وجاهز — يظهر في المشغّل كتأكيد للمستخدم */
    val cloudReady: Boolean = false,
    /** تنزيل الصوت العصبي جارٍ — يُعرض في المشغّل بلا مقاطعة التشغيل */
    val voiceDownloadActive: Boolean = false,
    val voiceDownloadProgress: Float = 0f,
    val ttsMissing: Boolean = false,
    val connected: Boolean = false,
    /** إظهار المعنى على الشاشة — حالة عرض بحتة لا تمسّ التشغيل */
    val showTranslation: Boolean = true
) {
    val current: QueueItem? get() = queue.getOrNull(index)
    val currentFavorite: Boolean get() = current?.favorite == true

    /**
     * الحالة الفعلية للكلمة الجارية — المصدر الوحيد للحقيقة في الواجهة.
     * كل ما يعرض الحالة أو يغيّرها يمرّ من هنا، فلا يمكن أن يختلف زر عن زر.
     */
    val currentStatus: WordStatus
        get() = runCatching { WordStatus.valueOf(current?.status ?: "NEW") }
            .getOrDefault(WordStatus.NEW)
    val hasQueue: Boolean get() = queue.isNotEmpty()
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val sleepActive: Boolean get() = sleepRemainingMs > 0
}

/** الأوامر التي تنفّذها خدمة التشغيل */
interface PlaybackCommands {
    /**
     * الطابور يُمرَّر كصفوف خفيفة لا كبطاقات كاملة.
     * جلسة من خمسة آلاف كلمة يجب ألا تحمل خمسة آلاف بطاقة مفكوكة في الذاكرة —
     * البطاقة تُقرأ من قاعدة البيانات لحظة بناء صوتها فقط.
     */
    fun setQueue(rows: List<PlayItem>, startIndex: Int, scope: PlayScope, autoPlay: Boolean)
    fun playPause()
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun jumpTo(index: Int)
    fun seekTo(ms: Long)
    /** تقديم أو ترجيع نسبي — موجب للأمام وسالب للخلف */
    fun seekBy(deltaMs: Long)
    fun stopPlayback()
    fun setSpeed(v: Float)
    fun setDetailed(v: Boolean)
    fun setShuffle(v: Boolean)
    fun setWordRepeat(v: Int)
    fun setListRepeat(v: Int)
    fun setSleepTimer(minutes: Int)
    /**
     * ينطق بطاقة واحدة ويتوقف بعدها.
     * @param full شرح كامل بكل الأقسام، أو مختصر يقتصر على المعاني.
     */
    fun speakCard(word: Word, full: Boolean = true)
    fun playPronunciation(url: String, fallbackWord: String, british: Boolean)
    /** ينطق نصاً مفرداً — يخدم زر النطق العربي وأي قراءة سريعة */
    fun speakText(text: String, arabic: Boolean)

    /**
     * يزيل عنصراً من الجلسة الجارية فور حذفه من مصدره.
     *
     * بدونها تبقى الكلمة المحذوفة في المشغّل تُسمع وتُعرض كأنها موجودة —
     * والمستخدم الذي حذفها للتوّ يقرأ ذلك خللاً بحق: القوائم يجب أن تكون
     * مرآة واحدة للمكتبة لا نسخاً تتباعد.
     */
    fun removeFromSession(id: Long)
}

/**
 * جسر بين الواجهة وخدمة التشغيل.
 *
 * الخدمة هي المالك الوحيد للمشغّل وللطابور؛ هذا الكائن مرآة للحالة وقناة أوامر.
 * أي أمر يصل قبل جهوزية الخدمة يُحفظ ويُنفَّذ فور ارتباطها، فلا تضيع ضغطة زر.
 */
object PlaybackBus {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    @Volatile internal var commands: PlaybackCommands? = null
    private val pending = ArrayDeque<(PlaybackCommands) -> Unit>()

    internal fun update(block: (PlaybackUiState) -> PlaybackUiState) = _state.update(block)

    internal fun attach(c: PlaybackCommands) {
        synchronized(pending) {
            commands = c
            _state.update { it.copy(connected = true) }
            while (pending.isNotEmpty()) pending.removeFirst()(c)
        }
    }

    internal fun detach() {
        synchronized(pending) {
            commands = null
            _state.update { it.copy(connected = false, isPlaying = false, preparing = false) }
        }
    }

    fun submit(context: Context, action: (PlaybackCommands) -> Unit) {
        val c = commands
        if (c != null) { action(c); return }
        synchronized(pending) {
            if (pending.size > 8) pending.removeFirst()
            pending += action
        }
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, PlaybackService::class.java)
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** حالة عرض محلية — لا تمرّ بالخدمة لأنها لا تؤثر على الصوت إطلاقاً */
    fun toggleTranslation() = _state.update { it.copy(showTranslation = !it.showTranslation) }

    /** يحدّث علامة المفضّلة فوراً بعد تبديلها من الواجهة */
    fun markFavorite(id: Long, favorite: Boolean) = _state.update { s ->
        s.copy(queue = s.queue.map { if (it.id == id) it.copy(favorite = favorite) else it })
    }

    /** يعكس تصنيف الكلمة فوراً بلا انتظار دورة قاعدة البيانات */
    fun markStatus(id: Long, status: String) = _state.update { s ->
        s.copy(queue = s.queue.map { if (it.id == id) it.copy(status = status) else it })
    }
}
