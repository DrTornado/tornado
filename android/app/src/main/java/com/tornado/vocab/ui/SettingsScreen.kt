package com.tornado.vocab.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.audio.HumanAudioRepository
import com.tornado.vocab.audio.NarrationRepository
import com.tornado.vocab.audio.TtsSynthesizer
import com.tornado.vocab.audio.TtsVoiceInfo
import com.tornado.vocab.audio.VoiceChain
import com.tornado.vocab.data.AppSettings
import com.tornado.vocab.data.AudioSettings
import com.tornado.vocab.data.ThemeMode
import com.tornado.vocab.tornado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val voices: List<TtsVoiceInfo> = emptyList(),
    val engines: List<Pair<String, String>> = emptyList(),
    val cacheMb: Long = 0,
    val cachedCards: Int = 0,
    val humanClips: Int = 0,
    val humanMb: Long = 0,
    val busyMessage: String? = null,
    val toast: String? = null,
    // ===== المزامنة مع الكمبيوتر =====
    val syncRepo: String = com.tornado.vocab.data.GitHubSync.DEFAULT_REPO,
    val hasSyncToken: Boolean = false,
    val maskedSyncToken: String = "",
    val syncing: Boolean = false,
    val syncStatus: String? = null,
    // ===== مصدر جمل الأمثلة =====
    val pendingGaps: Int = 0,
    val kokoroMb: Long = 0
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = app.tornado.settings
    private val repo = app.tornado.repository
    private val tts = TtsSynthesizer(app)
    val humanAudio = HumanAudioRepository(app)
    private val keyStore = com.tornado.vocab.data.SecureKeyStore(app)
    private val narration =
        NarrationRepository(app, VoiceChain(humanAudio, tts))


    // ===== المزامنة مع الكمبيوتر =====

    private val sync = com.tornado.vocab.data.GitHubSync(repo, keyStore)

    // ===== الصوت الممتاز (كوكورو) =====

    // نسخة الحاوية لا نسخة جديدة: نسختان تعنيان حالة تنزيل لا تصل، ونموذجاً
    // بمئة وأربعة عشر ميغابايت مُحمَّلاً في الذاكرة مرتين
    private val kokoro = app.tornado.kokoro
    val kokoroState = kokoro.state

    fun installKokoro() = viewModelScope.launch {
        kokoro.install()
        refresh()
    }

    fun removeKokoro() = viewModelScope.launch {
        kokoro.remove()
        refresh()
    }

    fun setKokoroVoice(sid: Int) = viewModelScope.launch { settings.setKokoroSid(sid) }

    fun setUseKokoro(v: Boolean) = viewModelScope.launch { settings.setUseKokoro(v) }


    /**
     * يولّد عيّنة بالصوت المختار ويشغّلها.
     * زرّ اسمه «جرّب الصوت» يجب أن يُسمعك الصوت — الدرس نفسه من كل زرّ تجربة
     * صامت أصلحناه في هذا التطبيق.
     */
    fun testKokoro() = viewModelScope.launch {
        val sid = settings.audio.first().kokoroSid
        kokoro.sid = sid
        val sample = java.io.File(getApplication<Application>().cacheDir, "kokoro-sample.wav")
        val ok = kokoro.synthesize(
            "Tornado. Your vocabulary player, now with a premium voice.", sample
        )
        if (ok) {
            runCatching {
                android.media.MediaPlayer().apply {
                    setDataSource(sample.absolutePath)
                    prepare(); start()
                    setOnCompletionListener { it.release() }
                }
            }
        } else {
            _ui.value = _ui.value.copy(toast = "Could not generate a sample")
        }
    }

    fun setSyncRepo(v: String) = viewModelScope.launch {
        settings.setSyncRepo(v)
        sync.repo = v
        _ui.value = _ui.value.copy(syncRepo = v, syncStatus = null)
    }

    fun saveSyncToken(token: String) = viewModelScope.launch {
        keyStore.setKey(com.tornado.vocab.data.GitHubSync.PROVIDER, token)
        refreshSyncUi()
        // نتحقق فوراً: رمز خاطئ يُكتشف الآن لا عند أول مزامنة يحتاجها المستخدم
        _ui.value = _ui.value.copy(syncing = true)
        val r = sync.check()
        _ui.value = _ui.value.copy(syncing = false, syncStatus = describe(r, checkOnly = true))
    }

    fun clearSyncToken() = viewModelScope.launch {
        keyStore.clearKey(com.tornado.vocab.data.GitHubSync.PROVIDER)
        refreshSyncUi()
        _ui.value = _ui.value.copy(syncStatus = null)
    }

    fun syncNow() = viewModelScope.launch {
        _ui.value = _ui.value.copy(syncing = true, syncStatus = null)
        sync.repo = settings.syncRepo()
        val r = sync.sync()
        _ui.value = _ui.value.copy(syncing = false, syncStatus = describe(r, checkOnly = false))
        refresh()
    }

    private suspend fun refreshSyncUi() {
        val p = com.tornado.vocab.data.GitHubSync.PROVIDER
        sync.repo = settings.syncRepo()
        _ui.value = _ui.value.copy(
            syncRepo = sync.repo,
            hasSyncToken = keyStore.hasKey(p),
            maskedSyncToken = keyStore.maskedKey(p)
        )
    }

    private fun describe(r: com.tornado.vocab.data.SyncResult, checkOnly: Boolean): String =
        when (r) {
            is com.tornado.vocab.data.SyncResult.Success ->
                if (checkOnly) "✓ Connected"
                else "✓ Added ${r.pulled} · sent ${r.pushed} · removed ${r.deleted}"
            com.tornado.vocab.data.SyncResult.NotConfigured -> "✗ Enter the repository and token first"
            is com.tornado.vocab.data.SyncResult.Failed -> "✗ ${r.message}"
        }

    fun setVoiceStrategy(v: String) = viewModelScope.launch { settings.setVoiceStrategy(v) }

    val audio: StateFlow<AudioSettings> =
        settings.audio.stateIn(viewModelScope, SharingStarted.Eagerly, AudioSettings())
    val appSettings: StateFlow<AppSettings> =
        settings.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()


    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val voices = tts.availableVoices()
        val engines = tts.availableEngines()
        val stats = withContext(Dispatchers.IO) {
            listOf(
                narration.cacheSizeBytes(),
                narration.cachedCardCount().toLong(),
                humanAudio.cachedCount().toLong(),
                humanAudio.cachedBytes(),
            )
        }
        _ui.value = _ui.value.copy(
            syncRepo = settings.syncRepo(),
            hasSyncToken = keyStore.hasKey(com.tornado.vocab.data.GitHubSync.PROVIDER),
            maskedSyncToken = keyStore.maskedKey(com.tornado.vocab.data.GitHubSync.PROVIDER),
            pendingGaps = runCatching { getApplication<Application>().tornado.enricher.pendingCount() }
                .getOrDefault(0),
            kokoroMb = runCatching { kokoro.installedBytes() / 1_048_576 }.getOrDefault(0),
            voices = voices, engines = engines,
            cacheMb = stats[0] / (1024 * 1024),
            cachedCards = stats[1].toInt(),
            humanClips = stats[2].toInt(),
            humanMb = stats[3] / (1024 * 1024),
        )
    }

    fun setHumanOnly(v: Boolean) = viewModelScope.launch { settings.setHumanOnly(v) }

    fun clearHumanCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) { humanAudio.clear() }
        _ui.value = _ui.value.copy(toast = "Human recordings cleared")
        refresh()
    }

    fun setSpeed(v: Float) = viewModelScope.launch { settings.setSpeed(v) }
    fun setSpeakArabic(v: Boolean) = viewModelScope.launch { settings.setSpeakArabic(v) }
    fun setPrefetch(v: Boolean) = viewModelScope.launch { settings.setPrefetch(v) }
    fun setEnglishVoice(v: String) = viewModelScope.launch { settings.setEnglishVoice(v); refresh() }
    fun setArabicVoice(v: String) = viewModelScope.launch { settings.setArabicVoice(v); refresh() }
    fun setEngine(v: String) = viewModelScope.launch { settings.setEngine(v); refresh() }
    fun setCacheLimit(mb: Int) = viewModelScope.launch { settings.setCacheLimitMb(mb) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { settings.setTheme(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { settings.setDynamicColor(v) }
    fun setQuizDueOnly(v: Boolean) = viewModelScope.launch { settings.setQuizDueOnly(v) }
    fun setQuizLimit(v: Int) = viewModelScope.launch { settings.setQuizLimit(v) }

    fun clearAudioCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) { narration.clearCache() }
        _ui.value = _ui.value.copy(toast = "Audio cache cleared")
        refresh()
    }

    fun resetProgress() = viewModelScope.launch {
        repo.resetAllProgress()
        _ui.value = _ui.value.copy(toast = "All review progress reset")
    }

    suspend fun exportJson(): String = repo.exportJson()

    fun importJson(text: String, replace: Boolean) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busyMessage = "Importing…")
        val words = repo.parseExport(text)
        if (words.isEmpty()) {
            _ui.value = _ui.value.copy(busyMessage = null, toast = "No words found in that file")
            return@launch
        }
        val message = if (replace) {
            repo.replaceAll(words); "Replaced library with ${words.size} words"
        } else {
            val r = repo.mergeIncoming(words)
            "Merged: ${r.added} added, ${r.updated} updated"
        }
        _ui.value = _ui.value.copy(busyMessage = null, toast = message)
    }

    fun clearToast() { _ui.value = _ui.value.copy(toast = null) }

    override fun onCleared() {
        // مشغّل العيّنات حُذف مع القسم السحابي
        tts.shutdown()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAttribution: () -> Unit = {}
) {
    val audio by vm.audio.collectAsStateWithLifecycle()
    val app by vm.appSettings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            vm.viewModelScope.launch {
                val text = vm.exportJson()
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.viewModelScope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (text != null) pendingImport = text
            }
        }
    }

    ui.toast?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2_500); vm.clearToast() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        ) {
            ui.toast?.let {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(12.dp)
                ) { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }


            /*
             * كان هنا قسم لتنزيل أصوات عصبية تعمل على الجهاز.
             *
             * حُذف لأنه كان يبيع للمستخدم انتظاراً بلا مقابل: اثنان وثمانون
             * ميغابايت تُنزَّل لصوت أضعف من محرك جهازه نفسه. والاحتياط دون
             * إنترنت قائم كما هو عبر محرك الجهاز — بلا تنزيل ولا إعداد.
             */

            // ===== الصوت =====
            SettingsSection("Playback")

            SettingRow("Reading speed", "${audio.speed}x") {
                val opts = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.15f, 1.3f)
                val next = opts[(opts.indexOfFirst { kotlin.math.abs(it - audio.speed) < 0.01f }
                    .takeIf { it >= 0 }?.plus(1) ?: 0) % opts.size]
                vm.setSpeed(next)
            }

            SwitchRow(
                "Speak Arabic meanings",
                "Reads the Arabic translation after each English line. Needs an Arabic voice installed.",
                audio.speakArabic,
                vm::setSpeakArabic
            )

            SwitchRow(
                "Prepare next word in advance",
                "Builds the next card's audio while the current one plays, so switching is instant.",
                audio.prefetchEnabled,
                vm::setPrefetch
            )

            VoicePicker(
                label = "English voice",
                current = audio.englishVoice,
                voices = ui.voices.filter { it.locale.startsWith("en") },
                onPick = vm::setEnglishVoice
            )
            VoicePicker(
                label = "Arabic voice",
                current = audio.arabicVoice,
                voices = ui.voices.filter { it.locale.startsWith("ar") },
                onPick = vm::setArabicVoice
            )

            SettingsSection("Storage")

            ActionRow(
                "Human recordings",
                "${ui.humanClips} clips · ${ui.humanMb} MB — tap to clear"
            ) { vm.clearHumanCache() }

            SettingRow(
                "Audio cache",
                "${ui.cacheMb} MB · ${ui.cachedCards} cards · limit ${audio.cacheLimitMb} MB"
            ) {
                val opts = listOf(128, 256, 512, 1024, 2048)
                val next = opts[(opts.indexOf(audio.cacheLimitMb).takeIf { it >= 0 }?.plus(1) ?: 2) % opts.size]
                vm.setCacheLimit(next)
            }
            ActionRow("Clear audio cache", "Frees space. Cards rebuild on next play.") {
                vm.clearAudioCache()
            }
            ActionRow(
                "Install or manage voices",
                "Opens Android's text-to-speech settings."
            ) {
                runCatching {
                    context.startActivity(
                        Intent("com.android.settings.TTS_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }

            // ===== المراجعة =====
            SettingsSection("Review")
            SwitchRow(
                "Only show cards that are due",
                "When off, every quiz round draws from your whole library.",
                app.quizDueOnly,
                vm::setQuizDueOnly
            )
            SettingRow("Cards per round", app.quizLimit.toString()) {
                val opts = listOf(10, 20, 40, 60, 100)
                val next = opts[(opts.indexOf(app.quizLimit).takeIf { it >= 0 }?.plus(1) ?: 2) % opts.size]
                vm.setQuizLimit(next)
            }

            // ===== المظهر =====
            SettingsSection("Appearance")
            SettingRow("Theme", app.theme.name.lowercase().replaceFirstChar { it.uppercase() }) {
                val next = when (app.theme) {
                    ThemeMode.SYSTEM -> ThemeMode.DARK
                    ThemeMode.DARK -> ThemeMode.LIGHT
                    ThemeMode.LIGHT -> ThemeMode.SYSTEM
                }
                vm.setTheme(next)
            }
            SwitchRow(
                "Use system colours",
                "Follows your wallpaper palette instead of the Tornado gold theme.",
                app.dynamicColor,
                vm::setDynamicColor
            )

            // ===== البيانات =====
            // ===== المزامنة مع الكمبيوتر =====
            SettingsSection("Sync with computer")
            GitHubSyncSection(
                repo = ui.syncRepo,
                hasToken = ui.hasSyncToken,
                maskedToken = ui.maskedSyncToken,
                busy = ui.syncing,
                status = ui.syncStatus,
                onSetRepo = vm::setSyncRepo,
                onSaveToken = vm::saveSyncToken,
                onClearToken = vm::clearSyncToken,
                onSyncNow = vm::syncNow
            )

            // ===== الصوت الممتاز =====
            SettingsSection("Premium voice")
            val kokoroState by vm.kokoroState.collectAsStateWithLifecycle()
            KokoroSection(
                state = kokoroState,
                currentSid = audio.kokoroSid,
                installedMb = ui.kokoroMb,
                useKokoro = audio.useKokoro,
                onSetEngine = vm::setUseKokoro,
                onInstall = vm::installKokoro,
                onRemove = vm::removeKokoro,
                onPickVoice = vm::setKokoroVoice,
                onTest = vm::testKokoro
            )

            // ===== مصدر الأمثلة =====
            SettingsSection("Example sentences")
            ExampleSourceSection(pendingGaps = ui.pendingGaps)

            SettingsSection("Your words")
            ActionRow("Export backup", "Saves a .json file compatible with the web version.") {
                exportLauncher.launch("tornado-words.json")
            }
            ActionRow("Import backup", "Merges a .json file — progress is never lost.") {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            ActionRow(
                "Reset all review progress",
                "Keeps every word but clears right/wrong counts and schedules.",
                danger = true
            ) { confirmReset = true }

            /*
             * الإسناد بندٌ ظاهر لا سطرٌ في «حول».
             *
             * رخص المشاع الإبداعي تجعل الإسناد شرطاً لصحة الاستعمال لا مجاملة،
             * ومن يقرأ معنىً ويريد أصله يجب أن يجد الطريق إليه بضغطة.
             */
            SettingsSection("About")
            ActionRow(
                "Sources & licences",
                "Where every meaning, example and recording comes from."
            ) { onOpenAttribution() }

            VSpace(32)
            Text(
                "Tornado 2.0 · native Android build",
                Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all progress?") },
            text = { Text("Every word stays, but all right/wrong counts and review dates are cleared.") },
            confirmButton = {
                TextButton(onClick = { vm.resetProgress(); confirmReset = false }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import words") },
            text = { Text("Merge keeps your current progress and adds anything new. Replace wipes the library first.") },
            confirmButton = {
                TextButton(onClick = { vm.importJson(text, replace = false); pendingImport = null }) {
                    Text("Merge")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.importJson(text, replace = true); pendingImport = null }) {
                    Text("Replace", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HSpace(12)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoicePicker(
    label: String,
    current: String,
    voices: List<TtsVoiceInfo>,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(
            label,
            when {
                voices.isEmpty() -> "None installed"
                current.isBlank() -> "System default"
                else -> current.substringAfterLast('-').take(20)
            }
        ) { if (voices.isNotEmpty()) open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("System default") },
                onClick = { onPick(""); open = false }
            )
            voices.take(20).forEach { v ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(v.name, fontSize = 13.sp)
                            Text(
                                v.locale + (if (v.isNetwork) " · needs internet" else " · offline"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { onPick(v.name); open = false }
                )
            }
        }
    }
}
