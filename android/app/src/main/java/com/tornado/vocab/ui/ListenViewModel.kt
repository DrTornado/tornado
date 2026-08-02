package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.PlayScope
import com.tornado.vocab.audio.PlaybackBus
import com.tornado.vocab.audio.PlaybackUiState
import com.tornado.vocab.data.LibraryStats
import com.tornado.vocab.data.ListRepeat
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.WordRow
import com.tornado.vocab.data.toPlayItem
import com.tornado.vocab.data.WordStatus
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * مصدر قائمة التشغيل.
 * كل قائمة في التطبيق قابلة للتشغيل بنفس الطريقة — لا فرق بين مرشّح
 * ونتيجة بحث ومفضّلة: كلها طوابير متساوية الحقوق.
 */
data class PlaylistSource(
    val key: String,
    val label: String,
    val status: WordStatus? = null,
    val favOnly: Boolean = false
)

class ListenViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.tornado.repository

    private val _source = MutableStateFlow(PlaylistSource("all", "All words"))
    val source: StateFlow<PlaylistSource> = _source.asStateFlow()

    private val _scope = MutableStateFlow(PlayScope.LIST)
    val scope: StateFlow<PlayScope> = _scope.asStateFlow()

    private val _loadedPlaylist = MutableStateFlow<List<WordRow>>(emptyList())
    val loadedPlaylist: StateFlow<List<WordRow>> = _loadedPlaylist.asStateFlow()

    val playback: StateFlow<PlaybackUiState> = PlaybackBus.state

    val stats: StateFlow<LibraryStats> = repo.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats())

    init {
        reload()
        /*
         * القائمة تتبع المكتبة لا تلتقط صورة منها.
         *
         * كانت تُحمَّل مرة واحدة، بينما شرائح العدّ فوقها تراقب قاعدة البيانات
         * حيّاً. فإذا أضافت المزامنة كلمة تغيّر رقم الشريحة وحده وبقيت القائمة
         * على حالها — «All ١١٦» فوق «١١٥ words» في شاشة واحدة، والمستخدم يقرأ
         * التناقض خللاً بحق.
         *
         * مراقبة الإحصاءات تكفي كإشارة: تتغيّر كلما تغيّر عدد الكلمات، فنُعيد
         * التحميل عندها لا في كل لحظة.
         */
        viewModelScope.launch {
            stats.map { it.total }.distinctUntilChanged().collect { reload() }
        }
    }

    fun setSource(s: PlaylistSource) { _source.value = s; reload() }
    fun setScope(s: PlayScope) { _scope.value = s }

    private fun reload() = viewModelScope.launch {
        val s = _source.value
        _loadedPlaylist.value = repo.playlistRows(s.status, s.favOnly)
    }

    // ===== تشغيل أي قائمة =====

    /** يشغّل أي مجموعة كلمات جاهزة — الطريق الموحّد لكل القوائم في التطبيق */
    fun playWords(words: List<WordRow>, startIndex: Int = 0) {
        if (words.isEmpty()) return
        PlaybackBus.submit(getApplication()) {
            it.setQueue(words.map { r -> r.toPlayItem() }, startIndex.coerceIn(0, words.lastIndex), _scope.value, autoPlay = true)
        }
    }

    fun playFrom(index: Int) = playWords(_loadedPlaylist.value, index)
    fun playAll() = playFrom(0)

    fun togglePlay() {
        if (!playback.value.hasQueue) { playAll(); return }
        PlaybackBus.submit(getApplication()) { it.playPause() }
    }

    fun next() = PlaybackBus.submit(getApplication()) { it.next() }
    fun previous() = PlaybackBus.submit(getApplication()) { it.previous() }
    fun jumpTo(i: Int) = PlaybackBus.submit(getApplication()) { it.jumpTo(i) }
    fun seekTo(ms: Long) = PlaybackBus.submit(getApplication()) { it.seekTo(ms) }
    fun stop() = PlaybackBus.submit(getApplication()) { it.stopPlayback() }

    fun rewind() = PlaybackBus.submit(getApplication()) { it.seekBy(-10_000) }
    fun fastForward() = PlaybackBus.submit(getApplication()) { it.seekBy(10_000) }

    fun setSpeed(v: Float) = PlaybackBus.submit(getApplication()) { it.setSpeed(v) }
    fun setSleepTimer(minutes: Int) = PlaybackBus.submit(getApplication()) { it.setSleepTimer(minutes) }

    fun toggleDetail() =
        PlaybackBus.submit(getApplication()) { it.setDetailed(!playback.value.detailed) }

    fun toggleShuffle() =
        PlaybackBus.submit(getApplication()) { it.setShuffle(!playback.value.shuffle) }

    /** عدد مرات نطق شرح الكلمة — يعيد المشغّل بناء الجلسة لأنه يغيّر الصوت نفسه */
    fun setWordRepeat(times: Int) =
        PlaybackBus.submit(getApplication()) { it.setWordRepeat(times.coerceIn(1, 10)) }

    fun cycleListRepeat() {
        val opts = ListRepeat.OPTIONS
        val cur = playback.value.listRepeat
        val next = opts[(opts.indexOf(cur).takeIf { it >= 0 }?.plus(1) ?: 1) % opts.size]
        PlaybackBus.submit(getApplication()) { it.setListRepeat(next) }
    }

    fun dismissMessage() = PlaybackBus.consumeMessage()

    /** إظهار المعنى أو إخفاؤه — تدريب الاسترجاع يحتاج إخفاءه أحياناً */
    fun toggleTranslation() = PlaybackBus.toggleTranslation()

    /**
     * الزر الدوّار في أعلى المشغّل: معروفة ← أخطأت ← جديدة.
     * يستدعي setStatus نفسها التي تستدعيها الأزرار الثلاثة، فالقيمة واحدة
     * ولا يمكن أن يفترق الزران عن بعضهما مهما تعاقب الضغط.
     */
    fun cycleStatus() {
        val next = when (playback.value.currentStatus) {
            WordStatus.NEW -> WordStatus.KNOWN
            WordStatus.KNOWN -> WordStatus.MISSED
            WordStatus.MISSED -> WordStatus.NEW
        }
        setStatus(next)
    }

    /** تصنيف الكلمة الحالية بلون — ينعكس فوراً على القوائم والمرشّحات */
    fun setStatus(status: WordStatus) = viewModelScope.launch {
        val id = playback.value.current?.id ?: return@launch
        repo.setStatus(id, status)
        PlaybackBus.markStatus(id, status.name)
        reload()
    }

    fun toggleFavoriteCurrent() = viewModelScope.launch {
        val item = playback.value.current ?: return@launch
        val next = !item.favorite
        repo.toggleFavorite(item.id, next)
        PlaybackBus.markFavorite(item.id, next)
    }

    /** ينطق المعنى العربي للبطاقة الحالية — ضغطة واعية تسمح بالتوليد */
    fun speakArabicCurrent() = viewModelScope.launch {
        val id = playback.value.current?.id ?: return@launch
        val w = repo.word(id) ?: return@launch
        val text = w.meanings.firstOrNull()?.ar?.takeIf { it.isNotBlank() }
            ?: w.arabicPron.takeIf { it.isNotBlank() }
            ?: return@launch
        PlaybackBus.submit(getApplication()) { it.speakText(text, arabic = true) }
    }
}
