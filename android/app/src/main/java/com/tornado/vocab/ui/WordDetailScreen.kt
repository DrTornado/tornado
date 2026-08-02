package com.tornado.vocab.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.PlaybackBus
import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Word
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@kotlinx.coroutines.ExperimentalCoroutinesApi
class WordDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.tornado.repository
    private val id = MutableStateFlow(0L)

    val word: StateFlow<Word?> = id
        .flatMapLatest { repo.observeWord(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun load(wordId: Long) { id.value = wordId }

    fun toggleFavorite() = viewModelScope.launch {
        val w = word.value ?: return@launch
        repo.toggleFavorite(w.id, !w.favorite)
    }

    fun pronounce(british: Boolean) {
        val w = word.value ?: return
        val url = if (british) w.audioUK else w.bestUsAudio
        PlaybackBus.submit(getApplication()) { it.playPronunciation(url, w.word, british) }
    }

    fun readFull() {
        val w = word.value ?: return
        PlaybackBus.submit(getApplication()) { it.speakCard(w, full = true) }
    }

    /** المعاني وحدها بلا مرادفات ولا متلازمات ولا مشتقات */
    fun readShort() {
        val w = word.value ?: return
        PlaybackBus.submit(getApplication()) { it.speakCard(w, full = false) }
    }
}

/**
 * بطاقة الكلمة الكاملة — تعرض كل قسم موجود في تطبيق الويب:
 * النطق، المستوى، المعاني، التصريفات، المشتقات، المرادفات، المتلازمات،
 * الأمثلة، والفروق. الأقسام الفارغة تُحذف بدل عرض عناوين بلا محتوى.
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun WordDetailScreen(vm: WordDetailViewModel, onBack: () -> Unit) {
    val word by vm.word.collectAsStateWithLifecycle()
    val w = word

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(w?.word ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (w != null) {
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                if (w.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                "Star",
                                tint = if (w.favorite) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (w == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { Text("Loading…") }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)
        ) {
            item {
                VSpace(8)
                Text(w.word, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)

                val prons = listOfNotNull(
                    w.ipaUS.takeIf { it.isNotBlank() },
                    w.ipaUK.takeIf { it.isNotBlank() },
                    w.ipa.takeIf { it.isNotBlank() && w.ipaUS.isBlank() && w.ipaUK.isBlank() }
                )
                if (prons.isNotEmpty()) {
                    Text(
                        prons.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (w.arabicPron.isNotBlank()) {
                    Text(
                        w.arabicPron,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                VSpace(12)
                PronunciationRow(w, vm)

                VSpace(12)
                BadgeRow(w)
                VSpace(4)
            }

            if (w.meanings.isNotEmpty()) {
                item { SectionHeader("Meanings") }
                items(w.meanings) { m ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            m.pos?.takeIf { it.isNotBlank() }?.let {
                                InfoBadge(it, MaterialTheme.colorScheme.primary)
                                HSpace(8)
                            }
                        }
                        if (m.en.isNotBlank()) {
                            Text(m.en, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (m.ar.isNotBlank()) {
                            Text(
                                m.ar,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (w.inflections.isNotEmpty()) {
                item {
                    SectionHeader("Word forms")
                    Text(w.inflections.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
                }
            }

            pairSection("Related words", w.derivatives)
            pairSection("Synonyms", w.synonyms)
            pairSection("Common combinations", w.collocations)
            pairSection("Examples", w.examples)
            pairSection("Differences", w.differences)

            item {
                SectionHeader("Your progress")
                ProgressPanel(w)
                VSpace(40)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.pairSection(
    title: String,
    items: List<LangPair>
) {
    if (items.isEmpty()) return
    item { SectionHeader(title) }
    items(items) { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            if (p.en.isNotBlank()) Text(p.en, style = MaterialTheme.typography.bodyLarge)
            if (p.ar.isNotBlank()) {
                Text(
                    p.ar,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * صف الاستماع في شاشة الكلمة.
 *
 * أربعة أزرار في سطر واحد موزّعة بالوزن، فلا تمرير أفقي مهما ضاقت الشاشة:
 * نطق أمريكي، نطق بريطاني، ثم قراءة مختصرة وقراءة كاملة.
 *
 * الفصل بين المختصر والكامل مقصود: من يراجع كلمة يعرفها يريد معناها في ثوانٍ،
 * ومن يقابلها أول مرة يريد كل شيء. زرّ واحد يخدم أحدهما ويظلم الآخر.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun PronunciationRow(w: Word, vm: WordDetailViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PronButton("US", StatusColors.Missed, Modifier.weight(1f)) { vm.pronounce(false) }
        PronButton("UK", StatusColors.New, Modifier.weight(1f)) { vm.pronounce(true) }
        ReadButton("SHORT", filled = false, modifier = Modifier.weight(1f)) { vm.readShort() }
        ReadButton("FULL", filled = true, modifier = Modifier.weight(1.1f)) { vm.readFull() }
    }
}

@Composable
private fun ReadButton(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (filled) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(
                    1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)
                )
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.PlayCircle, null,
            Modifier.size(14.dp),
            tint = if (filled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary
        )
        HSpace(4)
        Text(
            label,
            color = if (filled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun PronButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, color, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔊 $label", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BadgeRow(w: Word) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (w.oxford.isNotBlank()) InfoBadge("Oxford ${w.oxford}", MaterialTheme.colorScheme.primary)
        if (w.cefr.isNotBlank()) InfoBadge("CEFR ${w.cefr}", MaterialTheme.colorScheme.primary)
        if (w.oxford.isBlank() && w.freqLabel.isNotBlank()) InfoBadge("📊 ${w.freqLabel}")
        // الحدّ المنقّط يميّز التقدير عن التصنيف الرسمي — فرق جوهري لا يصح طمسه
        if (w.oxford.isBlank() && w.estCefr.isNotBlank()) {
            InfoBadge("≈ CEFR ${w.estCefr} (est.)", StatusColors.New, dashed = true)
        }
        w.pos.forEach { InfoBadge(it) }
    }
}

@Composable
private fun ProgressPanel(w: Word) {
    val total = w.right + w.wrong
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MiniStat("Status", StatusColors.label(w.status), StatusColors.of(w.status))
        MiniStat("Correct", w.right.toString(), StatusColors.Known)
        MiniStat("Missed", w.wrong.toString(), StatusColors.Missed)
        MiniStat(
            "Next review",
            when {
                total == 0 -> "—"
                w.due <= System.currentTimeMillis() -> "Due"
                else -> "${((w.due - System.currentTimeMillis()) / 86_400_000L) + 1}d"
            },
            MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 16.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
