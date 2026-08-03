package com.tornado.vocab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.vocab.audio.PlaybackUiState
import com.tornado.vocab.data.ListRepeat
import com.tornado.vocab.data.WordStatus

/**
 * شاشة التشغيل.
 *
 * التوزيع يتبع بنية مشغّل APEUni: شريط علوي بعدّاد وموضع، ثم المحتوى نفسه
 * كبيراً في المنتصف، ثم صف خيارات، ثم شريط تقدّم وزمن، ثم صف نقل من خمسة
 * عناصر موزّعة بالتساوي حول زر تشغيل دائري كبير.
 *
 * المبدأ الحاكم: المحتوى المسموع هو البطل، وكل شيء آخر ينزوي حوله.
 */
@Composable
fun PlayerScreen(
    vm: ListenViewModel,
    onCollapse: () -> Unit,
    onOpenWord: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onOpenNotesList: () -> Unit = {}
) {
    val state by vm.playback.collectAsStateWithLifecycle()
    /// نوع المحتوى الجاري: يقرّر النص المعروض ومعنى زرّ SHORT/FULL معاً
    val isNote = state.current?.isNote == true
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showRepeat by remember { mutableStateOf(false) }

    /*
     * وهج دافئ أسفل المشغّل.
     *
     * كان ينتهي بلون ثانوي أزرق مخضرّ اختير حين كانت الخلفية زرقاء رمادية.
     * وبعد تدفئة الخلفية صار يقطعها بكتلة باردة ظاهرة الحدّ — لونان من عائلتين
     * مختلفتين على شاشة واحدة.
     *
     * الوهج الآن من الذهبي نفسه وبشفافية خفيفة: يبقى الإحساس بالعمق ويختفي
     * التصادم، لأن اللون صار من جنس ما حوله.
     */
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        )
    )

    Column(Modifier.fillMaxSize().background(gradient)) {

        // ===== ١ — الشريط العلوي: رجوع، عدّاد، عنوان، حفظ =====
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(26.dp))
            }
            Text(
                /*
                 * العدّاد يعدّ ما يراه المستخدم لا ما يبنيه المشغّل.
                 *
                 * الملاحظة تُقسَّم داخلياً إلى جمل ليبدأ الصوت بسرعة، وذلك شأن
                 * المحرّك. لكن العدّاد كان يعرض تلك الجمل: «١ من ١٠» لمن عنده
                 * ملاحظتان — رقمٌ لا يقابل شيئاً على شاشته.
                 */
                if (!state.hasQueue) "0/0"
                else if (state.current?.isNote == true) {
                    val ids = state.queue.map { it.id }
                    val total = ids.distinct().size
                    val here = ids.take(state.index + 1).distinct().size
                    "$here/$total"
                } else "${state.index + 1}/${state.queue.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))

            /*
             * زر التصنيف السريع.
             * يدور: معروفة (أخضر) ← أخطأت (أحمر) ← جديدة (افتراضي).
             * يكتب في نفس القيمة الداخلية التي تكتبها الأزرار الثلاثة أسفله،
             * فلا يمكن أن يتعارض الاثنان — كلاهما نداء واحد لـsetStatus.
             */
            // نحتفظ بشكل الإشارة المرجعية الأصلي؛ اللون والامتلاء وحدهما يحملان الحالة
            val currentStatus = state.currentStatus
            IconButton(onClick = { vm.cycleStatus() }) {
                Icon(
                    if (currentStatus == WordStatus.NEW) Icons.Filled.BookmarkBorder
                    else Icons.Filled.Bookmark,
                    "Change status",
                    Modifier.size(28.dp),
                    tint = if (currentStatus == WordStatus.NEW)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else StatusColors.of(currentStatus)
                )
            }
        }

        // ===== ٢ — تصنيف الكلمة بالألوان =====
        // الأزرار تعكس الحالة الفعلية دائماً لأنها تقرأ نفس القيمة التي يكتبها الزر أعلاه.
        // وتُخفى فوق مقاطع الملاحظات: التصنيف شأن كلمات تُراجَع، والنصّ لا جدولة له.
        if (state.hasQueue && state.current?.isNote != true) {
            StatusPicker(
                current = state.currentStatus,
                onPick = { vm.setStatus(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp)
            )
        }

        // ===== ٣ — المحتوى: البطل =====
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            /*
             * حال لا جلسة.
             *
             * فتح المشغّل قبل بدء التشغيل كان يعرض شرطة صغيرة وسط شاشة فارغة
             * و«٠/٠» — لا خطأ ولا تفسير ولا ما يُفعل. والمستخدم يقرأ الفراغ
             * عطلاً لا حالة.
             */
            if (!state.hasQueue) {
                VSpace(80)
                Text(
                    "Nothing queued",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
                VSpace(10)
                Text(
                    "Pick a list in Listen and press play — it will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    state.current?.word ?: "—",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 48.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            /*
             * تحت العنوان: الجملة المنطوقة الآن للملاحظات، والمعنى للكلمات.
             *
             * المقتطف الثابت (أول سبعين حرفاً) كان أسوأ الخيارات: يبقى جامداً
             * بينما يتقدّم الصوت دقائق، فيقرأ المستخدم شيئاً ويسمع غيره. النص
             * الذي لا يلاحق الصوت يوهم بالمواكبة ولا يواكب.
             */
            // نوع المحتوى يقرّر معنى عدة عناصر أدناه — يُشتقّ مرة واحدة
            // (معرَّف في أعلى الدالة)
            val sub = if (isNote) state.spokenLine else state.current?.subtitle.orEmpty()
            if (sub.isNotBlank() && (isNote || state.showTranslation)) {
                VSpace(18)
                Text(
                    sub,
                    fontSize = if (isNote) 19.sp else 20.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = if (isNote) 30.sp else 32.sp
                )
            }
            /*
             * التحضير يُقال بالكلمات لا بدائرة تدور.
             *
             * أخطر ما بقي في التطبيق ليس عطلاً بل صمتاً يُقرأ عطلاً: يضغط
             * المستخدم تشغيل، فيرى دائرة تدور بلا خبر، فيستنتج أن الصوت لا
             * يعمل — وقد حدث هذا فعلاً في الاختبار. الانتظار المفهوم يُحتمل،
             * والانتظار الغامض يُهجَر.
             */
            if (state.preparing) {
                VSpace(40)
                CircularProgressIndicator(
                    Modifier.size(44.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
                VSpace(16)
                Text(
                    if (state.readyCount == 0) "Building the first card…"
                    else "Building ahead — ${state.readyCount} ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (state.readyCount == 0 && state.prepareTotal > 0) {
                    VSpace(6)
                    Text(
                        "${state.prepareDone} of ${state.prepareTotal} parts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                VSpace(6)
                Text(
                    "Each word is built once, then plays instantly forever.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            if (state.skippedCount > 0) {
                VSpace(24)
                Text(
                    "${state.skippedCount} skipped — no human recording found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ===== ٤ — صف الخيارات =====
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            /*
             * الزرّ يغيّر معناه بتغيّر المحتوى.
             *
             * فوق كلمة: FULL شرح كامل وSHORT مختصر.
             * وفوق ملاحظة: هو وحدة التكرار — فقرة أو جملة — ولا معنى له إن
             * كان Say على ×١ لأنه لا تكرار أصلاً، فيُعطَّل ويبهت بدل أن يبقى
             * زرّاً يُضغط بلا أثر.
             */
            val unitEnabled = !isNote || state.wordRepeat > 1
            OutlinePill(
                text = when {
                    !isNote -> if (state.detailed) "FULL" else "SHORT"
                    state.detailed -> "FULL ¶"
                    else -> "SHORT ·"
                },
                modifier = Modifier.weight(0.8f),
                active = state.detailed && unitEnabled,
                enabled = unitEnabled
            ) { vm.toggleDetail() }
            OutlinePill(
                "Say ×${state.wordRepeat}",
                Modifier.weight(0.9f),
                active = state.wordRepeat > 1
            ) { showRepeat = true }
            OutlinePill(
                if (state.sleepActive) formatTime(state.sleepRemainingMs) else "Sleep",
                Modifier.weight(0.8f),
                active = state.sleepActive
            ) { showSleep = true }
            OutlinePill("${state.speed}X", Modifier.weight(0.7f)) { showSpeed = true }
        }

        /*
         * صفّ التحكّم الثاني.
         *
         * الخلط وتكرار القائمة والقفز عشر ثوانٍ وإخفاء المعنى — أربع ميزات
         * مبنية بالكامل في الخدمة ومختبَرة، ولم يكن يصلها زرّ واحد. شيفرة
         * تعمل بلا باب إليها ليست ميزة بل نفقة.
         *
         * ومكانها هنا مقصود: فوق شريط التقدّم لا داخل صفّ النقل، فلا يزدحم
         * الصفّ الأساسي ولا يحتاج المستخدم تمريراً أفقياً.
         */
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::toggleShuffle, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Shuffle, "Shuffle", Modifier.size(22.dp),
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = vm::rewind, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Replay10, "Back 10 seconds", Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = vm::fastForward, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Forward10, "Forward 10 seconds", Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = vm::toggleTranslation, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (state.showTranslation) Icons.Filled.Visibility
                    else Icons.Filled.VisibilityOff,
                    "Show meaning", Modifier.size(22.dp),
                    tint = if (state.showTranslation) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = vm::cycleListRepeat, modifier = Modifier.size(44.dp)) {
                Icon(
                    // الأيقونة تطابق الوضع: سهمان للكل، سهمان برقم ١ للواحدة
                    if (state.listRepeat == ListRepeat.ONE) Icons.Filled.RepeatOne
                    else Icons.Filled.Repeat,
                    ListRepeat.label(state.listRepeat), Modifier.size(22.dp),
                    tint = if (state.listRepeat != ListRepeat.OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ===== ٥ — شريط التقدّم والزمن =====
        SeekControl(state) { vm.seekTo(it) }

        // ===== ٦ — صف النقل: خمسة عناصر موزّعة بالتساوي =====
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // فوق ملاحظة يعود الزرّ لقائمة الملاحظات؛ فوق كلمة يفتح طابور التشغيل
            IconButton(
                onClick = {
                    if (state.current?.isNote == true) onOpenNotesList() else showQueue = true
                },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", Modifier.size(28.dp))
            }
            IconButton(onClick = vm::previous, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
            }
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onBackground)
                    .clickable { vm.togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                if (state.preparing) {
                    CircularProgressIndicator(
                        Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.background,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.background
                    )
                }
            }
            IconButton(onClick = vm::next, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
            }
            // فوق كلمة يفتح شرحها كاملاً؛ وفوق ملاحظة يفتح نصّها — بلا إيقاف التشغيل
            IconButton(
                onClick = {
                    val cur = state.current ?: return@IconButton
                    if (cur.isNote) onOpenNote(cur.id) else onOpenWord(cur.id)
                },
                enabled = state.current != null,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    Icons.Filled.Article,
                    if (state.current?.isNote == true) "Open the text" else "Full explanation",
                    Modifier.size(26.dp)
                )
            }
        }
    }

    if (showQueue) QueueSheet(state, { showQueue = false }) { vm.jumpTo(it) }
    if (showSleep) SleepSheet(state.sleepTimerMinutes, { showSleep = false }) {
        vm.setSleepTimer(it); showSleep = false
    }
    if (showSpeed) SpeedSheet(state.speed, { showSpeed = false }) { vm.setSpeed(it) }
    if (showRepeat) RepeatSheet(state.wordRepeat, { showRepeat = false }) {
        vm.setWordRepeat(it); showRepeat = false
    }
}

/**
 * عدد مرات نطق شرح الكلمة داخل البطاقة الواحدة.
 * يغيّر محتوى الملف الصوتي نفسه، فالمشغّل يعيد بناء الجلسة عند تغييره —
 * ولهذا هو خيار صريح لا زر دوّار، حتى لا يُضغط بالخطأ.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSheet(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var custom by remember { mutableStateOf(current.coerceIn(1, 10).toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Repeat each word",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "How many times the word and its meanings are spoken inside one card.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpace(8)
        listOf(1 to "Once", 2 to "Twice", 3 to "Three times").forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(value) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, Modifier.weight(1f))
                if (value == current) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Custom", Modifier.weight(1f))
            androidx.compose.material3.OutlinedTextField(
                value = custom,
                onValueChange = { v -> custom = v.filter { it.isDigit() }.take(2) },
                singleLine = true,
                modifier = Modifier.width(90.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )
            HSpace(10)
            androidx.compose.material3.TextButton(
                onClick = { custom.toIntOrNull()?.coerceIn(1, 10)?.let(onPick) }
            ) { Text("Set") }
        }
        VSpace(16)
    }
}

/** حبة خيار محاطة بإطار — مطابقة لنمط الأزرار فوق شريط التقدّم */
@Composable
private fun OutlinePill(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    /** المعطّل يبهت ولا يستجيب — الشكل يقول ما يفعله الزرّ قبل أن يُضغط */
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val label = text
    val base = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onBackground
    val color = if (enabled) base else base.copy(alpha = 0.35f)
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TagBadge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/*
 * أسماء محركات الصوت (بشري/عصبي/نظام) تفاصيل داخلية للمطوّر.
 * المستخدم يريد أن يسمع، لا أن يعرف أي محرّك نطق — وعرض الاسم يحوّل
 * تفصيلاً هندسياً إلى ضجيج بصري بلا قرار يترتب عليه.
 */

/**
 * شريط التقدّم.
 * الزمن مقسوم على السرعة، فتغيير السرعة يغيّر الأرقام فعلاً — وهو ما كان
 * غائباً فبدا الزر بلا أثر رغم أنه يعمل.
 */
@Composable
private fun SeekControl(state: PlaybackUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else state.progress
    val speed = state.speed.coerceAtLeast(0.1f)

    Column(Modifier.padding(horizontal = 16.dp)) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                if (state.durationMs > 0) onSeek((dragValue * state.durationMs).toLong())
            },
            enabled = state.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onBackground,
                activeTrackColor = MaterialTheme.colorScheme.onBackground,
                inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime(((value * state.durationMs) / speed).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (state.preparing) "Loading" else formatTime((state.durationMs / speed).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(state: PlaybackUiState, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    LaunchedEffect(state.index) {
        if (state.queue.isNotEmpty()) listState.scrollToItem(state.index.coerceIn(0, state.queue.lastIndex))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            "Queue · ${state.queue.size}",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(420.dp)) {
            itemsIndexed(state.queue, key = { _, q -> q.id }) { i, item ->
                val current = i == state.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onJump(i); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (current && state.isPlaying) {
                        Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            "${i + 1}", Modifier.size(18.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    HSpace(14)
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.word,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SleepSheet(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Sleep timer",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium
        )
        listOf(0, 5, 10, 15, 30, 45, 60, 90).forEach { minutes ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (minutes == 0) "Off" else "$minutes minutes", Modifier.weight(1f))
                if (minutes == current) {
                    Icon(Icons.Filled.Bedtime, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        VSpace(16)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(current: Float, onDismiss: () -> Unit, onPick: (Float) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Playback speed",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium
        )
        listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(s) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${s}X", Modifier.weight(1f))
                if (kotlin.math.abs(s - current) < 0.001f) {
                    Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        VSpace(16)
    }
}
