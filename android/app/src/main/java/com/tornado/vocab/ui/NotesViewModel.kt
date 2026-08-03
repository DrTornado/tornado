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
    fun playAll(startId: Long? = null) = viewModelScope.launch {
        val all = notes.all().sortedByDescending { it.updatedAt }
        val items = all.flatMap { note ->
            val chunks = NoteChunker.split(note.text)
            chunks.mapIndexed { i, text ->
                PlayItem(
                    id = note.id,
                    title = if (chunks.size == 1) note.title else "${note.title} · ${i + 1}",
                    subtitle = text.take(70),
                    kind = RowKind.NOTE_CHUNK,
                    chunkIndex = i,
                    favorite = note.favorite
                )
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
        val chunks = NoteChunker.split(note.text)
        if (chunks.isEmpty()) {
            _ui.value = _ui.value.copy(message = "This note has no readable text")
            return@launch
        }
        val items = chunks.mapIndexed { i, text ->
            PlayItem(
                id = note.id,
                title = if (chunks.size == 1) note.title else "${note.title} · ${i + 1}",
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

    private fun pushToComputer() {
        val container = getApplication<Application>().tornado
        container.appScope.launch {
            runCatching {
                container.noteSync.repo = container.settings.syncRepo()
                if (container.noteSync.canPush) container.noteSync.sync()
            }
        }
    }
}
