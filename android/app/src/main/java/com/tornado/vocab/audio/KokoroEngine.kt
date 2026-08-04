package com.tornado.vocab.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** صوت كوكورو معروض للاختيار — الاسم والمعرّف واللكنة */
data class KokoroVoice(val sid: Int, val name: String, val accent: String)

/** حالة النموذج على الجهاز — تُعرض في الإعدادات حيّة */
sealed interface KokoroInstallState {
    data object NotInstalled : KokoroInstallState
    data class Downloading(val bytes: Long, val total: Long) : KokoroInstallState
    data object Extracting : KokoroInstallState
    data object Installed : KokoroInstallState
    data class Failed(val reason: String) : KokoroInstallState
    /** شبكة محدودة: لا ننزّل ١٤٧ ميغابايت من باقة المستخدم بلا إذنه */
    data object WaitingForWifi : KokoroInstallState
}

/**
 * محرك كوكورو — الصوت الأساسي للتطبيق.
 *
 * سبق أن حُذف من هنا محرك Piper العصبي لأنه كان أضعف من محرك الجهاز نفسه،
 * فكان حملاً بلا مقابل. وكوكورو نقيضه: أفضل صوت مفتوح للإنجليزية اليوم،
 * سمعه المستخدم بأذنه واختاره — وهذا هو المعيار الذي حُذف به الأول ورُكّب
 * به الثاني.
 *
 * النموذج خارج التطبيق عمداً: ١٤٧ ميغابايت داخل الحزمة تعني رفضاً من المتجر
 * وتنزيلاً ثقيلاً على من لا يريده. يُنزَّل مرة عند أول تشغيل ثم يعمل بلا
 * إنترنت إلى الأبد.
 */
class KokoroEngine(private val context: Context) {

    /**
     * المجلد يحمل اسم النموذج في مساره.
     *
     * كان اسماً عاماً «kokoro»، فحين تبيّن أن المثبَّت هو النموذج الصيني الخطأ
     * لم يكن للتطبيق سبيل ليعرف ذلك: يجد ملف onnx فيقول «مثبَّت» ويمضي. وضع
     * الإصدار في المسار يجعل النموذج القديم غير مرئي تلقائياً، فيُنزَّل الصحيح
     * بلا أن يُطلب من المستخدم حذف شيء بيده.
     */
    private val dir = File(context.filesDir, "kokoro-v1.0")

    /** مجلدات إصدارات سابقة تُنظَّف — منها النموذج الصيني بمئة وتسعين ميغابايت */
    private val legacyDirs = listOf(File(context.filesDir, "kokoro"))
    private val genLock = Mutex()
    private val installLock = Mutex()

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedFrom: String? = null

    private val _state = MutableStateFlow<KokoroInstallState>(
        if (isInstalled()) KokoroInstallState.Installed else KokoroInstallState.NotInstalled
    )
    val state: StateFlow<KokoroInstallState> = _state.asStateFlow()

    /** الصوت المختار — يُضبط من الإعدادات ويدخل بصمة التخزين */
    @Volatile var sid: Int = DEFAULT_SID

    /**
     * هوية النموذج — تدخل بصمة التخزين مع رقم الصوت.
     *
     * الرقم وحده لا يكفي: النموذج الصيني الخطأ والنموذج الصحيح كلاهما يحمل
     * الصوت رقم ٢٦، فبصمة «kk26» تتطابق فيهما. والنتيجة أن كل بطاقة بُنيت
     * بالصوت الخطأ تبقى مخزّنة وتُسمع كما هي حتى بعد استبدال النموذج —
     * يُصلح العطل في مكانه ويبقى أثره مسموعاً.
     */
    val modelTag: String get() = MODEL_TAG

    /**
     * جذر النموذج — يقبل أي اسم لملف onnx.
     *
     * الحزمة الفعلية تسمّيه `model.int8.onnx` لا `model.onnx`، وفحص الاسم
     * الثابت كان سيجعل التطبيق ينزّل ١٤٧ ميغابايت ثم يعلن أنها «لا تحتوي
     * نموذجاً» — عطل صامت لا يظهر إلا بعد التنزيل كاملاً.
     */
    private fun modelRoot(): File? = dir.listFiles()
        ?.firstOrNull { d -> d.isDirectory && d.listFiles()?.any { it.name.endsWith(".onnx") } == true }

    private fun onnxIn(root: File): File? =
        root.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    fun isInstalled(): Boolean {
        val root = modelRoot() ?: return false
        return onnxIn(root) != null &&
            File(root, "voices.bin").exists() &&
            File(root, "tokens.txt").exists()
    }

    val isReady: Boolean get() = isInstalled()

