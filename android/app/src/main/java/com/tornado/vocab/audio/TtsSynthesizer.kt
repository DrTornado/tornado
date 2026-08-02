package com.tornado.vocab.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

data class TtsVoiceInfo(val name: String, val locale: String, val quality: Int, val isNetwork: Boolean)

data class TtsStatus(
    val ready: Boolean = false,
    val englishAvailable: Boolean = false,
    val arabicAvailable: Boolean = false,
    val engineName: String = "",
    val error: String? = null
)

/**
 * غلاف حول محرّك النطق المدمج في أندرويد.
 *
 * قرار معماري جوهري: لا نستخدم speak() المباشر إطلاقاً. بدلاً منه نولّد كل مقطع
 * إلى ملف WAV عبر synthesizeToFile، ثم ندمج المقاطع في ملف واحد يشغّله ExoPlayer.
 *
 * السبب أن speak() لا يمرّ عبر جلسة وسائط: لا شريط تقدّم، لا تحكّم من شاشة القفل،
 * لا أزرار سماعة بلوتوث، ويتوقف بلا ضمان عند إطفاء الشاشة. أما ملف صوتي حقيقي
 * داخل ExoPlayer فيسلك سلوك أي مشغّل موسيقى: يعمل بالخلفية بلا حد، ويظهر في
 * شاشة القفل، ويستجيب للسماعات، ويمكن التنقّل داخله بالثانية.
 */
class TtsSynthesizer(private val context: Context) {

    private val mutex = Mutex()
    private val counter = AtomicInteger(0)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    @Volatile private var status = TtsStatus()

    @Volatile var preferredEngine: String? = null
    @Volatile var englishVoiceName: String? = null
    @Volatile var arabicVoiceName: String? = null

    /*
     * محرّك مستقل للعربية.
     *
     * كائن TextToSpeech يرتبط بمحرّك واحد عند إنشائه ولا يمكن تبديله بعدها،
     * فمحرّك واحد يعني إجبار اللغتين على مزوّد واحد. وهذا كان يضيّع أفضل صوت
     * عربي متاح: أجهزة سامسونج تأتي بمحرّكها الخاص مع حزمة صوت عربي جاهزة،
     * وهو أوضح وأقرب للبشر من عربية المحرّك الافتراضي — لكنه لا يُستعمل أبداً
     * لأن الإنجليزية تختار المحرّك للاثنتين.
     *
     * كائنان منفصلان يجعلان كل لغة تأخذ أفضل ما في الجهاز لها.
     */
    @Volatile var arabicEngine: String? = null
    @Volatile private var arabicTts: TextToSpeech? = null
    @Volatile private var arabicReady = false
    @Volatile private var arabicBoundTo: String? = null

    /** درجة الصوت وسرعته على مستوى المحرّك — السرعة النهائية يضبطها المشغّل */
    @Volatile var pitch: Float = 1.0f

    suspend fun status(): TtsStatus { ensureReady(); return status }

    private suspend fun ensureReady(): TextToSpeech? {
        tts?.let { if (initialized) return it }
        return mutex.withLock {
            tts?.let { if (initialized) return it }
            shutdownLocked()
            createEngine()
        }
    }

