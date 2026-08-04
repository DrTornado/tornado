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
import com.tornado.vocab.data.SyncCoordinator
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SyncButtonViewModel(app: Application) : AndroidViewModel(app) {

    /*
     * الحالة تأتي من المنسّق لا من هذا الزرّ.
     *
     * كانت محلّية هنا، فيدور الزرّ عند الضغط وحده وتجري المزامنة التلقائية
     * بصمت تامّ. فيسأل المستخدم «هل زامن عند الفتح؟» ولا شيء على الشاشة
     * يجيبه — وقد سأل. والآن يدور في كل مرّة، ضغطها أو جرت من نفسها.
     */
    val busy = SyncCoordinator.busy
    val toast = SyncCoordinator.lastResult

    fun sync() = viewModelScope.launch {
        SyncCoordinator.syncNow(getApplication(), announce = true)
    }

    fun clearToast() = SyncCoordinator.consumeResult()
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
