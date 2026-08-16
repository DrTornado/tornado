package com.tornado.vocab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.vocab.data.NoteRow

/**
 * الملاحظات الصوتية.
 *
 * نصوص طويلة تُسمع بنفس مشغّل الكلمات: الخلفية وإشعار المشغّل والسرعة ومؤقّت
 * النوم وأزرار السماعة — كلها تعمل هنا بلا سطر إضافي، لأن المشغّل واحد.
 *
 * والنصّ يُقسَّم مقاطع تصير عناصر طابور مستقلة، فيتنقّل المستخدم بينها كما
 * يتنقّل بين الكلمات ويستأنف من حيث وقف بدل العودة إلى أول نصّ من ساعة.
 */
@Composable
fun NotesScreen(
    vm: NotesViewModel,
    onOpenNote: (Long) -> Unit = {},
    /** الضغط على تشغيل يفتح المشغّل — الصوت بلا شاشته يبدو معطّلاً */
    onOpenPlayer: () -> Unit = {}
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()

    var pasting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<NoteRow?>(null) }

    val context = LocalContext.current
    LaunchedEffect(ui.message) {
        ui.message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.dismissMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("AUDIO NOTES", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(
                    if (rows.isEmpty()) "Nothing yet"
                    else "${rows.size} note${if (rows.size > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = { pasting = true }) { Text("Paste text") }
        }

        /*
         * زرّ تشغيل لا يُخطأ.
         *
         * كان التشغيل أيقونةً بأربعين نقطة على يسار الصفّ، ومن أخطأها بقليل
         * ضغط الصفّ ففُتح النص بدل المشغّل — فيبدو أن التشغيل لا يعمل وهو
         * يعمل. والهدف الصغير في قائمة تُستعمل باليد أثناء المشي عيبٌ في
         * التصميم لا في يد المستخدم.
         */
        if (rows.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { vm.playAll(); onOpenPlayer() }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                    HSpace(6)
                    Text("PLAY ALL", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        // شريط الاختيار — لا يظهر إلا حين تُختار ملاحظة
        if (selected.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${selected.size} selected",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.selectAll() }) { Text("All", fontSize = 13.sp) }
                TextButton(onClick = { vm.clearSelection() }) { Text("Clear", fontSize = 13.sp) }
                TextButton(onClick = { vm.playSelected(); onOpenPlayer() }) {
                    Text(
                        "▶ Play", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Long texts, read aloud",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                VSpace(10)
                Text(
                    "Paste an article, a chapter, or your own notes. " +
                        "They play in the same player — in the background, with the screen off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                VSpace(10)
                Text(
                    "Anything you add on the computer arrives here too.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                NoteRowItem(
                    row = row,
                    playing = playback.current?.id == row.id && playback.isPlaying,
                    /*
                     * الضغط على الصفّ يفتح النص، وزرّ التشغيل يشغّل.
                     *
                     * كان الصفّ كله زرّ تشغيل، ففتح النص للقراءة أو التحرير
                     * مستحيل من القائمة أصلاً. الفعلان مفصولان الآن كما في
                     * قائمة الكلمات تماماً: الصفّ للمحتوى، والأيقونة للصوت.
                     */
                    onOpen = { onOpenNote(row.id) },
                    // يشغّل من هذه الملاحظة ثم يواصل لما بعدها، ويفتح المشغّل
                    onPlay = { vm.playAll(row.id); onOpenPlayer() },
                    onDelete = { confirmDelete = row },
                    selecting = selected.isNotEmpty(),
                    selected = row.id in selected,
                    onToggleSelect = { vm.toggleSelect(row.id) }
                )
            }
        }
    }

    if (pasting) {
        PasteSheet(
            onDismiss = { pasting = false },
            onSave = { text, title -> vm.add(text, title); pasting = false }
        )
    }

    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this note?") },
            text = { Text("\"${row.title}\" — this also removes it from the computer.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(row.id); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteRowItem(
    row: NoteRow,
    playing: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            // ضغطةٌ مطوّلة تبدأ الاختيار — كما في الكلمات والاستماع
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // زرّ التشغيل مستقل عن الصفّ — الصفّ يفتح النص والزرّ يشغّل الصوت
        // ستّ وخمسون نقطة: أدنى هدف لمس مريح، والأربعون كانت تُخطأ
        IconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.PlayArrow, "Play",
                Modifier.size(26.dp),
                tint = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                row.preview,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // التقدّم يُعرض لأن نصّاً طويلاً يُسمع على أيام لا في جلسة
                buildString {
                    append("${row.wordCount} words · ${row.chunkCount} parts")
                    if (row.lastChunk > 0) append(" · resumes at ${row.lastChunk + 1}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete, "Delete", Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * لصق نصّ طويل.
 *
 * العنوان اختياري: إجبار المستخدم على تسمية كل لصقة يجعله يتوقّف عن اللصق،
 * وأول سطر يصلح عنواناً في الغالب الأعمّ.
 */
@Composable
private fun PasteSheet(onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New audio note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                VSpace(10)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    label = { Text("Paste the text") },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                if (text.length > 40) {
                    VSpace(8)
                    val parts = com.tornado.vocab.data.NoteChunker.split(text).size
                    Text(
                        "${text.split(Regex("\\s+")).count { it.isNotBlank() }} words · $parts parts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text, title.takeIf { it.isNotBlank() }) },
                enabled = text.trim().length >= 20
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