    private suspend fun createEngine(): TextToSpeech? = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont: CancellableContinuation<TextToSpeech?> ->
                var engine: TextToSpeech? = null
                val listener = TextToSpeech.OnInitListener { code ->
                    if (cont.isActive) {
                        cont.resume(if (code == TextToSpeech.SUCCESS) engine else null)
                    }
                }
                /*
                 * نختار المحرّك بأنفسنا حين لا يختاره النظام.
                 *
                 * تمرير null يعني «استخدم المحرّك الافتراضي»، وعلى جهاز لم
                 * يُضبط فيه افتراضي قط لا يوجد ما يُستخدم — فيفشل التهيئة
                 * ويصمت التطبيق كله بلا خطأ ظاهر. وهذا يحدث فعلاً: أجهزة
                 * كثيرة تُشحن بمحرّك مثبَّت وبلا تعيينه افتراضياً.
                 *
                 * فنسأل النظام عن المحرّكات المثبّتة ونربط بأولها بدل أن
                 * ننتظر إعداداً قد لا يوجد.
                 */
                val chosen = preferredEngine?.takeIf { it.isNotBlank() } ?: installedEngine()
                engine = if (chosen == null) {
                    TextToSpeech(context.applicationContext, listener)
                } else {
                    TextToSpeech(context.applicationContext, listener, chosen)
                }
            }
        }
        if (result == null) {
            status = TtsStatus(error = "Text-to-speech engine did not start")
            initialized = false
            tts = null
            return@withContext null
        }
        tts = result
        initialized = true
        result.setPitch(pitch)
        status = TtsStatus(
            ready = true,
            englishAvailable = result.supports(Locale.US),
            arabicAvailable = result.supports(Locale("ar")),
            engineName = runCatching { result.defaultEngine ?: "" }.getOrDefault("")
        )
        result
    }

    /**
     * يُنشئ محرّك العربية عند الحاجة فقط.
     * فشله ليس خطأً: نعود للمحرّك الأساسي، فوجود صوت أفضل ميزة لا شرط.
     */
    private suspend fun ensureArabic(): TextToSpeech? {
        val wanted = arabicEngine?.takeIf { it.isNotBlank() } ?: return null
        arabicTts?.let { if (arabicReady && arabicBoundTo == wanted) return it }
        return withContext(Dispatchers.Main) {
            runCatching { arabicTts?.shutdown() }
            arabicTts = null
            arabicReady = false
            val result = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { cont: CancellableContinuation<TextToSpeech?> ->
                    var engine: TextToSpeech? = null
                    val listener = TextToSpeech.OnInitListener { code ->
                        if (cont.isActive) {
                            cont.resume(if (code == TextToSpeech.SUCCESS) engine else null)
                        }
                    }
                    engine = TextToSpeech(context.applicationContext, listener, wanted)
                }
            }
            if (result == null) return@withContext null
            result.setPitch(pitch)
            arabicTts = result
            arabicReady = true
            arabicBoundTo = wanted
            result
        }
    }

    /**
     * محرّك نطق مثبَّت على الجهاز، مقروءاً من مدير الحزم مباشرة.
     * لا يحتاج كائن TextToSpeech قائماً، فيصلح لكسر دورة «لا محرّك بلا محرّك».
     */
    private fun installedEngine(): String? = runCatching {
        val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            // محرّك الجهاز الأصلي يسبق غيره: بياناته الصوتية مثبّتة معه عادةً
            .minByOrNull { if (it.startsWith("com.google")) 1 else 0 }
    }.getOrNull()

    private fun TextToSpeech.supports(locale: Locale): Boolean = runCatching {
        val r = isLanguageAvailable(locale)
        r == TextToSpeech.LANG_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }.getOrDefault(false)

    suspend fun availableVoices(): List<TtsVoiceInfo> {
        val engine = ensureReady() ?: return emptyList()
        return runCatching {
            engine.voices.orEmpty()
                .filter {
                    val lang = it.locale.language
                    lang == "en" || lang == "ar"
                }
                .map { TtsVoiceInfo(it.name, it.locale.toLanguageTag(), it.quality, it.isNetworkConnectionRequired) }
                .sortedWith(compareBy({ it.locale }, { -it.quality }))
        }.getOrDefault(emptyList())
    }

    /**
     * أفضل محرّك عربي متاح على هذا الجهاز، أو null إن لم يتميّز أحد.
     *
     * أجهزة سامسونج تشحن محرّكها الخاص مع حزم أصوات عربية مثبّتة مسبقاً، وهي
     * أوضح من عربية المحرّك الافتراضي. لكنها تبقى معطّلة عملياً لأن المستخدم
     * يختار محرّكاً واحداً للنظام كله — فتربح الإنجليزية وتخسر العربية.
     *
     * نختاره تلقائياً بدل انتظار المستخدم: من يعاني من رداءة النطق العربي لا
     * يخطر له أن الحل إعداد مدفون اسمه «محرّك تحويل النص إلى كلام».
     */
    suspend fun bestArabicEngine(): String? {
        val installed = availableEngines().map { it.first }
        return installed.firstOrNull { it == "com.samsung.SMT" }
    }

    suspend fun availableEngines(): List<Pair<String, String>> {
        val engine = ensureReady() ?: return emptyList()
        return runCatching { engine.engines.orEmpty().map { it.name to it.label } }.getOrDefault(emptyList())
    }

    /**
     * يولّد مقطعاً واحداً إلى ملف. متسلسل بقفل لأن محرّك النطق كائن واحد
     * لا يقبل طلبين متزامنين — التوازي هنا يُنتج ملفات مقطوعة.
     */
    suspend fun synthesize(text: String, lang: SegLang, target: File): Boolean = mutex.withLock {
        // العربية تأخذ محرّكها الخاص إن وُجد، وإلا فالمحرّك الأساسي كما كان
        val engine = (if (lang == SegLang.AR) ensureArabic() else null)
            ?: (tts?.takeIf { initialized } ?: createEngine())
            ?: return@withLock false
        val locale = if (lang == SegLang.AR) Locale("ar") else Locale.US

        val langResult = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            return@withLock false
        }
        val wanted = if (lang == SegLang.AR) arabicVoiceName else englishVoiceName
        if (!wanted.isNullOrBlank()) {
            runCatching {
                engine.voices?.firstOrNull { it.name == wanted }?.let { v: Voice -> engine.voice = v }
            }
        }

        target.parentFile?.mkdirs()
        target.delete()
        val id = "seg-" + counter.incrementAndGet()

        val ok = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }
                })
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                }
                val queued = runCatching { engine.synthesizeToFile(text, params, target, id) }
                    .getOrDefault(TextToSpeech.ERROR)
                if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } ?: false

        // ملف صفري الحجم يعني فشلاً صامتاً — نعامله كفشل صريح بدل تمرير صمت للمُشغّل
        ok && target.exists() && target.length() > 64
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        // محرّك العربية كائن نظام مستقل، وتركه معلّقاً يسرّب اتصالاً بالخدمة
        runCatching { arabicTts?.stop() }
        runCatching { arabicTts?.shutdown() }
        arabicTts = null
        arabicReady = false
        arabicBoundTo = null
    }

    private fun shutdownLocked() {
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
    }
}
