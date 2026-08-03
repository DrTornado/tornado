package com.tornado.vocab

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tornado.vocab.data.AppSettings
import com.tornado.vocab.ui.AddWordScreen
import com.tornado.vocab.ui.AttributionScreen
import com.tornado.vocab.ui.AddWordViewModel
import com.tornado.vocab.ui.LibraryScreen
import com.tornado.vocab.ui.LibraryViewModel
import com.tornado.vocab.ui.NoteDetailScreen
import com.tornado.vocab.ui.NotesScreen
import com.tornado.vocab.ui.NotesViewModel
import com.tornado.vocab.ui.SyncButton
import com.tornado.vocab.ui.ListenScreen
import com.tornado.vocab.ui.ListenViewModel
import com.tornado.vocab.ui.MiniPlayer
import com.tornado.vocab.ui.PlayerScreen
import com.tornado.vocab.ui.ProgressScreen
import com.tornado.vocab.ui.ProgressViewModel
import com.tornado.vocab.ui.QuizScreen
import com.tornado.vocab.ui.QuizViewModel
import com.tornado.vocab.ui.SettingsScreen
import com.tornado.vocab.ui.SettingsViewModel
import com.tornado.vocab.ui.TornadoTheme
import com.tornado.vocab.ui.WordDetailScreen
import com.tornado.vocab.ui.WordDetailViewModel

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM_DESTINATIONS = listOf(
    Destination("library", "Words", Icons.AutoMirrored.Filled.MenuBook),
    Destination("quiz", "Quiz", Icons.Filled.Quiz),
    Destination("listen", "Listen", Icons.Filled.Headphones),
    // الملاحظات تبويب مستقل لا وضع داخل الاستماع: محتوى مختلف بإدارة مختلفة
    Destination("notes", "Notes", Icons.AutoMirrored.Filled.Article),
    Destination("add", "Add", Icons.Filled.Add)
)

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent { TornadoRoot() }

        /*
         * طلب إذن الإشعارات بعد أول رسم لا قبله.
         *
         * طلبه في بداية onCreate يضع نافذة نظام فوق التطبيق قبل أن يُرسم شيء،
         * فيرى المستخدم حواراً عن الإشعارات قبل أن يرى التطبيق أصلاً — ويُحسب
         * زمن انتظاره من زمن الإقلاع. وقياسنا سجّل ١١٫٧ ثانية بسببه بينما
         * التطبيق نفسه يظهر في نصف ذلك.
         *
         * ولا نطلبه إلا إن لم يكن ممنوحاً: الطلب المتكرر رحلة ذهاب وإياب مع
         * النظام في كل فتحة بلا فائدة.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                window.decorView.post {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun TornadoRoot() {
    val app = androidx.compose.ui.platform.LocalContext.current.tornado
    /**
     * نجمع التدفّق مباشرة.
     * استخدام stateIn هنا كان يبني تدفّقاً جديداً ويطلق كوروتين مع كل إعادة
     * تركيب، فيتراكم العمل على الخيط الرئيسي حتى تخطّى الإقلاع ٧٠٠ إطار.
     */
    val settings by app.settings.app.collectAsStateWithLifecycle(initialValue = AppSettings())

    TornadoTheme(mode = settings.theme, dynamicColor = settings.dynamicColor) {
        val nav = rememberNavController()
        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val isBottomRoute = BOTTOM_DESTINATIONS.any { it.route == route }

        val libraryVm: LibraryViewModel = viewModel()
        val quizVm: QuizViewModel = viewModel()
        val listenVm: ListenViewModel = viewModel()
        val addVm: AddWordViewModel = viewModel()

        Scaffold(
            topBar = {
                if (isBottomRoute) {
                    TopAppBar(
                        title = {
                            Text(
                                "TORNADO",
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        actions = {
                            // زر المزامنة في أعلى الشاشة كما في نسخة الويب —
                            // المكان الذي تعوّدت يدُ المستخدم عليه
                            SyncButton()
                            IconButton(onClick = { nav.navigate("progress") }) {
                                Icon(Icons.Filled.Insights, "Progress")
                            }
                            IconButton(onClick = { nav.navigate("settings") }) {
                                Icon(Icons.Filled.Settings, "Settings")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = isBottomRoute,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    Column {
                        // المشغّل المصغّر فوق شريط التنقّل — حاضر في كل شاشة
                        val playback by com.tornado.vocab.audio.PlaybackBus.state
                            .collectAsStateWithLifecycle()
                        MiniPlayer(
                            state = playback,
                            onExpand = { nav.navigate("player") },
                            onPlayPause = { listenVm.togglePlay() },
                            onNext = { listenVm.next() },
                            onPrevious = { listenVm.previous() }
                        )
                        NavigationBar {
                            BOTTOM_DESTINATIONS.forEach { dest ->
                                NavigationBarItem(
                                    selected = route == dest.route,
                                    onClick = {
                                        if (route != dest.route) {
                                            nav.navigate(dest.route) {
                                                popUpTo("listen") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(dest.icon, dest.label) },
                                    label = { Text(dest.label) }
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(nav, startDestination = "listen") {

                    composable("library") {
                        LibraryScreen(
                            vm = libraryVm,
                            onOpenWord = { nav.navigate("word/$it") },
                            onOpenPlayer = { nav.navigate("player") }
                        )
                    }

                    composable("quiz") {
                        /*
                         * الجولة تُبنى مرة وتبقى، والمكتبة تتغيّر تحتها.
                         *
                         * كلمة تُضاف لا تدخل الجولة الجارية، وكلمة تُحذف تظل
                         * معروضة وقد تُحذف من تحت المستخدم أثناء إجابته. فحص
                         * عند العودة للتبويب يكفي: يعيد البناء إن تغيّر العدد
                         * فعلاً، ولا يقطع جولة بدأ الإجابة فيها.
                         */
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            quizVm.refreshIfLibraryChanged()
                        }
                        QuizScreen(vm = quizVm, onOpenWord = { nav.navigate("word/$it") })
                    }

                    composable("notes") {
                        NotesScreen(
                            vm = viewModel(),
                            onOpenNote = { nav.navigate("note/$it") },
                            onOpenPlayer = { nav.navigate("player") }
                        )
                    }

                    composable("listen") {
                        ListenScreen(vm = listenVm, onOpenPlayer = { nav.navigate("player") })
                    }

                    composable("add") {
                        AddWordScreen(vm = addVm, onOpenWord = { nav.navigate("word/$it") })
                    }

                    composable("player") {
                        PlayerScreen(
                            vm = listenVm,
                            onCollapse = { nav.popBackStack() },
                            onOpenWord = { nav.navigate("word/$it") },
                            /*
                             * زرّا أسفل المشغّل يتبعان نوع المحتوى.
                             *
                             * فوق كلمة: القائمة اليسرى طابور الكلمات، واليمنى
                             * شرح الكلمة. وفوق مقطع ملاحظة: اليسرى تعود لقائمة
                             * الملاحظات، واليمنى تفتح نصّها كاملاً — زرّان
                             * بمعنى واحد («أين أنا؟» و«ما الذي أسمعه؟») مهما
                             * تبدّل المحتوى.
                             */
                            onOpenNote = { nav.navigate("note/$it") },
                            onOpenNotesList = {
                                nav.navigate("notes") { popUpTo("listen"); launchSingleTop = true }
                            }
                        )
                    }

                    composable(
                        "word/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType })
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        val detailVm: WordDetailViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(id) { detailVm.load(id) }
                        WordDetailScreen(vm = detailVm, onBack = { nav.popBackStack() })
                    }

                    composable(
                        "note/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType })
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        val notesVm: NotesViewModel = viewModel()
                        NoteDetailScreen(
                            noteId = id,
                            onBack = { nav.popBackStack() },
                            onPlay = { notesVm.play(it) }
                        )
                    }

                    composable("attribution") {
                        AttributionScreen(onBack = { nav.popBackStack() })
                    }

                    composable("progress") {
                        val progressVm: ProgressViewModel = viewModel()
                        ProgressScreen(
                            vm = progressVm,
                            onBack = { nav.popBackStack() },
                            onStartQuiz = {
                                quizVm.start()
                                nav.navigate("quiz") { popUpTo("library") }
                            }
                        )
                    }

                    composable("settings") {
                        val settingsVm: SettingsViewModel = viewModel()
                        SettingsScreen(
                            vm = settingsVm,
                            onBack = { nav.popBackStack() },
                            onOpenAttribution = { nav.navigate("attribution") }
                        )
                    }
                }
            }
        }
    }
}