    /**
     * يولّد نطقاً لنصّ إنجليزي إلى ملف WAV بالتردد القياسي.
     *
     * كوكورو يُخرج ٢٤٠٠٠ هرتز ونظامنا على ٤٤١٠٠ — الرفع لا يضيف تشوّهاً
     * (بخلاف الخفض الذي سبّب الخشخشة التي شكا منها المستخدم سابقاً).
     */
    /**
     * @param voiceSid صوتٌ لهذا النداء وحده — لا يمسّ اختيار المستخدم.
     *
     * زرّا 🇺🇸 و🇬🇧 يحتاجان صوتين مختلفين في اللحظة نفسها، بينما بقية التطبيق
     * ينطق بالصوت المختار. وتغيير الحقل المشترك لأجلهما كان سيسرّب الصوت إلى
     * كل بطاقة تُبنى في تلك اللحظة — والبناء يجري بالخلفية دائماً.
     */
    suspend fun synthesize(text: String, target: File, voiceSid: Int? = null): Boolean = genLock.withLock {
        withContext(Dispatchers.Default) {
            val engine = ensureLoaded() ?: return@withContext false
            runCatching {
                val audio = engine.generate(text = text, sid = voiceSid ?: sid, speed = 1.0f)
                if (audio.samples.isEmpty()) return@runCatching false
                val shorts = ShortArray(audio.samples.size) { i ->
                    (audio.samples[i] * 32000f).toInt().coerceIn(-32768, 32767).toShort()
                }
                val canonical =
                    if (audio.sampleRate == WavUtils.SAMPLE_RATE) shorts
                    else WavUtils.resampleTo(shorts, audio.sampleRate, WavUtils.SAMPLE_RATE)
                target.parentFile?.mkdirs()
                WavUtils.writeWav(target, listOf(canonical))
                target.length() > 256
            }.getOrElse { false }
        }
    }

