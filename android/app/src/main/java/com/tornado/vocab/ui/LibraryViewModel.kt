package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.PlayScope
import com.tornado.vocab.audio.PlaybackBus
import com.tornado.vocab.tornado
import com.tornado.vocab.data.DisplayCard
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    private val container = app.tornado

    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters.asStateFlow()

    val stats: StateFlow<LibraryStats> = repo.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats())

    /*
     * حُذف عدّاد «يُكمَّل بالخلفية» مع القاموس الآليّ الذي كان يعدّه.
     *
     * ولا بديل له: الطابور المعروض أصدق منه — يقول أيّ كلمةٍ تنتظر بطاقتها
     * بالاسم، لا رقماً يتناقص بلا أن يُعرف ما وراءه.
     */

    /**
     * طابور البطاقات: الكلمات التي لم تُكتب بطاقتها المراجَعة بعد.
     *
     * البطاقات تُكتب بيد لا بمسارٍ تلقائي، فالكلمة الجديدة تبقى بلا بطاقةٍ
     * كاملة حتى تُكتب. وإخفاء ذلك هو ما جعل صاحب المكتبة يظنّ البطاقة الفقيرة
     * سقفَ ما نقدر عليه. فالطابور معروض: كم كلمة، وأيّها.
     *
     * ويتتبّع القاعدة لا الساعة: `curatedWords` تدفق من Room، فأول مزامنة
     * تُنقص العدد في اللحظة نفسها بلا استطلاع.
     */
    val cardQueue: StateFlow<List<String>> = combine(
        repo.rows(null, favOnly = false, sort = SortOrder.ALPHA),
        container.enrichSync.curatedWords()
    ) { all, written ->
        val have = written.toHashSet()
        all.map { it.word }.filter { it.trim().lowercase() !in have }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * مستويات البطاقات المكتوبة — لشارة صفّ القائمة.
     *
     * الصفّ مشروعٌ خفيف من جدول الكلمات لا يمرّ بنقطة الدمج، فكان يعرض
     * مستوى القاموس القديم بينما تعرض البطاقة المفتوحة تحته المستوى المكتوب.
     */
    val levels: StateFlow<Map<String, Pair<String, Boolean>>> =
        container.enrichSync.curatedLevels()
            .map { list -> list.associate { it.word to (it.level to it.levelExact) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    private val _expandedWords = MutableStateFlow<Map<Long, DisplayCard>>(emptyMap())
    val expandedWords: StateFlow<Map<Long, DisplayCard>> = _expandedWords.asStateFlow()

    fun toggleExpanded(id: Long) {
        val now = _expanded.value
        if (now.contains(id)) {
            _expanded.value = now - id
        } else {
            _expanded.value = now + id
            /*
             * تُقرأ في كل فتحة، ولا تُحفظ مرّةً واحدة إلى الأبد.
             *
             * كان الشرط `if (!containsKey(id))`، فالبطاقة التي فُتحت مرّةً
             * تبقى في الذاكرة كما قُرئت أوّل مرّة. فيضغط المستخدم Sync وتصل
             * البطاقة المصحَّحة إلى القاعدة، ثم يفتحها فيرى القديمة — ويظنّ
             * المزامنة لم تعمل. رأيتها بعيني: صحّحتُ سطراً ودفعته، وزامن
             * الجهاز، وبقي السطر القديم حتى أُغلق التطبيق وفُتح.
             *
             * والقراءة من Room رخيصة، والنسخة القديمة تبقى معروضة ريثما
             * تصل الجديدة — فلا دوّارة انتظارٍ ولا وميض.
             */
            viewModelScope.launch {
                // البطاقة المدموجة لا الخام — القائمة هي ما ينظر فيه المستخدم فعلاً
                container.cards.full(id)?.let { c ->
                    _expandedWords.value = _expandedWords.value + (id to c)
                }
            }
        }
    }

    fun collapseAll() { _expanded.value = emptySet() }

    fun expandAll() {
        val ids = rows.value.map { it.id }
        _expanded.value = ids.toSet()
        viewModelScope.launch {
            // كلّها تُقرأ من جديد — لا تُستثنى المحفوظة، فقد تكون تغيّرت بمزامنة
            val loaded = ids.mapNotNull { container.cards.full(it) }.associateBy { it.word.id }
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
        // الحذف يزامن في الاتجاهين: ترفع شاهدته وتُسحب تعديلات الجهاز الآخر
        runCatching { com.tornado.vocab.data.SyncCoordinator.syncNow(getApplication()) }
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
        // يُنطق ما يُقرأ — لا الخام الذي بناه التطبيق لنفسه
        val w: Word = container.cards.card(id) ?: return@launch
        PlaybackBus.submit(getApplication()) { it.speakCard(w) }
    }

    /** نطق الكلمة بلكنة محددة — التسجيل البشري أولاً داخل الخدمة */
    fun pronounce(w: Word, british: Boolean) {
        val url = if (british) w.audioUK else w.bestUsAudio
        PlaybackBus.submit(getApplication()) { it.playPronunciation(url, w.word, british) }
    }

    /**
     * زرّ «عربي» — يعمل لكل كلمة، ولا يصمت بلا سبب مُعلَن.
     *
     * كان يقرأ `arabicPron` حين لا يجد معنى عربياً، وذاك نطقٌ لا معنى: تضغط
     * «عربي» على `glacier` فتسمع «جليشير» بدل «نهر جليدي». الزرّ يبدو عاملاً
     * وهو يكذب — وسبعُ كلمات في المكتبة كانت كذلك.
     *
     * وإن لم يوجد معنى عربي أصلاً كان يخرج صامتاً بلا رسالة، فلا يعرف
     * المستخدم أعُطِل الزرّ أم عُطِل الصوت. الآن يُترجَم المعنى في اللحظة
     * ويُحفَظ ويُرفع مع المزامنة، فتعمل الكلمة مرّةً بانتظار ثوانٍ وكل مرّة
     * بعدها فوراً — ويسمعها كروم أيضاً.
     */
    /*
     * يقرأ المدموج ويكتب في الخام.
     *
     * لو قرأ الخام لترجم معنىً قديماً بينما في البطاقة المكتوبة عربيّةٌ
     * أدقّ منه. ولو كتب المدموج لانتقل الإثراء إلى بيانات المستخدم ثم رُفع
     * إلى المستودع — والإثراء نسخةٌ للعرض لا مِلكٌ للبطاقة.
     */
    fun speakArabic(id: Long) = viewModelScope.launch {
        val shown = container.cards.card(id) ?: return@launch
        val ready = shown.meanings.firstOrNull { it.ar.isNotBlank() }?.ar
        if (ready != null) {
            PlaybackBus.submit(getApplication()) { it.speakText(ready, arabic = true) }
            return@launch
        }

        val source = shown.meanings.firstOrNull { it.en.isNotBlank() }?.en
        if (source.isNullOrBlank()) {
            _notice.value = "لا يوجد معنى لهذه الكلمة بعد — اضغط Sync"
            return@launch
        }

        _notice.value = "جارٍ جلب المعنى بالعربية…"
        val ar = runCatching { container.dictionary.arabicFor(source) }.getOrDefault("")
        if (ar.isBlank()) {
            _notice.value = "تعذّرت الترجمة الآن — تحقّق من الاتصال وأعد المحاولة"
            return@launch
        }

        // نحفظه في البطاقة فلا يُطلب مرّتين، ويسافر مع المزامنة إلى بقية الأجهزة
        val raw = repo.word(id)   // RAW-OK: الكتابة تقع على الخام لا على المدموج
        if (raw != null) {
            val at = raw.meanings.indexOfFirst { it.en == source }
            if (at >= 0) {
                val updated = raw.copy(
                    meanings = raw.meanings.mapIndexed { i, m ->
                        if (i == at) m.copy(ar = ar) else m
                    }
                )
                runCatching { repo.update(updated) }
            }
        }
        _notice.value = null
        PlaybackBus.submit(getApplication()) { it.speakText(ar, arabic = true) }
        runCatching { com.tornado.vocab.data.SyncCoordinator.syncNow(getApplication()) }
    }

    /** رسالة قصيرة تُعرض للمستخدم — الصمت بلا سبب أسوأ من التأخير */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    private fun <T> MutableStateFlow<T>.update(block: (T) -> T) { value = block(value) }
}
