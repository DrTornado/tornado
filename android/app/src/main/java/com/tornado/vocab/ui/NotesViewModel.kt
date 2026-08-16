package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.PlayScope
import com.tornado.vocab.audio.PlaybackBus
import com.tornado.vocab.audio.PlaybackUiState
import com.tornado.vocab.data.Note
import com.tornado.vocab.data.NoteChunker
import com.tornado.vocab.data.NoteRow
import com.tornado.vocab.data.PlayItem
import com.tornado.vocab.data.RowKind
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotesUi(
    val busy: Boolean = false,
    val message: String? = null,
    val openNote: Note? = null
)

/**
 * الملاحظات الصوتية.
 *
 * الفكرة أن ما يصلح للكلمات يصلح للنصوص الطويلة: نفس المشغّل، ونفس الخلفية،
 * ونفس الإشعار، ونفس مؤقّت النوم والسرعة. الفارق الوحيد أن المحتوى نصّ يُقرأ
 * من أوّله لا بطاقة تُراجَع — فلا تصنيف ولا جدولة ولا اختبار.
 */
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val notes = app.tornado.notes

    val rows: StateFlow<List<NoteRow>> =
        notes.rows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackUiState> = PlaybackBus.state

    private val _ui = MutableStateFlow(NotesUi())
    val ui: StateFlow<NotesUi> = _ui.asStateFlow()

    fun add(text: String, title: String? = null) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true)
        val note = notes.add(text, title)
        _ui.value = _ui.value.copy(
            busy = false,
            message = if (note == null) "Paste a longer text first"
            else "Added \"${note.title}\" · ${note.chunkCount} parts"
        )
        if (note != null) pushToComputer()
    }

    fun delete(id: Long) = viewModelScope.launch {
        notes.delete(id)
        // الملاحظة المحذوفة تخرج من المشغّل أيضاً إن كانت تُسمع الآن
        PlaybackBus.submit(getApplication()) { it.removeFromSession(id) }
        pushToComputer()
    }

    fun toggleFavorite(row: NoteRow) = viewModelScope.launch {
        notes.setFavorite(row.id, !row.favorite)
    }

    fun open(id: Long) = viewModelScope.launch {
        _ui.value = _ui.value.copy(openNote = notes.byId(id))
    }

    fun closeOpen() { _ui.value = _ui.value.copy(openNote = null) }
    fun dismissMessage() { _ui.value = _ui.value.copy(message = null) }

    /**
     * يشغّل ملاحظة من مقطع محدّد.
     *
     * المقاطع تصير عناصر طابور مستقلة، فيتنقّل المستخدم بينها بأزرار السابق
     * والتالي كما يتنقّل بين الكلمات — ويستأنف من حيث وقف بدل العودة إلى أول
     * نصّ من ساعة.
     */
    /**
     * يشغّل كل الملاحظات بالترتيب، بادئاً بواحدة معيّنة.
     *
     * كانت كل ملاحظة جزيرة: تنتهي فيقف المشغّل، وزرّ التالي لا يجد ما بعدها
     * لأنها وحدها في الطابور. ومن يستمع وهو يمشي أو يقود لا يستطيع أن يلتقط
     * جواله عند نهاية كل نصّ.
     */
    /*
     * ===== اختيارُ ملاحظاتٍ بعينها =====
     *
     * نفس الاختيار الذي في الكلمات والاستماع. وطلبُ صاحب المكتبة أن يكون
     * في كل قائمةٍ يُشغّل منها — فالوظيفة التي تعمل في شاشةٍ وتغيب عن
     * أختها تُقرأ عطلاً لا تصميماً.
     */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun toggleSelect(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun selectAll() = viewModelScope.launch {
        _selected.value = notes.all().map { it.id }.toSet()
    }

    /** يشغّل الملاحظات المختارة وحدها */
    fun playSelected() = viewModelScope.launch {
        val ids = _selected.value
        if (ids.isEmpty()) return@launch
        _selected.value = emptySet()
        playAll(only = ids)
    }

    fun playAll(startId: Long? = null, only: Set<Long>? = null) = viewModelScope.launch {
        val all = notes.all().sortedByDescending { it.updatedAt }
            .let { list -> if (only.isNullOrEmpty()) list else list.filter { it.id in only } }
        /*
         * التكرار يُبنى في الطابور لا في الملف.
         *
         * Say ×٢ تعني أن الجملة تدخل الطابور مرتين وتُقرأ من نفس الملف
         * المخزَّن — لا توليد ثانياً ولا انتظار. وهذا ما طلبه المستخدم
         * حرفياً بعد أن رأى كل تبديل يعيد البناء من الصفر.
         */
        val audio = runCatching { getApplication<Application>().tornado.settings.audio.first() }.getOrNull()
        val times = (audio?.wordRepeat ?: 1).coerceIn(1, 10)
        // FULL يعني الفقرة وحدةً للتكرار، وSHORT يعني الجملة — كما طلب المستخدم
        val byParagraph = audio?.detailed ?: false

        val items = all.flatMap { note ->
            val chunks = NoteChunker.units(note.text, byParagraph)
            chunks.flatMapIndexed { i: Int, text: String ->
                List(times) {
                    PlayItem(
                        id = note.id,
                        // الاسم كما سمّاه صاحبه: رقم الجملة تفصيل داخلي لا عنوان
                        title = note.title,
                        subtitle = text.take(70),
                        kind = RowKind.NOTE_CHUNK,
                        chunkIndex = i,
                        favorite = note.favorite
                    )
                }
            }
        }
        if (items.isEmpty()) {
            _ui.value = _ui.value.copy(message = "No notes to play yet")
            return@launch
        }
        val start = startId?.let { id -> items.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: 0
        PlaybackBus.submit(getApplication()) {
            it.setQueue(items, start, PlayScope.LIST, autoPlay = true)
        }
    }

    fun play(id: Long, fromChunk: Int? = null) = viewModelScope.launch {
        val note = notes.byId(id) ?: return@launch
        val chunks = NoteChunker.sentences(note.text)
        if (chunks.isEmpty()) {
            _ui.value = _ui.value.copy(message = "This note has no readable text")
            return@launch
        }
        val items = chunks.mapIndexed { i, text ->
            PlayItem(
                id = note.id,
                title = note.title,
                subtitle = text.take(70),
                kind = RowKind.NOTE_CHUNK,
                chunkIndex = i,
                favorite = note.favorite
            )
        }
        val start = (fromChunk ?: note.lastChunk).coerceIn(0, items.lastIndex)
        PlaybackBus.submit(getApplication()) {
            it.setQueue(items, start, PlayScope.LIST, autoPlay = true)
        }
    }

    /** يحفظ موضع الاستماع — نصّ طويل يُستأنف لا يُعاد من أوّله */
    fun rememberPosition(id: Long, chunk: Int) = viewModelScope.launch {
        notes.setLastChunk(id, chunk)
    }

    /**
     * إضافة نوتة أو حذفها تُطلق المزامنة الشاملة، لا رفع النوتات وحده.
     *
     * كانت ترفع الملاحظات فقط، فتصل النوتة إلى الكمبيوتر ولا يجري شيء آخر:
     * لا تُسحب كلمةٌ أضيفت هناك، ولا يعمل الإثراء. والمستخدم يقرأ «مزامنة»
     * كلمةً واحدة تعني كل شيء، لا نصفه.
     */
    private fun pushToComputer() {
        val app = getApplication<Application>()
        app.tornado.appScope.launch {
            runCatching { com.tornado.vocab.data.SyncCoordinator.syncNow(app) }
        }
    }
}
