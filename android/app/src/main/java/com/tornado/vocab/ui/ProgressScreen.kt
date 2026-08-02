package com.tornado.vocab.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.tornado.vocab.data.AppSettings
import com.tornado.vocab.data.LibraryStats
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ProgressViewModel(app: Application) : AndroidViewModel(app) {
    val stats: StateFlow<LibraryStats> = app.tornado.repository.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats())
    val settings: StateFlow<AppSettings> = app.tornado.settings.app
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
}

/**
 * لوحة التقدّم — لا وجود لها في تطبيق الويب إطلاقاً.
 *
 * قيمتها ليست في الأرقام بل في جعل المجهول مرئياً: كم كلمة أتقنت فعلاً،
 * وكم تنتظر المراجعة اليوم، وهل السلسلة اليومية ما زالت قائمة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(vm: ProgressViewModel, onBack: () -> Unit, onStartQuiz: () -> Unit) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val app by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${stats.total}", "Words", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatTile("${stats.dueNow}", "Due now", StatusColors.Missed, Modifier.weight(1f))
                StatTile("${app.streakDays}", "Day streak", StatusColors.Known, Modifier.weight(1f))
            }

            VSpace(20)
            Text("Library breakdown", style = MaterialTheme.typography.titleMedium)
            VSpace(8)
            StatBar("Known", stats.known, stats.total, StatusColors.Known)
            StatBar("Missed", stats.missed, stats.total, StatusColors.Missed)
            StatBar("Not tested yet", stats.newCount, stats.total, StatusColors.New)
            StatBar("Starred", stats.favorites, stats.total, MaterialTheme.colorScheme.primary)

            VSpace(24)
            Text("Answers so far", style = MaterialTheme.typography.titleMedium)
            VSpace(8)
            val answered = stats.totalRight + stats.totalWrong
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Metric("${if (answered == 0) 0 else stats.totalRight * 100 / answered}%", "Accuracy")
                Metric("${stats.totalRight}", "Correct")
                Metric("${stats.totalWrong}", "Missed")
                Metric("${app.reviewedToday}", "Today")
            }

            VSpace(20)
            val goalProgress = if (app.dailyGoal <= 0) 0f
            else (app.reviewedToday.toFloat() / app.dailyGoal).coerceIn(0f, 1f)
            Text("Daily goal", style = MaterialTheme.typography.titleMedium)
            VSpace(8)
            StatBar(
                "${app.reviewedToday} of ${app.dailyGoal} cards",
                app.reviewedToday.coerceAtMost(app.dailyGoal),
                app.dailyGoal,
                MaterialTheme.colorScheme.primary
            )
            if (goalProgress >= 1f) {
                VSpace(6)
                Text("🎯 Goal reached today", color = StatusColors.Known)
            }

            VSpace(28)
            androidx.compose.material3.Button(
                onClick = onStartQuiz,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (stats.dueNow > 0) "Review ${stats.dueNow} due card${if (stats.dueNow > 1) "s" else ""}"
                    else "Start a practice round"
                )
            }
            VSpace(32)
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
