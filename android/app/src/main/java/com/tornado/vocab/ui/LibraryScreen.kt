package com.tornado.vocab.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.vocab.data.SortOrder
import com.tornado.vocab.data.WordRow
import com.tornado.vocab.data.WordStatus

/**
 * مكتبة الكلمات — الشاشة الرئيسية.
 *
 * تحسين جوهري على تطبيق الويب: هناك لا يوجد بحث إطلاقاً، فالوصول لكلمة بعينها
 * يعني تمريراً يدوياً في قائمة كاملة. هنا بحث فوري بالعربية والإنجليزية معاً،
 * فوق فهرس نصي كامل يبقى فورياً عند آلاف الكلمات.
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenWord: (Long) -> Unit,
    onOpenPlayer: () -> Unit
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val cardQueue by vm.cardQueue.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val expandedWords by vm.expandedWords.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<WordRow?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    /*
     * رسالة زرّ «عربي» تُقال ولا تُبتلع.
     *
     * الزرّ كان يخرج صامتاً حين لا يجد معنى عربياً، فلا يعرف المستخدم أعُطِل
     * الزرّ أم عُطِل الصوت. والرسالة العابرة تكفي: خبرٌ يُقال مرّة ثم يزول،
     * لا نافذة تُغلق بضغطة.
     */
    val notice by vm.notice.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(notice) {
        notice?.let {
            android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearNotice()
        }
    }

    Column(Modifier.fillMaxSize()) {

        // ===== البحث =====
        OutlinedTextField(
            value = filters.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search words, meanings, Arabic…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = vm::clearQuery) { Icon(Icons.Filled.Clear, "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )

        // ===== المرشّحات =====
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LibraryFilterChips(
                current = filters.status,
                favOnly = filters.favOnly,
                counts = stats,
                onStatus = vm::setStatus,
                onFav = vm::toggleFavOnly,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(order.label) },
                            onClick = { vm.setSort(order); sortMenu = false },
                            leadingIcon = {
                                if (order == filters.sort) Icon(Icons.Filled.Bolt, null)
                            }
                        )
                    }
                }
            }
        }

        // ===== سطر العدد + تشغيل الكل =====
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // «N filling in» ذهبت مع القاموس الآليّ. والطابور تحتها يقول
            // أيّ كلمةٍ تنتظر بطاقتها بالاسم — وذلك أصدق من رقمٍ يتناقص.
            Text(
                rows.size.toString() + " word" + (if (rows.size == 1) "" else "s"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (rows.isNotEmpty()) {
                TextButton(onClick = { vm.playVisible(null); onOpenPlayer() }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    HSpace(4)
                    Text("Listen to these", fontSize = 13.sp)
                }
            }
        }

        // ===== طابور البطاقات =====
        CardQueueRow(cardQueue)

        if (rows.isEmpty()) {
            EmptyState(
                icon = if (filters.query.isBlank()) "📚" else "🔍",
                title = if (filters.query.isBlank()) "No words here yet" else "No matches",
                body = if (filters.query.isBlank())
                    "Add words from the Add tab and they will appear here with meanings, audio and review scheduling."
                else "Nothing matched \"${filters.query}\". Try a shorter search or a different filter.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { row ->
                    val isOpen = expanded.contains(row.id)
                    WordListRow(
                        row = row,
                        levels = levels,
                        expanded = isOpen,
                        onOpen = { vm.toggleExpanded(row.id) },
                        onFavorite = { vm.toggleFavorite(row) },
                        onPlay = { vm.speakCard(row.id) },
                        onDelete = { pendingDelete = row }
                    )
                    // البطاقة تنسدل في مكانها — والبطاقات الأخرى المفتوحة تبقى مفتوحة
                    androidx.compose.animation.AnimatedVisibility(visible = isOpen) {
                        val full = expandedWords[row.id]
                        if (full == null) {
                            Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    Modifier.size(20.dp), strokeWidth = 2.dp
                                )
                            }
                        } else {
                            InlineWordCard(
                                word = full.word,
                                extra = full.extra,
                                onOpenFull = { onOpenWord(row.id) },
                                onPlayFull = { vm.speakCard(row.id) },
                                onPronounce = { british -> vm.pronounce(full.word, british) },
                                onArabic = { vm.speakArabic(row.id) }
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    // الحذف يؤكَّد دائماً — لا تراجع بعده
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${row.word}\"?") },
            text = { Text("This removes the word and its review progress. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(row); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

/** صف واحد ثابت بلا تمرير — خمس شرائح متساوية العرض تظهر كلها دائماً */
@Composable
private fun LibraryFilterChips(
    current: WordStatus?,
    favOnly: Boolean,
    counts: com.tornado.vocab.data.LibraryStats,
    onStatus: (WordStatus?) -> Unit,
    onFav: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactChip("All", counts.total, current == null && !favOnly, Modifier.weight(1f)) {
            onStatus(null); if (favOnly) onFav()
        }
        CompactChip("New", counts.newCount, current == WordStatus.NEW, Modifier.weight(1f), StatusColors.New) {
            onStatus(if (current == WordStatus.NEW) null else WordStatus.NEW)
        }
        CompactChip("Missed", counts.missed, current == WordStatus.MISSED, Modifier.weight(1f), StatusColors.Missed) {
            onStatus(if (current == WordStatus.MISSED) null else WordStatus.MISSED)
        }
        CompactChip("Known", counts.known, current == WordStatus.KNOWN, Modifier.weight(1f), StatusColors.Known) {
            onStatus(if (current == WordStatus.KNOWN) null else WordStatus.KNOWN)
        }
        CompactChip("★", counts.favorites, favOnly, Modifier.weight(0.6f)) { onFav() }
    }
}

/** بطاقة الكلمة المنسدلة داخل القائمة — كل الأقسام بلا مغادرة الشاشة */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InlineWordCard(
    word: com.tornado.vocab.data.Word,
    extra: com.tornado.vocab.data.Enrichment?,
    onOpenFull: () -> Unit,
    onPlayFull: () -> Unit,
    onPronounce: (Boolean) -> Unit,
    onArabic: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // أزرار النطق: أمريكي، بريطاني، عربي، والشرح الكامل — صف واحد موزّع بالتساوي
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AudioChip("US", StatusColors.Missed, Modifier.weight(1f)) { onPronounce(false) }
            AudioChip("UK", StatusColors.New, Modifier.weight(1f)) { onPronounce(true) }
            AudioChip("عربي", MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { onArabic() }
            AudioChip("Full", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { onPlayFull() }
        }
        VSpace(12)

        val ipa = word.ipaUS.ifBlank { word.ipa.ifBlank { word.ipaUK } }
        if (ipa.isNotBlank() || word.arabicPron.isNotBlank()) {
            Text(
                listOfNotNull(
                    ipa.takeIf { it.isNotBlank() },
                    word.arabicPron.takeIf { it.isNotBlank() }
                ).joinToString("   "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpace(8)
        }

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // الحكم قائمةُ أوكسفورد الرسمية — والخارج عنها يُقال صراحةً
            when {
                word.oxford == "none" -> InfoBadge("Not in Oxford 5000")
                word.oxford.isNotBlank() ->
                    InfoBadge("Oxford ${word.oxford}", MaterialTheme.colorScheme.primary)
            }
            if (word.cefr.isNotBlank()) InfoBadge("CEFR ${word.cefr}", MaterialTheme.colorScheme.primary)
            if (word.cefr.isBlank() && word.estCefr.isNotBlank()) {
                InfoBadge("≈ ${word.estCefr}", StatusColors.New, dashed = true)
            }
            word.pos.forEach { InfoBadge(it) }
        }

        if (word.meanings.isNotEmpty()) {
            SectionHeader("Meanings")
            word.meanings.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (m.en.isNotBlank()) Text(m.en, style = MaterialTheme.typography.bodyMedium)
                    if (m.ar.isNotBlank()) {
                        Text(
                            m.ar,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (word.inflections.isNotEmpty()) {
            SectionHeader("Word forms")
            Text(word.inflections.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
        }

        /*
         * نفس أقسام الشاشة الكاملة وبنفس ترتيبها.
         *
         * كانت البطاقة المنسدلة تعرض خمسة أقسام من `Word` وحدها، فلا أضداد
         * ولا ملاحظات استعمال ولا نطق ولا أنماط تركيب — وهي أقسامٌ لا موضع
         * لها في `Word` أصلاً، تسكن الإثراء. فمن قرأ من القائمة قرأ نصف
         * البطاقة وهو يحسبها كلّها.
         */
        InlinePairs("Related words", word.derivatives)
        InlinePairs("Synonyms", word.synonyms)
        InlinePairs("Antonyms", extra?.antonyms.orEmpty())
        InlinePairs("Common combinations", word.collocations)
        InlinePairs("Examples", word.examples)
        InlinePairs("Differences", word.differences)
        InlinePairs("Grammar patterns", extra?.grammarPatterns.orEmpty())
        InlinePairs("Pronunciation note", extra?.pronunciationNote.orEmpty())
        InlinePairs("Usage notes", extra?.usageNotes.orEmpty())

        VSpace(8)
        TextButton(onClick = onOpenFull) { Text("Open full card →", fontSize = 13.sp) }
    }
}

/** زر صوت مدمج يعيش داخل صف واحد بلا تمرير */
@Composable
private fun AudioChip(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "🔊 $label",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun InlinePairs(title: String, items: List<com.tornado.vocab.data.LangPair>) {
    if (items.isEmpty()) return
    SectionHeader(title)
    items.forEach { p ->
        /*
         * كلُّ سطرٍ بلغةٍ واحدة واتّجاهٍ واحد — كما في الشاشة الكاملة.
         *
         * وكانت `note` و`ex` و`exAr` تسقط هنا كلّها: فيها نسبةُ المرادف إلى
         * معناه ومثالُه وترجمته، وبإسقاطها يقرأ صاحب المكتبة «violate» تحت
         * أضداد `abide` بلا ما يقيّدها بمعناها.
         */
        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            if (p.en.isNotBlank()) Text(p.en, style = MaterialTheme.typography.bodyMedium)
            if (p.ar.isNotBlank()) {
                Text(
                    p.ar,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (p.note.isNotBlank()) {
                Text(
                    p.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (p.ex.isNotBlank()) {
                VSpace(3)
                Text(p.ex, style = MaterialTheme.typography.bodyMedium)
            }
            if (p.exAr.isNotBlank()) {
                Text(
                    p.exAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WordListRow(
    row: WordRow,
    levels: Map<String, Pair<String, Boolean>>,
    expanded: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val statusColor = StatusColors.of(row.status)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 0.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // شريط الحالة الجانبي — نفس الشيفرة اللونية في كل الشاشات
        Box(
            Modifier
                .width(4.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(statusColor)
        )
        HSpace(12)

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.word,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                /*
                 * المكتوبة أوّلاً، ثم ما بناه التطبيق لنفسه.
                 *
                 * كان الصفّ يقرأ الخام وحده، فيعرض «≈ C1» بينما تعرض البطاقة
                 * المفتوحة تحته «CEFR B2» — رقمان متناقضان في شاشةٍ واحدة.
                 */
                val written = levels[row.word.trim().lowercase()]
                val level = written?.first ?: row.cefr.ifBlank { row.estCefr }
                val exact = written?.second ?: row.cefr.isNotBlank()
                if (level.isNotBlank()) {
                    HSpace(8)
                    InfoBadge(if (exact) level else "≈ $level", dashed = !exact)
                }
            }
            val subtitle = row.primaryAr.ifBlank { row.primaryEn }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Read aloud", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) {
            Icon(
                if (row.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                "Star",
                tint = if (row.favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Delete, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete word") },
                    onClick = { menu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) }
                )
            }
        }
    }
}

/**
 * طابور البطاقات — الكلمات التي لم تُكتب بطاقتها بعد.
 *
 * ساكنٌ حين لا شيء ينتظر، ظاهرٌ حين ينتظر شيء. لا إشعار ولا مقاطعة: من فقد
 * الثقة في بطاقةٍ فقيرة إنما فقدها لأنه لم يعرف أهي كلّ ما نقدر عليه أم
 * بطاقةٌ لم تُكتب بعد — وهذا السطر يفرّق بين الاثنين.
 *
 * والنسخ لأن الطلب يُكتب في المحادثة: كلماتٌ مفصولة بمسافات، جاهزةٌ للّصق.
 *
 * وينقلب السطر تحذيراً بعد ساعتين: البطاقة تصل في خمس دقائق وسيطاً (قِيست
 * اثنتا عشرة جولة: من دقيقةٍ ونصف إلى تسع)، فالساعتان تعنيان عطلاً
 * — رمزاً انتهى، أو مساراً سقط، أو دقائق نفدت. وكان يقول «تنتظر» إلى الأبد
 * بنفس اللهجة الهادئة، فيظنّها صاحبها بطيئةً ولا يعرف أن شيئاً انكسر.
 */
@Composable
private fun CardQueueRow(queue: CardQueue) {
    if (queue.words.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val stalled = queue.stalled

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        val count = "${queue.words.size} " + (if (queue.words.size == 1) "word" else "words")
        Text(
            (if (stalled) "⚠️ $count still waiting after ${waited(queue.oldestMs)}  ·  check GitHub Actions"
             else "📝 $count awaiting a written card") +
                (if (open) "" else "  ·  tap to list"),
            style = MaterialTheme.typography.labelMedium,
            color = if (stalled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
        )
        AnimatedVisibility(open) {
            Column {
                Text(
                    queue.words.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (stalled) Text(
                    "A card normally arrives within minutes. Open the tornado-data " +
                        "repository → Actions to see what failed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(queue.words.joinToString(" ")))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("📋 Copy list", fontSize = 12.sp) }
            }
        }
    }
}

/** مدّة الانتظار بأخشن وحدةٍ تصفها: «1h» ثم «3h» ثم «2d». */
internal fun waited(ms: Long): String {
    val h = ms / 3_600_000L
    return if (h < 24) "${h}h" else "${h / 24}d"
}
