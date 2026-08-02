package com.tornado.vocab.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tornado.vocab.data.Note
import com.tornado.vocab.data.NoteChunker
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NoteDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val notes = app.tornado.notes
    private val _note = MutableStateFlow<Note?>(null)
    val note: StateFlow<Note?> = _note.asStateFlow()

    fun load(id: Long) = viewModelScope.launch { _note.value = notes.byId(id) }

    /**
     * الحفظ يعيد التقسيم ويرفع للكمبيوتر.
     * تعديل النص قد يغيّر عدد المقاطع، وموضع الاستئناف القديم قد يتجاوز
     * النهاية الجديدة — فيُقصّ إلى آخر مقطع صالح بدل أن يفشل التشغيل.
     */
    fun save(title: String, text: String) = viewModelScope.launch {
        val current = _note.value ?: return@launch
        val chunks = NoteChunker.split(text)
        val updated = current.copy(
            title = title.trim().ifBlank { NoteChunker.titleFrom(text) },
            text = text.trim(),
            lastChunk = current.lastChunk.coerceAtMost((chunks.size - 1).coerceAtLeast(0))
        )
        notes.save(updated)
        _note.value = updated
        val container = getApplication<Application>().tornado
        container.appScope.launch {
            runCatching {
                container.noteSync.repo = container.settings.syncRepo()
                if (container.noteSync.canPush) container.noteSync.sync()
            }
        }
    }
}

/**
 * نصّ الملاحظة كاملاً — قراءةً وتحريراً.
 *
 * القراءة أولاً والتحرير بضغطة: من يفتح النص غالباً يريد متابعته بعينه أثناء
 * الاستماع، والتحرير الدائم يضع لوحة المفاتيح بينه وبين القراءة بلا داعٍ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: Long,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit = {}
) {
    val vm: NoteDetailViewModel = viewModel()
    LaunchedEffect(noteId) { vm.load(noteId) }
    val note by vm.note.collectAsState()

    var editing by remember { mutableStateOf(false) }
    var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var text by remember(note?.id) { mutableStateOf(note?.text.orEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        note?.title ?: "Note",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { note?.id?.let(onPlay) }) {
                        Icon(Icons.Filled.PlayArrow, "Play")
                    }
                    IconButton(onClick = {
                        if (editing) vm.save(title, text)
                        editing = !editing
                    }) {
                        Icon(
                            if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                            if (editing) "Save" else "Edit",
                            tint = if (editing) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { pad ->
        val n = note ?: return@Scaffold
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                "${n.wordCount} words · ${n.chunkCount} parts" +
                    if (n.lastChunk > 0) " · resumes at part ${n.lastChunk + 1}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            VSpace(12)

            if (editing) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                VSpace(10)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    label = { Text("Text") },
                    shape = RoundedCornerShape(12.dp)
                )
            } else {
                /*
                 * العرض بمقاطع لا كتلة واحدة.
                 *
                 * المقطع هو وحدة الاستماع، فعرضه وحدةً للقراءة يجعل المستخدم
                 * يعرف أين هو من النص حين يسمع «الجزء ٣» — الشاشة والأذن
                 * تتكلمان نفس اللغة.
                 */
                NoteChunker.split(n.text).forEachIndexed { i, chunk ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (i == n.lastChunk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (i == n.lastChunk) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(end = 12.dp, top = 3.dp)
                        )
                        Text(
                            chunk,
                            fontSize = 17.sp,
                            lineHeight = 28.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    VSpace(16)
                }
            }
            VSpace(32)
        }
    }
}

