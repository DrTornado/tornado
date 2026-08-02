package com.tornado.vocab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.vocab.audio.PlayScope
import com.tornado.vocab.data.WordStatus

/**
 * شاشة الاستماع — اختيار القائمة ثم التشغيل.
 *
 * كل الأزرار في صفوف ثابتة موزّعة بالتساوي: لا تمرير أفقي في أي موضع،
 * وكل خيار مرئي منذ اللحظة الأولى.
 */
@Composable
fun ListenScreen(vm: ListenViewModel, onOpenPlayer: () -> Unit) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val playlist by vm.loadedPlaylist.collectAsStateWithLifecycle()

    if (stats.total == 0) {
        EmptyState(
            icon = "🎧",
            title = "Nothing to listen to yet",
            body = "Add words first — every list in the app becomes a playable queue.",
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {

        item {
            SectionLabel("Playlist")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactChip("All", stats.total, source.key == "all", Modifier.weight(1f)) {
                    vm.setSource(PlaylistSource("all", "All words"))
                }
                CompactChip("Missed", stats.missed, source.key == "missed", Modifier.weight(1f), StatusColors.Missed) {
                    vm.setSource(PlaylistSource("missed", "Missed", WordStatus.MISSED))
                }
                CompactChip("New", stats.newCount, source.key == "new", Modifier.weight(1f), StatusColors.New) {
                    vm.setSource(PlaylistSource("new", "New", WordStatus.NEW))
                }
                CompactChip("Known", stats.known, source.key == "known", Modifier.weight(1f), StatusColors.Known) {
                    vm.setSource(PlaylistSource("known", "Known", WordStatus.KNOWN))
                }
                CompactChip("★", stats.favorites, source.key == "fav", Modifier.weight(0.6f)) {
                    vm.setSource(PlaylistSource("fav", "Starred", favOnly = true))
                }
            }
        }

        item {
            SectionLabel("Mode")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeButton("Whole list", scope == PlayScope.LIST, Modifier.weight(1f)) {
                    vm.setScope(PlayScope.LIST)
                }
                ModeButton("One word", scope == PlayScope.SINGLE, Modifier.weight(1f)) {
                    vm.setScope(PlayScope.SINGLE)
                }
            }
        }

        item {
            VSpace(14)
            NowPlayingCard(vm, onOpenPlayer)
        }

        if (playlist.isEmpty()) {
            item {
                EmptyState(icon = "🗂", title = "This list is empty", body = "Pick another filter above.")
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${playlist.size} words",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "PLAY ALL",
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { vm.playAll(); onOpenPlayer() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            itemsIndexed(playlist, key = { _, w -> w.id }) { i, w ->
                val isCurrent = playback.current?.id == w.id
                TrackRow(
                    index = i,
                    word = w.word,
                    subtitle = w.primaryAr.ifBlank { w.primaryEn },
                    status = w.status,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && playback.isPlaying,
                    onClick = { vm.playFrom(i); onOpenPlayer() }
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        fontSize = 10.sp
    )
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** بطاقة "قيد التشغيل" — أزرار النقل موزّعة بالتساوي في صف واحد */
@Composable
private fun NowPlayingCard(vm: ListenViewModel, onExpand: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onExpand)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.hasQueue) "${state.index + 1} / ${state.queue.size}" else "Ready",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            HSpace(6)
            Icon(
                Icons.Filled.OpenInFull, "Open player",
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        VSpace(8)
        Text(
            state.current?.word ?: "Tap play to start",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        /*
         * هنا يضغط المستخدم تشغيل، وهنا ينتظر.
         *
         * الشريط وحده لا يقول ما يجري، والصمت الذي يسبق أول كلمة كان يُقرأ
         * عطلاً لا انتظاراً. سطر واحد يشرح يكفي لقلب التجربة.
         */
        val sub = state.current?.subtitle.orEmpty()
        when {
            state.preparing && state.readyCount == 0 -> Text(
                if (state.prepareTotal > 0)
                    "Building the first card · ${state.prepareDone} of ${state.prepareTotal}"
                else "Building the first card…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            sub.isNotBlank() -> Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        VSpace(12)
        if (state.preparing) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = {
                    if (state.prepareTotal > 0) state.prepareDone.toFloat() / state.prepareTotal else 0f
                },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        VSpace(12)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::previous, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(28.dp))
            }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { vm.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                if (state.preparing) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            IconButton(onClick = vm::next, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    word: String,
    subtitle: String,
    status: WordStatus,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp))
                .background(StatusColors.of(status))
        )
        HSpace(12)
        Icon(
            if (isPlaying) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
            null,
            Modifier.size(18.dp),
            tint = if (isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(
                word,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
