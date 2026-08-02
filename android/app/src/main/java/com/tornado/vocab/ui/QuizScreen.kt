package com.tornado.vocab.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.vocab.data.Word
import kotlin.math.abs

/**
 * شاشة الاختبار — بطاقات تُقلب مع إيماءات سحب حقيقية.
 *
 * البطاقة تتبع الإصبع فعلياً وتطير خارج الشاشة عند تجاوز حدّ الحسم، بدل قفزة
 * مفاجئة. هذا التتبّع المباشر هو ما يجعل المراجعة تبدو ملموسة لا مجرد أزرار.
 */
@Composable
fun QuizScreen(vm: QuizViewModel, onOpenWord: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (state.queue.isEmpty()) {
        EmptyState(
            icon = "🎓",
            title = "Nothing to review",
            body = "Add a few words first — they will appear here on a spaced-repetition schedule.",
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    if (state.finished) {
        QuizSummary(state, onRestart = { vm.start() })
        return
    }

    val card = state.current ?: return

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        // ===== شريط التقدّم =====
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            /*
             * حجم الجولة يُفسَّر لا يُعرض رقماً مجرّداً.
             *
             * «٤٠» بلا سياق يُقرأ حدّاً غامضاً أو خللاً — وقد حدث ذلك فعلاً.
             * وكلمة «round» تكفي لتقول إن ما تراه جولة من مكتبة أكبر، لا
             * مكتبتك كلها.
             */
            Text(
                if (state.freePractice) "Card ${state.shown} of ${state.total} · practice"
                else "Card ${state.shown} of ${state.total} in this round",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (state.answered > 0) {
                Text(
                    "${state.correct}/${state.answered} correct",
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusColors.Known
                )
            }
            if (state.canUndo) {
                IconButton(onClick = vm::undo, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", Modifier.size(18.dp))
                }
            }
        }
        LinearProgressIndicator(
            // التقدّم يتبع ما أُجيب عنه لا موضع المؤشر — التخطّي ليس تقدّماً
            progress = { (state.answered.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        state.notice?.let { notice ->
            LaunchedEffect(notice) {
                kotlinx.coroutines.delay(3_500)
                vm.dismissNotice()
            }
            Text(
                notice,
                Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }

        // ===== البطاقة =====
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            FlashCard(
                card = card,
                flipped = state.flipped,
                onFlip = vm::flip,
                onSwipeLeft = vm::skip,
                onSwipeRight = { if (state.canUndo) vm.undo() },
                onPronounce = vm::pronounce,
                onReadFull = vm::readFull,
                onFavorite = vm::toggleFavorite,
                onOpen = { onOpenWord(card.id) }
            )
        }

        // ===== الأزرار =====
        if (state.flipped) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.answer(false) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusColors.Missed)
                ) {
                    Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); HSpace(6); Text("Missed it")
                }
                TextButton(onClick = vm::skip) {
                    Icon(Icons.Filled.SkipNext, "Skip")
                }
                Button(
                    onClick = { vm.answer(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusColors.Known)
                ) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); HSpace(6); Text("I knew it")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recall the meaning, then tap to flip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = vm::skip) {
                    Icon(Icons.Filled.SkipNext, null, Modifier.size(18.dp)); HSpace(4); Text("Skip")
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlashCard(
    card: Word,
    flipped: Boolean,
    onFlip: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onPronounce: (Boolean) -> Unit,
    onReadFull: () -> Unit,
    onFavorite: () -> Unit,
    onOpen: () -> Unit
) {
    var offsetX by remember(card.id) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 70.dp.toPx() }
    val animatedOffset by animateFloatAsState(offsetX, tween(180), label = "swipe")

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = animatedOffset
                rotationZ = animatedOffset / 40f
                alpha = 1f - (abs(animatedOffset) / (threshold * 6)).coerceIn(0f, 0.6f)
            }
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(card.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            offsetX < -threshold -> { offsetX = 0f; onSwipeLeft() }
                            offsetX > threshold -> { offsetX = 0f; onSwipeRight() }
                            else -> offsetX = 0f
                        }
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { _, delta -> offsetX = (offsetX + delta).coerceIn(-260f, 260f) }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFlip
            )
            .padding(20.dp)
    ) {
        // النجمة وفتح البطاقة الكاملة متاحان في الوجهين
        Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (card.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "Star",
                    Modifier.size(18.dp),
                    tint = if (card.favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!flipped) {
                Text(
                    card.word,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                VSpace(10)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (card.oxford.isNotBlank()) InfoBadge("Oxford ${card.oxford}", MaterialTheme.colorScheme.primary)
                    if (card.cefr.isNotBlank()) InfoBadge("CEFR ${card.cefr}", MaterialTheme.colorScheme.primary)
                    if (card.cefr.isBlank() && card.estCefr.isNotBlank()) {
                        InfoBadge("≈ ${card.estCefr}", StatusColors.New, dashed = true)
                    }
                    card.pos.forEach { InfoBadge(it) }
                }
                val ipa = card.ipaUS.ifBlank { card.ipa.ifBlank { card.ipaUK } }
                if (ipa.isNotBlank()) {
                    VSpace(12)
                    Text(ipa, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                }
                VSpace(16)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = { onPronounce(false) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "US audio", tint = StatusColors.Missed)
                    }
                    if (card.audioUK.isNotBlank()) {
                        IconButton(onClick = { onPronounce(true) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "UK audio", tint = StatusColors.New)
                        }
                    }
                    IconButton(onClick = onReadFull) {
                        Icon(Icons.Filled.PlayCircle, "Read full", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Text(
                    "Meaning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(14)
                card.meanings.forEachIndexed { i, m ->
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        m.pos?.takeIf { it.isNotBlank() }?.let {
                            InfoBadge(it, MaterialTheme.colorScheme.primary); VSpace(4)
                        }
                        if (m.en.isNotBlank()) {
                            Text(
                                if (card.meanings.size > 1) "${i + 1}. ${m.en}" else m.en,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (m.ar.isNotBlank()) {
                            Text(
                                m.ar,
                                textAlign = TextAlign.Center,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                TextButton(onClick = onOpen) { Text("Open full card") }
            }
        }

        Text(
            "swipe ⟵ skip   ·   swipe ⟶ back",
            Modifier.align(Alignment.BottomCenter),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun QuizSummary(state: QuizState, onRestart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎉", fontSize = 52.sp)
        VSpace(12)
        Text("Round complete", style = MaterialTheme.typography.headlineMedium)
        VSpace(8)
        Text(
            "You got ${state.correct} of ${state.answered} right",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpace(20)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile("${state.accuracy}%", "Accuracy", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatTile("${state.correct}", "Correct", StatusColors.Known, Modifier.weight(1f))
            StatTile("${state.answered - state.correct}", "Missed", StatusColors.Missed, Modifier.weight(1f))
        }
        VSpace(24)
        Button(onClick = onRestart) { Text("Start a new round") }
    }
}
