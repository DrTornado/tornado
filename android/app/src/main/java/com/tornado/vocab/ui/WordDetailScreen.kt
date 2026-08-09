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
import com.tornado.vocab.data.Enrichment
import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Phrase
import com.tornado.vocab.data.Word
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@kotlinx.coroutines.ExperimentalCoroutinesApi
class WordDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.tornado.repository
    private val id = MutableStateFlow(0L)

    val word: StateFlow<Word?> = id
        .flatMapLatest { repo.observeWord(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /*
     * الإثراء يتبع الكلمة المفتوحة وحدها.
     *
     * بطاقةٌ واحدة تُقرأ حين تُفتح، فلا تحمّل القائمةُ مئةً وستّين بطاقةً
     * لا تُعرض. و`null` تعني «لم يصل إثراء بعد» — فتظهر البطاقة كما كانت
     * تماماً، بلا نقص ولا رسالة خطأ.
     *
     * والمفتاح نصُّ الكلمة لا الصفّ كلّه: الصفّ يتغيّر مع كل إجابة في
     * الاختبار، والإثراء لا علاقة له بذلك.
     */
    val enrichment: StateFlow<Enrichment?> = word
        .map { it?.word.orEmpty() }
        .distinctUntilChanged()
        .map { w ->
            if (w.isBlank()) null
            else runCatching {
                getApplication<Application>().tornado.enrichSync.forWord(w)
            }.getOrNull()
        }
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

    /*
     * الصوت يقرأ البطاقة المُثراة، لا المحفوظة في الجهاز.
     *
     * كان يقرأ الخام: تُعرض على الشاشة معانٍ مكتوبةٌ بيد ويُسمَع غيرها.
     * والمتعلّم يسمع أكثر ممّا يقرأ، فيحفظ ما تركناه لا ما كتبناه.
     */
    private fun spoken(): Word? = word.value?.withEnrichment(enrichment.value)

    fun readFull() {
        val w = spoken() ?: return
        PlaybackBus.submit(getApplication()) { it.speakCard(w, full = true) }
    }

    /** المعاني وحدها بلا مرادفات ولا متلازمات ولا مشتقات */
    fun readShort() {
        val w = spoken() ?: return
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
    val enrichment by vm.enrichment.collectAsStateWithLifecycle()
    val w = word
    val e = enrichment

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
        // نسخةٌ للعرض وحده — لا تُحفظ ولا تُرفع، فخطأ العرض لا يمسّ بياناته
        val shown = w.withEnrichment(e)

        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)
        ) {
            item {
                VSpace(8)
                Text(shown.word, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)

                val prons = listOfNotNull(
                    shown.ipaUS.takeIf { it.isNotBlank() },
                    shown.ipaUK.takeIf { it.isNotBlank() },
                    shown.ipa.takeIf { it.isNotBlank() && shown.ipaUS.isBlank() && shown.ipaUK.isBlank() }
                )
                if (prons.isNotEmpty()) {
                    Text(
                        prons.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (shown.arabicPron.isNotBlank()) {
                    Text(
                        shown.arabicPron,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                VSpace(12)
                PronunciationRow(w, vm)

                VSpace(12)
                BadgeRow(shown)
                VSpace(4)
            }

            if (shown.meanings.isNotEmpty()) {
                item { SectionHeader("Meanings") }
                items(shown.meanings) { m ->
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

            if (shown.inflections.isNotEmpty()) {
                item {
                    SectionHeader("Word forms")
                    Text(shown.inflections.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // الدمج تمّ في withEnrichment — الشاشة والصوت يقرآن المصدر نفسه
            pairSection("Related words", shown.derivatives)
            pairSection("Synonyms", shown.synonyms)
            pairSection("Antonyms", e?.antonyms.orEmpty())
            pairSection("Common combinations", shown.collocations)
            pairSection("Examples", shown.examples)
            pairSection("Differences", shown.differences)
            pairSection("Grammar patterns", e?.grammarPatterns.orEmpty())

            pairSection("Pronunciation note", e?.pronunciationNote.orEmpty())

            phraseSection("Phrasal verbs", e?.phrasalVerbs)
            phraseSection("Idioms", e?.idioms)

            pairSection("Usage notes", e?.usageNotes.orEmpty())
            e?.register?.takeIf { it.isNotEmpty() }?.let { reg ->
                item {
                    SectionHeader("Register")
                    Text(reg.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
                }
            }

            /*
             * الغائب يُقال، ولا يُترك للتخمين.
             *
             * قسمٌ بلا معلومةٍ موثوقة يُذكر اسمه صراحةً، فيعرف القارئ أن
             * المصدر خالٍ لا أن التطبيق أهمل — والاختلاق ليس بديلاً.
             */
            e?.absent?.takeIf { it.isNotEmpty() }?.let { gaps ->
                item {
                    VSpace(14)
                    Text(
                        "لا توجد معلومة موثوقة في المصادر لـ: " +
                            gaps.joinToString("  ·  ") { Enrichment.absentLabel(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionHeader("Your progress")
                ProgressPanel(w)
                VSpace(40)
            }
        }
    }
}

/*
 * الدمج: القائم أوّلاً، والإثراء يزيد ولا يزيح.
 *
 * ما بناه التطبيق لنفسه يبقى في مكانه — قد يكون فيه ترجمةٌ عربية ليست في
 * القاعدة. والإثراء يُلحق ما ليس عنده، بلا تكرار.
 */
private fun norm(s: String) = s.trim().lowercase()

/**
 * البطاقة كما تُعرض: القائم أوّلاً، والإثراء يملأ الفراغ.
 *
 * نسخةٌ لا تُحفظ: `Word` تُرفع إلى المستودع، فالكتابة فيها تنقل خطأ العرض
 * إلى بيانات المستخدم. وما لا يُكتب لا يُفسد.
 *
 * والفراغ وحده يُملأ — ما بناه التطبيق لنفسه لا يُزاح، لأن فيه أحياناً
 * ترجمةً عربية ليست في القاعدة.
 */
private fun Word.withEnrichment(e: Enrichment?): Word {
    if (e == null) return this
    val extraMeanings = e.meanings.filter { m ->
        m.en.isNotBlank() && meanings.none { norm(it.en) == norm(m.en) }
    }
    return copy(
        ipaUS = ipaUS.ifBlank { e.ipaUS },
        ipaUK = ipaUK.ifBlank { e.ipaUK },
        ipa = if (ipaUS.isBlank() && ipaUK.isBlank() && e.ipaUS.isBlank() &&
            e.ipaUK.isBlank()
        ) ipa.ifBlank { e.ipaGen } else ipa,
        arabicPron = arabicPron.ifBlank { e.arabicPron },
        oxford = oxford.ifBlank { e.oxford },
        cefr = cefr.ifBlank { e.cefr },
        estCefr = if (cefr.isBlank() && e.cefr.isBlank()) {
            estCefr.ifBlank { e.cefrEst }
        } else estCefr,
        pos = if (e.curated && e.pos.isNotEmpty()) e.pos
              else pos + e.pos.filterNot { p -> pos.any { norm(it) == norm(p) } },
        meanings = if (e.curated && e.meanings.isNotEmpty()) e.meanings
                   else meanings + extraMeanings,
        inflections = if (e.curated && e.inflections.isNotEmpty()) e.inflections
                      else inflections + e.inflections
                          .filterNot { f -> inflections.any { norm(it) == norm(f) } },
        /*
         * القوائم تُدمج هنا لا عند الرسم.
         *
         * كان الدمج في الشاشة وحدها، فقرأ الصوتُ الخام: يُعرض ما كتبناه
         * ويُسمَع ما لم نكتبه. ونقطةُ دمجٍ واحدة تمنع افتراقهما مستقبلاً.
         */
        derivatives = take(derivatives, e.derivatives, e.curated),
        synonyms = take(synonyms, e.synonyms, e.curated),
        collocations = take(collocations, e.collocations, e.curated),
        examples = take(examples, e.examples, e.curated),
        differences = take(differences, e.differences, e.curated)
    )
}

/*
 * المراجَعة تحلّ محلّ القديمة، ولا تُضاف إليها.
 *
 * كان الدمج ضمّاً، وبطاقة التطبيق القديمة جاءت من قاموسٍ آليّ كثيرٌ من
 * معانيها وأمثلتها بلا عربية. فتتصدّر سطورٌ إنجليزية عارية ما كُتب
 * كاملاً، فيظنّ القارئ البطاقة ناقصة — وهي مسبوقة بما لا ينفع لا ناقصة.
 *
 * وغير المراجَعة تبقى على الضمّ: فيها ما ليس عندنا، وحذفُه خسارة.
 */
private fun take(base: List<LangPair>, extra: List<LangPair>,
                 curated: Boolean): List<LangPair> =
    if (curated && extra.isNotEmpty()) extra else mergedPairs(base, extra)

private fun mergedPairs(base: List<LangPair>, extra: List<LangPair>?): List<LangPair> {
    if (extra.isNullOrEmpty()) return base
    val seen = base.mapTo(HashSet()) { norm(it.en) }
    return base + extra.filter { it.en.isNotBlank() && seen.add(norm(it.en)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.phraseSection(
    title: String,
    items: List<Phrase>?
) {
    if (items.isNullOrEmpty()) return
    item { SectionHeader(title) }
    items(items) { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(
                p.phrase,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (p.gloss.isNotBlank()) {
                Text(
                    p.gloss,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        /*
         * كلُّ سطرٍ بلغةٍ واحدة واتّجاهٍ واحد.
         *
         * كان السطر يجمعهما: «يخالف — ضدّ abide by». فيقفز البصر بين
         * اتّجاهين في السطر الواحد، وتصير القراءة مُتعِبة.
         */
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            if (p.en.isNotBlank()) Text(p.en, style = MaterialTheme.typography.bodyLarge)
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
