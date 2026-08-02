package com.tornado.vocab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * المزامنة مع نسخة الكمبيوتر.
 *
 * الجهازان لا يلتقيان مباشرة — لا شبكة محلية ولا كيبل — لكن نسخة الويب تحفظ
 * المكتبة في ملف واحد على مستودع المستخدم منذ البداية. فنقرأ الملف نفسه ونكتب
 * فيه، فتصير الإضافة على الكمبيوتر والاستماع على الجوال مكتبة واحدة.
 *
 * الرمز يُخزَّن مشفّراً كبقية المفاتيح ولا يغادر الجهاز إلا إلى GitHub.
 */
@Composable
fun GitHubSyncSection(
    repo: String,
    hasToken: Boolean,
    maskedToken: String,
    busy: Boolean,
    status: String?,
    onSetRepo: (String) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onSyncNow: () -> Unit
) {
    var tokenInput by remember { mutableStateOf("") }
    var repoInput by remember(repo) { mutableStateOf(repo) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {

        OutlinedTextField(
            value = repoInput,
            onValueChange = { repoInput = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Repository") },
            placeholder = { Text("owner/name") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                if (repoInput.isNotBlank() && repoInput != repo) {
                    TextButton(onClick = { onSetRepo(repoInput) }) { Text("Save") }
                }
            }
        )

        VSpace(12)

        /*
         * زر المزامنة ظاهر دائماً.
         *
         * السحب من المستودع لا يحتاج رمزاً، فإخفاء الزر حتى يُلصق الرمز كان
         * يحجب نصف الفائدة خلف خطوة إعداد لا يحتاجها ذلك النصف أصلاً.
         */
        Button(
            onClick = onSyncNow,
            enabled = !busy && repo.contains('/'),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(if (hasToken) "Sync now" else "Pull from computer")
        }

        /*
         * نطمئن المستخدم قبل أن نطلب منه شيئاً.
         *
         * أغلب من يتعلّم الإنجليزية لا يعرف GitHub ولا يريد أن يعرفه، ورؤية
         * حقل «مستودع» ورمز وصول تُقرأ تهديداً: «إن لم أفعل هذا ضاعت كلماتي».
         * وهي ليست كذلك — أندرويد يحفظ البيانات في حساب جوجل الذي يملكه
         * أصلاً. فنقول ذلك أولاً، ثم نعرض المزامنة لمن يريد الأكثر.
         */
        if (!repo.contains('/')) {
            VSpace(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle, null,
                    Modifier.size(18.dp), tint = StatusColors.Known
                )
                HSpace(8)
                Text(
                    "Your words are already backed up",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            VSpace(4)
            Text(
                "Android saves them to your Google account automatically and restores " +
                    "them when you install Tornado on a new phone — nothing to set up.\n\n" +
                    "The repository above is optional, and only for keeping a computer " +
                    "and a phone on one library at the same time.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        status?.let {
            VSpace(6)
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (it.startsWith("✓")) StatusColors.Known
                else MaterialTheme.colorScheme.error
            )
        }

        VSpace(14)

        if (!hasToken) {
            Text(
                "Words you add on the computer arrive with the button above. " +
                    "To send changes back the other way, add a token: on github.com open " +
                    "Settings → Developer settings → Personal access tokens → Fine-grained " +
                    "tokens, with Contents: Read and write on this repository only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpace(10)
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Access token") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            VSpace(8)
            Button(
                onClick = { onSaveToken(tokenInput); tokenInput = "" },
                enabled = tokenInput.length > 15,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save token") }
            VSpace(6)
            Text(
                "Encrypted on this device. Sent only to GitHub.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = StatusColors.Known)
                HSpace(8)
                Text(
                    "Two-way sync on  ·  $maskedToken",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            VSpace(4)
            // الحذف بتأكيد: لصق الرمز خطوة مملّة، ومسحه بالخطأ كلفة غير متكافئة
            var confirm by remember { mutableStateOf(false) }
            TextButton(
                onClick = { if (confirm) { onClearToken(); confirm = false } else confirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (confirm) "Tap again to remove the token" else "Remove token",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        }

        VSpace(6)
        Text(
            "Add words on the computer, listen on the phone — one library.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * مفتاح مصدر جمل الأمثلة.
 *
 * منفصل عن مفاتيح الصوت عمداً: هذا لا يكلّف شيئاً ولا يحتاج بطاقة، وخلطه بها
 * يجعله يبدو التزاماً مالياً وهو ليس كذلك.
 */
/**
 * الصوت الممتاز — كوكورو.
 *
 * أفضل صوت مفتوح للإنجليزية اليوم، والمستخدم سمعه بأذنه وطلبه. النموذج
 * (~١٤٠ ميغابايت) تنزيل اختياري: من يكفيه محرك جهازه لا يدفع شيئاً.
 */
@Composable
fun KokoroSection(
    state: com.tornado.vocab.audio.KokoroInstallState,
    currentSid: Int,
    installedMb: Long,
    useKokoro: Boolean,
    onSetEngine: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onPickVoice: (Int) -> Unit,
    onTest: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {

        /*
         * اختيار المحرك أولاً — قبل التنزيل وقبل الأصوات.
         *
         * الصوت أهم أجزاء هذا التطبيق، ومن يريد محرك جهازه يجب أن يجد الخيار
         * في أول سطر لا بعد شرح طويل عن نموذج لا ينوي تنزيله أصلاً.
         */
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "Kokoro", false to "Device voice").forEach { (isKokoro, label) ->
                val selected = useKokoro == isKokoro
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSetEngine(isKokoro) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (isKokoro) "Highest quality" else "No download",
                        fontSize = 10.sp,
                        color = if (selected)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        VSpace(14)

        if (!useKokoro) {
            Text(
                "Using your device's built-in voice. Nothing to download. " +
                    "Kokoro stays installed if you switch back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        when (state) {
            is com.tornado.vocab.audio.KokoroInstallState.NotInstalled,
            is com.tornado.vocab.audio.KokoroInstallState.Failed -> {
                Text(
                    "Kokoro — the best open English voice available. Downloads once " +
                        "(~${com.tornado.vocab.audio.KokoroEngine.DOWNLOAD_MB} MB), " +
                        "then works fully offline. British and American voices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state is com.tornado.vocab.audio.KokoroInstallState.Failed) {
                    VSpace(6)
                    Text(
                        "✗ ${state.reason}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                VSpace(10)
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Download voice (${com.tornado.vocab.audio.KokoroEngine.DOWNLOAD_MB} MB)")
                }
            }

            is com.tornado.vocab.audio.KokoroInstallState.Downloading -> {
                val pct = if (state.total > 0)
                    (state.bytes * 100 / state.total).toInt() else 0
                Text(
                    "Downloading… ${state.bytes / 1_048_576} of " +
                        "${(state.total / 1_048_576).coerceAtLeast(1)} MB",
                    style = MaterialTheme.typography.bodyMedium
                )
                VSpace(8)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is com.tornado.vocab.audio.KokoroInstallState.WaitingForWifi -> {
                Text(
                    "Waiting for Wi-Fi to download the voice " +
                        "(${com.tornado.vocab.audio.KokoroEngine.DOWNLOAD_MB} MB). " +
                        "Your device voice is being used until then.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(10)
                // من يريد إنفاق باقته يقرّر بنفسه — نمنع المفاجأة لا الاختيار
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Download now on mobile data")
                }
            }

            is com.tornado.vocab.audio.KokoroInstallState.Extracting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    HSpace(10)
                    Text("Installing…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is com.tornado.vocab.audio.KokoroInstallState.Installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = StatusColors.Known)
                    HSpace(8)
                    Text(
                        "Premium voice on · ${installedMb} MB",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                VSpace(10)
                // شبكة الأصوات — بريطانية أولاً لأنها سبب الطلب
                com.tornado.vocab.audio.KokoroEngine.VOICES.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { v ->
                            val selected = v.sid == currentSid
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { onPickVoice(v.sid) }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    v.name,
                                    fontSize = 13.sp,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    v.accent,
                                    fontSize = 10.sp,
                                    color = if (selected)
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                VSpace(4)
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                    Text("Test this voice")
                }
                VSpace(2)
                var confirm by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { if (confirm) { onRemove(); confirm = false } else confirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (confirm) "Tap again to delete the voice" else "Remove download",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        }
        VSpace(6)
        Text(
            "Existing cards rebuild with the new voice the next time they play.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ExampleSourceSection(pendingGaps: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle, null, Modifier.size(18.dp),
                tint = if (pendingGaps == 0) StatusColors.Known
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            HSpace(8)
            Text(
                if (pendingGaps > 0) "$pendingGaps cards still incomplete"
                else "Every card is complete",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        VSpace(6)
        Text(
            "Missing examples, pronunciations and levels are filled in quietly " +
                "while you use the app — from Tatoeba, whose sentences are written " +
                "by volunteers and sometimes carry a real human recording.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
