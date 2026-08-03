package com.tornado.vocab.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tornado.vocab.data.SyncResult
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SyncButtonViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.tornado
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun sync() = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        val repo = runCatching { container.settings.syncRepo() }.getOrDefault("")
        val r = runCatching {
            container.sync.repo = repo
            container.sync.sync(push = container.sync.canPush)
        }.getOrElse { SyncResult.Failed(it.message?.take(60) ?: "No connection") }

        // الصوت يُزامَن مع الكلمات في نفس الضغطة — فصلهما يعني زرّين لعمل واحد
        val audio = runCatching {
            container.audioSync.repo = repo
            container.audioSync.sync()
        }.getOrNull()

        // الملاحظات مع الكلمات في الضغطة نفسها — زرّ يحمل نصف المحتوى يمنح ثقة كاذبة
        runCatching {
            container.noteSync.repo = repo
            if (container.noteSync.canPull) {
                container.noteSync.sync(push = container.noteSync.canPush)
            }
        }

        /*
         * الزرّ يجلب المعاني المحدَّثة أيضاً، لا الكلمات الجديدة وحدها.
         *
         * كان الإثراء يجري إن وصلت كلمات من الكمبيوتر فقط. فمن حسّنّا محرّكه
         * ولم تصله كلمة جديدة يبقى على بطاقاته القديمة بلا سبيل لتحديثها —
         * يضغط «مزامنة» فلا يتغيّر شيء، ويظنّ التحسين لم يصل. وقد ظنّه.
         *
         * الآن كل ضغطة تمرّ على المكتبة: ما بُني بمحرّك أقدم يُعاد بناؤه،
         * وما هو محدَّث لا يُمسّ. والعمل بالخلفية فلا ينتظره أحد.
         */
        container.appScope.launch { runCatching { container.enricher.runUntilComplete() } }

        _busy.value = false
        _toast.value = when (r) {
            is SyncResult.Success -> {
                val words = if (r.pulled == 0 && r.pushed == 0 && r.deleted == 0) "Up to date"
                else "+${r.pulled} words"
                val clips = audio?.let {
                    if (it.downloaded > 0 || it.uploaded > 0)
                        " · audio ↓${it.downloaded} ↑${it.uploaded}" else ""
                }.orEmpty()
                words + clips
            }
            SyncResult.NotConfigured -> "Add your repository in Settings"
            is SyncResult.Failed -> r.message
        }
    }

    fun clearToast() { _toast.value = null }
}

/**
 * زر المزامنة في شريط العنوان.
 *
 * موضعه وشكله منقولان عن نسخة الويب عمداً: المستخدم يضيف كلمات على حاسوبه
 * ثم يمدّ يده إلى نفس الركن في جواله. تغيير المكان يعني تعلّم عادة جديدة
 * بلا مقابل، ودفن الزرّ في الإعدادات يعني ألّا يُستعمل أصلاً.
 *
 * والمزامنة تجري تلقائياً عند كل فتح؛ هذا الزر لمن أضاف كلمة والتطبيق مفتوح
 * فلا يريد إغلاقه وفتحه ليراها.
 */
@Composable
fun SyncButton() {
    val vm: SyncButtonViewModel = viewModel()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()

    Row(
        Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = !busy) { vm.sync() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(
                "🔄 Sync",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    // نتيجة المزامنة تُقال مرة ثم تختفي — لا تستحق نافذة تُغلق بضغطة
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }
}