    private fun ensureLoaded(): OfflineTts? {
        val root = modelRoot() ?: return null
        tts?.let { if (loadedFrom == root.absolutePath) return it }
        return runCatching {
            val model = onnxIn(root) ?: return null
            val lexicons = root.listFiles()
                ?.filter { it.name.startsWith("lexicon") && it.name.endsWith("-en.txt") }
                ?.joinToString(",") { it.absolutePath }
                .orEmpty()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = model.absolutePath,
                        voices = File(root, "voices.bin").absolutePath,
                        tokens = File(root, "tokens.txt").absolutePath,
                        dataDir = File(root, "espeak-ng-data").absolutePath,
                        lexicon = lexicons,
                        lang = "en"
                    ),
                    // أربعة خيوط: هواتف اليوم ثمانية النوى، والتوليد أثقل عمل في التطبيق
                    numThreads = 4
                )
            )
            OfflineTts(config = config).also { tts = it; loadedFrom = root.absolutePath }
        }.getOrNull()
    }

    /** هل الشبكة الحالية غير محدودة (واي فاي عادةً)؟ */
    fun onUnmeteredNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * التنزيل التلقائي عند أول تشغيل — على شبكة غير محدودة وحدها.
     *
     * المستخدم طلب التنزيل التلقائي، والتنفيذ الحرفي كان سيبتلع ١٤٧ ميغابايت
     * من باقة الجوال في أول فتحة خارج المنزل. فالتلقائي يعمل على الواي فاي،
     * وعلى بيانات الجوال يبقى زرّاً صريحاً في الإعدادات — الطلب مُنفَّذ حيث
     * لا يؤذي.
     */
    suspend fun installIfAppropriate(): Boolean {
        // تنظيف الإصدارات القديمة أولاً — لا نترك نموذجاً مهجوراً يأكل الذاكرة
        purgeLegacy()
        if (isInstalled()) return true
        if (!onUnmeteredNetwork()) {
            _state.value = KokoroInstallState.WaitingForWifi
            return false
        }
        return install()
    }

    suspend fun install(): Boolean = installLock.withLock {
        withContext(Dispatchers.IO) {
            purgeLegacy()
            if (isInstalled()) {
                _state.value = KokoroInstallState.Installed
                return@withContext true
            }
            val archive = File(context.cacheDir, "kokoro.tar.bz2")
            try {
                _state.value = KokoroInstallState.Downloading(0, 0)
                val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    archive.outputStream().buffered().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastPush = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastPush > 1_048_576) {
                                _state.value = KokoroInstallState.Downloading(done, total)
                                lastPush = done
                            }
                        }
                    }
                }

                _state.value = KokoroInstallState.Extracting
                // مجلد مؤقت ثم تسمية: انقطاع في المنتصف لا يترك نموذجاً نصفه صالح
                val staging = File(context.filesDir, "kokoro-staging")
                staging.deleteRecursively()
                staging.mkdirs()
                TarArchiveInputStream(
                    BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
                ).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val out = File(staging, entry.name)
                        // حماية من مسارات تخرج من المجلد (Zip Slip)
                        if (!out.canonicalPath.startsWith(staging.canonicalPath)) continue
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().buffered().use { tar.copyTo(it) }
                        }
                    }
                }
                runCatching { archive.delete() }
                pruneUnused(staging)

                dir.deleteRecursively()
                val moved = staging.renameTo(dir)
                if (!moved) staging.copyRecursively(dir, overwrite = true)
                runCatching { staging.deleteRecursively() }

                val ok = isInstalled()
                _state.value =
                    if (ok) KokoroInstallState.Installed
                    else KokoroInstallState.Failed("Package did not contain a usable model")
                ok
            } catch (e: Exception) {
                runCatching { archive.delete() }
                _state.value = KokoroInstallState.Failed(e.message?.take(80) ?: "Download failed")
                false
            }
        }
    }

    /**
     * يحذف ما لا نستعمله من الحزمة — ١٦ ميغابايت صينية.
     *
     * الحزمة متعدّدة اللغات وتحمل قاموس تقطيع صيني ومعجمه وملفات أرقامه.
     * تطبيقنا إنجليزي بحت، وتركها يعني ستة عشر ميغابايت من ذاكرة المستخدم
     * ثمناً للا شيء.
     */
    private fun pruneUnused(root: File) {
        val model = root.listFiles()?.firstOrNull { it.isDirectory } ?: return
        runCatching {
            File(model, "dict").deleteRecursively()
            model.listFiles()
                ?.filter { it.name.contains("-zh") || it.name.endsWith("zh.fst") }
                ?.forEach { it.delete() }
        }
    }

    /** يحذف نماذج إصدارات سابقة إن وُجدت — يُستدعى قبل أي فحص للتثبيت */
    fun purgeLegacy() {
        legacyDirs.filter { it.exists() }.forEach { old ->
            runCatching { old.deleteRecursively() }
        }
    }

    fun remove() {
        release()
        runCatching { dir.deleteRecursively() }
        _state.value = KokoroInstallState.NotInstalled
    }

    fun release() {
        runCatching { tts?.release() }
        tts = null
        loadedFrom = null
    }

    /** الحجم الفعلي على القرص — يُعرض للمستخدم بجانب زر الحذف */
    fun installedBytes(): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)

    companion object {
        /*
         * كوكورو v1.0 — لا v1.1-zh.
         *
         * شُحنت v1.1-zh أولاً لأنها ظهرت في قائمة النماذج بحجم مقارب، وملفها
         * الداخلي يقول صراحةً «this directory is for kokoro v1.1-zh»: نسخة
         * مضبوطة للصينية، إنجليزيتها رديئة، وترتيب أصواتها المئة والاثنين
         * مختلف تماماً. فسمع المستخدم أصواتاً صينية تحت أسماء بريطانية.
         *
         * وأرقام الأصوات أدناه لم تكن خاطئة — كانت لهذا النموذج، وطُبِّقت على
         * ذاك. الدرس أن رقم الصوت بلا اسم النموذج الذي جاء منه لا معنى له.
         */
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                "kokoro-multi-lang-v1_0.tar.bz2"

        /**
         * النسخة الكاملة لا المضغوطة (int8).
         *
         * المضغوطة أصغر بمئتي ميغابايت، لكن الضغط يأكل من نقاء النبرة —
         * والمستخدم وضع الجودة فوق كل شيء صراحةً، ثم سمع الرديء وردّه.
         * فالحجم يُدفع مرة، والصوت يُسمع كل يوم.
         */
        const val DOWNLOAD_MB = 333
        const val ON_DISK_MB = 367

        /** يميّز النموذج في بصمة التخزين — يتغيّر مع كل استبدال نموذج */
        const val MODEL_TAG = "v10fp"

        /** bm_george — بريطاني */
        const val DEFAULT_SID = 26

        /**
         * صوتا زرّي اللهجة — ثابتان لا يتبعان اختيار المستخدم.
         *
         * الزرّان وُجدا ليُسمَع الفرق بين اللهجتين. فلو تبعا الاختيار العام
         * لصارا صوتاً واحداً كلما اختار المستخدم صوتاً أمريكياً أو بريطانياً
         * لمكتبته — أي في كل الأحوال تقريباً.
         */
        const val SID_UK = 26   // George · bm_george
        const val SID_US = 9    // Sarah · af_sarah

        /**
         * أصوات إنجليزية منتقاة، وأرقامها مأخوذة حرفياً من سكربت بناء
         * `voices.bin` في مشروع sherpa-onnx لنسخة v1.0 — لا من الذاكرة.
         *
         * ومعرّف كل صوت معروض للمستخدم بجانب اسمه: إن اختلف ما يسمعه عمّا
         * يقرؤه، فالمعرّف يكشف الخلل فوراً بدل أن يبقى مخبوءاً خلف اسم
         * لطيف اخترته أنا.
         */
        val VOICES = listOf(
            KokoroVoice(26, "George", "British · bm_george"),
            KokoroVoice(25, "Fable", "British · bm_fable"),
            KokoroVoice(27, "Lewis", "British · bm_lewis"),
            KokoroVoice(24, "Daniel", "British · bm_daniel"),
            KokoroVoice(21, "Emma", "British · bf_emma"),
            KokoroVoice(22, "Isabella", "British · bf_isabella"),
            KokoroVoice(11, "Adam", "American · am_adam"),
            KokoroVoice(16, "Michael", "American · am_michael"),
            KokoroVoice(3, "Heart", "American · af_heart"),
            KokoroVoice(9, "Sarah", "American · af_sarah")
        )

        fun voiceName(sid: Int): String =
            VOICES.firstOrNull { it.sid == sid }?.let { "${it.name} · ${it.accent}" } ?: "Voice $sid"
    }
}
