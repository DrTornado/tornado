package com.tornado.vocab.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tornado.vocab.audio.PlaybackUiState

/**
 * المشغّل المصغّر الدائم.
 *
 * هذا هو الفرق الجوهري بين تطبيق فيه مشغّل وتطبيق هو مشغّل: شريط التشغيل
 * حاضر فوق شريط التنقّل في كل شاشة، فلا يفقد المستخدم سياق ما يسمعه لمجرد
 * أنه فتح قائمة الكلمات أو بدأ اختباراً.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.hasQueue || state.preparing,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onExpand)
        ) {
            // شريط تقدّم رفيع بعرض الشاشة — مؤشر بصري بلا استهلاك مساحة
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {}
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.current?.word?.take(1)?.uppercase() ?: "T",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                }
                HSpace(10)

                Column(Modifier.weight(1f)) {
                    Text(
                        state.current?.word ?: if (state.preparing) "Preparing…" else "Nothing playing",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = state.current?.subtitle.orEmpty()
                    Text(
                        when {
                            // الانتظار الأول يُفسَّر: صمت بلا خبر يُقرأ عطلاً
                            state.preparing && state.readyCount == 0 ->
                                if (state.prepareTotal > 0)
                                    "Building the first card · ${state.prepareDone}/${state.prepareTotal}"
                                else "Building the first card…"
                            sub.isNotBlank() -> sub
                            else -> "${state.index + 1} / ${state.queue.size}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(24.dp))
                    }
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
                        if (state.preparing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (state.isPlaying) "Pause" else "Play",
                                Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipNext, "Next", Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}
