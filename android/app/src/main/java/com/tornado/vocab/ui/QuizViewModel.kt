package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.PlaybackBus
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.WordStatus
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class QuizState(
    val queue: List<Word> = emptyList(),
    val index: Int = 0,
    val position: Int = 1,
    val flipped: Boolean = false,
    val answered: Int = 0,
    val correct: Int = 0,
    val loading: Boolean = true,
    val finished: Boolean = false,
    val canUndo: Boolean = false,
    val notice: String? = null,
    val dueCount: Int = 0,
    /**
     * جولة تدريب لا مراجعة.
     *
     * تقع حين لا تستحق أي كلمة اليوم، فنعرض الكل بدل شاشة فارغة. والفرق
     * جوهري لا تجميلي: إجابات التدريب لا تحرّك مواعيد المراجعة.
     */
    val freePractice: Boolean = false,
    /** كم بطاقة أُجّلت بالسحب — تدخل العدّاد لأنها حركة فعلية في الجولة */
    val deferredCount: Int = 0
) {
    val current: Word? get() = queue.getOrNull(index)
    val total: Int get() = queue.size

    /**
     * موضع البطاقة المعروضة.
     *
     * إصداران خاطئان سبقا هذا: عدّاد حرّ يتسلّق مع كل سحبة بلا سقف
     * («١٧٠ من ٤٠»)، ثم عدّاد مربوط بالإجابات وحدها فيتجمّد على «١» مهما
     * سحب المستخدم. الصواب بينهما: الإجابة والتأجيل كلاهما حركة تتقدّم
     * بالعدّاد، والسقف حجم الجولة فلا يتجاوزه شيء.
     */
    val shown: Int get() = (answered + deferredCount + 1).coerceAtMost(total.coerceAtLeast(1))
    val accuracy: Int get() = if (answered == 0) 0 else (correct * 100 / answered)
}

/** لقطة للتراجع — تعيد الطابور والتقدّم معاً */
private data class Snapshot(
    val queue: List<Word>,
    val index: Int,
    val position: Int,
    val answered: Int,
    val correct: Int,
    val word: Word?
)

class QuizViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.tornado.repository
    private val settings = app.tornado.settings

    private val _state = MutableStateFlow(QuizState())
    val state: StateFlow<QuizState> = _state.asStateFlow()

    private val undo = ArrayDeque<Snapshot>()

    init { start() }

    /**
     * يبدأ جولة جديدة. المستحق اليوم أولاً؛ فإن لم يستحق شيء يتحول لتدريب حر
     * على الكل بدل إظهار شاشة فارغة — وهذا ما يبقي العادة اليومية قائمة.
     */
    fun start(statusFilter: WordStatus? = null, favOnly: Boolean = false) = viewModelScope.launch {
        _state.value = QuizState(loading = true)
        undo.clear()
        val prefs = settings.app.first()
        val due = repo.reviewQueue(true, statusFilter, favOnly, prefs.quizLimit)
        val queue = due.ifEmpty { repo.reviewQueue(false, statusFilter, favOnly, prefs.quizLimit) }
        val free = due.isEmpty() && queue.isNotEmpty()
        _state.value = QuizState(
            queue = queue,
            loading = false,
            finished = queue.isEmpty(),
            dueCount = due.size,
            freePractice = free,
            notice = when {
                queue.isEmpty() -> "No words yet — add some first"
                // نقول صراحةً إن التدريب لا يحرّك المواعيد، وإلا ظنّه المستخدم مراجعة
                free -> "Nothing due today — free practice, review dates stay as they are"
                else -> "${due.size} card${if (due.size > 1) "s" else ""} due for review"
            }
        )
        librarySize = repo.count()
    }

    /**
     * حجم المكتبة وقت بناء الجولة.
     *
     * الجولة لقطة، والمكتبة تتغيّر تحتها: تُضاف كلمة فلا تظهر، أو تُحذف فتبقى
     * معروضة وقد تُحذف من تحت المستخدم أثناء إجابته. مقارنة العدد عند العودة
     * للتبويب تكشف ذلك بلا مراقبة مستمرة تُعيد بناء الجولة في منتصفها.
     */
    private var librarySize = -1

    /** يُستدعى عند العودة إلى التبويب — يعيد البناء إن تغيّرت المكتبة فقط */
    fun refreshIfLibraryChanged() = viewModelScope.launch {
        val s = _state.value
        if (s.loading) return@launch
        val now = repo.count()
        // لا نقطع جولة جارية أجاب فيها المستخدم؛ نعيد البناء عند نهايتها أو بدايتها
        if (now != librarySize && (s.answered == 0 || s.finished)) start()
        else librarySize = now
    }

    fun flip() = _state.value.let { s ->
        if (s.current != null) _state.value = s.copy(flipped = !s.flipped)
    }

    fun answer(knew: Boolean) {
        val s = _state.value
        val word = s.current ?: return
        pushUndo(word)
        viewModelScope.launch {
            /*
             * الجدولة تتحرّك في المراجعة وحدها.
             *
             * التدريب الحرّ يجري على كلمات غير مستحقة، فاحتساب إجابته كمراجعة
             * يضاعف فواصلها بلا وجه حق ويدفعها إلى شهور. العدّاد يُحدَّث في
             * الحالتين لأنك أجبتَ فعلاً، والموعد لا يُمسّ إلا حين يحين.
             */
            if (s.freePractice) repo.recordAnswerOnly(word, knew) else repo.answer(word, knew)
            settings.recordReview()
        }
        advance(s, knew)
    }

    private fun advance(s: QuizState, knew: Boolean) {
        val nextIndex = s.index + 1
        _state.value = s.copy(
            index = nextIndex,
            position = s.position + 1,
            flipped = false,
            answered = s.answered + 1,
            correct = s.correct + if (knew) 1 else 0,
            finished = nextIndex >= s.queue.size,
            canUndo = true,
            notice = null
        )
    }

    /**
     * تخطٍّ بلا تسجيل صح أو خطأ — الكلمة تعود لآخر الجولة الحالية.
     * هذا يحترم الحالة التي لا يكون فيها المتعلّم مستعداً للحكم بعد.
     */
    fun skip() {
        val s = _state.value
        val word = s.current ?: return
        pushUndo(word)

        /*
         * التخطّي يؤجّل الكلمة مرة واحدة لا إلى الأبد.
         *
         * إعادتها لآخر الطابور بلا حدّ كانت تجعل الجولة دائرة لا تنتهي: يتخطّى
         * المستخدم فتعود، ويتخطّى فتعود، والعدّاد يتسلّق بلا سقف والجولة لا
         * تُغلق أبداً. التأجيل مرة واحدة يحترم «لست مستعداً الآن» ويُبقي للجولة
         * نهاية.
         */
        val alreadyDeferred = word.id in deferred
        val newQueue = s.queue.toMutableList()
        val moved = newQueue.removeAt(s.index)
        if (!alreadyDeferred) {
            deferred += word.id
            newQueue.add(moved)
            _state.value = s.copy(
                queue = newQueue, flipped = false, canUndo = true, notice = null,
                deferredCount = s.deferredCount + 1
            )
        } else {
            // تُخطّيت مرتين: تخرج من الجولة بلا تسجيل حكم عليها
            _state.value = s.copy(
                queue = newQueue,
                flipped = false,
                canUndo = true,
                finished = s.index >= newQueue.size,
                notice = null
            )
        }
    }

    /** الكلمات التي أُجّلت مرة — تمنع الجولة من الدوران بلا نهاية */
    private val deferred = mutableSetOf<Long>()

    fun undo() = viewModelScope.launch {
        val snap = undo.removeLastOrNull() ?: return@launch
        snap.word?.let { repo.restoreProgress(it) }
        _state.value = _state.value.copy(
            queue = snap.queue,
            index = snap.index,
            position = snap.position,
            answered = snap.answered,
            correct = snap.correct,
            flipped = false,
            finished = false,
            canUndo = undo.isNotEmpty(),
            notice = null
        )
    }

    private fun pushUndo(word: Word) {
        val s = _state.value
        undo.addLast(Snapshot(s.queue, s.index, s.position, s.answered, s.correct, word))
        if (undo.size > 100) undo.removeFirst() // سقف يمنع تراكماً غير محدود
    }

    fun toggleFavorite() = viewModelScope.launch {
        val w = _state.value.current ?: return@launch
        repo.toggleFavorite(w.id, !w.favorite)
        val updated = repo.word(w.id) ?: return@launch
        _state.value = _state.value.copy(
            queue = _state.value.queue.map { if (it.id == w.id) updated else it }
        )
    }

    fun pronounce(british: Boolean) {
        val w = _state.value.current ?: return
        val url = if (british) w.audioUK else w.bestUsAudio
        PlaybackBus.submit(getApplication()) { it.playPronunciation(url, w.word, british) }
    }

    fun readFull() {
        val w = _state.value.current ?: return
        PlaybackBus.submit(getApplication()) { it.speakCard(w) }
    }

    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }
}
