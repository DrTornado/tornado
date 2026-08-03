package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.PlayScope
import com.tornado.vocab.audio.PlaybackBus
import com.tornado.vocab.tornado
import com.tornado.vocab.data.LibraryStats
import com.tornado.vocab.data.SortOrder
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.WordRow
import com.tornado.vocab.data.toPlayItem
import com.tornado.vocab.data.WordStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryFilters(
    val query: String = "",
    val status: WordStatus? = null,
    val favOnly: Boolean = false,
    val sort: SortOrder = SortOrder.ALPHA
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.tornado.repository

    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters.asStateFlow()

    val stats: StateFlow<LibraryStats> = repo.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats())

    /**
     * كم بطاقة ما زالت تُكمَّل بالخلفية.
     *
     * القياس كل نصف دقيقة لا لحظياً: العدد يتناقص على مدى أيام بمعدّل ثماني
     * كلمات في كل فتحة، ومراقبته لحظياً استعلامٌ متكرر بلا ما يقابله على
     * الشاشة.
     */
    val pendingGaps: StateFlow<Int> = flow {
        while (true) {
            emit(runCatching { app.tornado.enricher.pendingCount() }.getOrDefault(0))
            kotlinx.coroutines.delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * تهدئة ٢٠٠ ملي ثانية على نص البحث وحده.
     * تغيير المرشّح أو الترتيب يسري فوراً لأنه ضغطة واحدة لا كتابة متتابعة.
     */
    val rows: StateFlow<List<WordRow>> = _filters
        .debounce { if (it.query.isBlank()) 0 else 200 }
        .distinctUntilChanged()
        .flatMapLatest { f ->
            if (f.query.isBlank()) repo.rows(f.status, f.favOnly, f.sort)
            else repo.search(f.query, f.status, f.favOnly, f.sort)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * الكلمات المفتوحة داخل القائمة.
     * التوسيع في المكان بدل الدخول لشاشة والعودة منها: يفتح المستخدم كلمة،
     * يقرأها، يمرّر لأسفل ويفتح التالية مباشرة — بلا أي انتقال بين الشاشات.
     */
    private val _expanded = MutableStateFlow<Set<Long>>(emptySet())
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    private val _expandedWords = MutableStateFlow<Map<Long, Word>>(emptyMap())
    val expandedWords: StateFlow<Map<Long, Word>> = _expandedWords.asStateFlow()

    fun toggleExpanded(id: Long) {
        val now = _expanded.value
        if (now.contains(id)) {
            _expanded.value = now - id
        } else {
            _expanded.value = now + id
            if (!_expandedWords.value.containsKey(id)) {
                viewModelScope.launch {
                    repo.word(id)?.let { w ->
                        _expandedWords.value = _expandedWords.value + (id to w)
                    }
                }
            }
        }
    }

    fun collapseAll() { _expanded.value = emptySet() }

    fun expandAll() {
        val ids = rows.value.map { it.id }
        _expanded.value = ids.toSet()
        viewModelScope.launch {
            val missing = ids.filterNot { _expandedWords.value.containsKey(it) }
            if (missing.isEmpty()) return@launch
            val loaded = missing.mapNotNull { repo.word(it) }.associateBy { it.id }
            _expandedWords.value = _expandedWords.value + loaded
        }
    }

    fun setQuery(q: String) = _filters.update { it.copy(query = q) }
    fun setStatus(s: WordStatus?) = _filters.update { it.copy(status = s) }
    fun toggleFavOnly() = _filters.update { it.copy(favOnly = !it.favOnly) }
    fun setSort(s: SortOrder) = _filters.update { it.copy(sort = s) }
    fun clearQuery() = _filters.update { it.copy(query = "") }

    fun toggleFavorite(row: WordRow) = viewModelScope.launch {
        repo.toggleFavorite(row.id, !row.favorite)
    }

    /**
     * الحذف يصل إلى الكمبيوتر أيضاً.
     *
     * كلمة تُحذف هنا وتبقى هناك تعود في المزامنة التالية، فيبدو للمستخدم أن
     * الحذف لا يعمل. الشاهدة تُرفع مع الحذف فيعرف الطرف الآخر أنه حذف متعمَّد
     * لا نقص في البيانات.
     */
    fun delete(row: WordRow) = viewModelScope.launch {
        repo.delete(row)
        // المشغّل يعرف بالحذف فوراً — لا كلمة شبح في جلسة جارية
        PlaybackBus.submit(getApplication()) { it.removeFromSession(row.id) }
        runCatching {
            val app = getApplication<android.app.Application>().tornado
            app.sync.repo = app.settings.syncRepo()
            if (app.sync.canPush) app.sync.sync(push = true)
        }
    }

    /**
     * يشغّل الكلمات المعروضة حالياً — ما يراه المستخدم هو ما يسمعه.
     * الصفوف المعروضة تُمرَّر كما هي: لا حاجة لقراءة بطاقات كاملة هنا إطلاقاً.
     */
    fun playVisible(startId: Long?) {
        val visible = rows.value
        if (visible.isEmpty()) return
        val start = startId?.let { id -> visible.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
        PlaybackBus.submit(getApplication()) {
            it.setQueue(visible.map { r -> r.toPlayItem() }, start, PlayScope.LIST, autoPlay = true)
        }
    }

    fun speakCard(id: Long) = viewModelScope.launch {
        val w: Word = repo.word(id) ?: return@launch
        PlaybackBus.submit(getApplication()) { it.speakCard(w) }
    }

    /** نطق الكلمة بلكنة محددة — التسجيل البشري أولاً داخل الخدمة */
    fun pronounce(w: Word, british: Boolean) {
        val url = if (british) w.audioUK else w.bestUsAudio
        PlaybackBus.submit(getApplication()) { it.playPronunciation(url, w.word, british) }
    }

    /** ينطق المعنى العربي — لا تسجيلات بشرية للعربية، فهذه ضغطة واعية تسمح بالتوليد */
    fun speakArabic(w: Word) {
        val text = w.meanings.firstOrNull()?.ar?.takeIf { it.isNotBlank() }
            ?: w.arabicPron.takeIf { it.isNotBlank() }
            ?: return
        PlaybackBus.submit(getApplication()) { it.speakText(text, arabic = true) }
    }

    private fun <T> MutableStateFlow<T>.update(block: (T) -> T) { value = block(value) }
}
