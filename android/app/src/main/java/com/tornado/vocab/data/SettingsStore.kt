package com.tornado.vocab.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("tornado-settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** تكرار القائمة — اللانهاية تعني تشغيلاً مستمراً بلا توقف */
/**
 * أوضاع التكرار — على نمط كل مشغّل موسيقى يعرفه المستخدم.
 *
 * الصيغة القديمة («القائمة مرة/مرتين/ثلاثاً») كانت تخصّ القائمة وحدها، ولا
 * سبيل فيها لتكرار الكلمة الجارية — وهو أكثر ما يحتاجه متعلّم يريد سماع شرح
 * كلمة صعبة مراراً. والمستخدم قرأ الزر على النمط المألوف فوجده لا يفعل ما
 * توقّعه، وكان توقّعه هو الصواب.
 */
object ListRepeat {
    const val OFF = 1
    const val ALL = -1
    const val ONE = -2
    val OPTIONS = listOf(OFF, ALL, ONE)
    fun label(v: Int) = when (v) {
        ALL -> "Repeat all"
        ONE -> "Repeat one"
        else -> "No repeat"
    }
    /** القيم القديمة المخزّنة (٢، ٣) تُقرأ كأقرب معنى — تكرار الكل */
    fun normalize(v: Int) = if (v == ALL || v == ONE) v else if (v > 1) ALL else OFF
}

data class AudioSettings(
    val speed: Float = 0.9f,
    val detailed: Boolean = true,
    val shuffle: Boolean = false,
    val wordRepeat: Int = 1,
    val listRepeat: Int = 1,
    /**
     * العربية تُقرأ صوتياً.
     *
     * مطفأة افتراضياً بقرار من المستخدم بعد سماع النتيجة على جهاز حقيقي:
     * محركات النطق المحلية تُخرج عربية سيئة إلى حدّ يضرّ بالجلسة بدل أن يفيدها.
     * والمعنى العربي يبقى معروضاً على الشاشة كاملاً — الحذف من الصوت وحده.
     */
    val speakArabic: Boolean = false,
    val autoAdvance: Boolean = true,
    val prefetchEnabled: Boolean = true,
    val englishVoice: String = "",
    val arabicVoice: String = "",
    val ttsEngine: String = "",
    /** محرّك مستقل للعربية — فارغ يعني استخدام المحرّك الأساسي لها أيضاً */
    val ttsArabicEngine: String = "",
    /** صوت كوكورو المختار — المعرّف الرقمي داخل النموذج */
    val kokoroSid: Int = 26,
    /**
     * كوكورو هو المحرك الأساسي — وهذا المفتاح يسمح بالعودة لمحرك الجهاز.
     *
     * ليس تفضيلاً تجميلياً: من ضاقت ذاكرته أو رضي بصوت جهازه لا يجب أن
     * يُجبَر على مئة وتسعين ميغابايت.
     */
    val useKokoro: Boolean = true,
    /**
     * حدّ التخزين الصوتي.
     *
     * خُفض من ٥١٢ إلى ٢٥٦: النظام نبّه المستخدم إلى «مسح الكاش للتحسين» بعد
     * استعمال بسيط، وتنبيهٌ كهذا يجعل التطبيق يبدو متطفّلاً على الجهاز. ومع
     * تخزين الجُمل منفردة صار البناء أرخص، فالحدّ الأدنى يكفي.
     */
    val cacheLimitMb: Int = 256,
    /**
     * الوضع الافتراضي: لا يُنطق إلا ما سجّله إنسان حقيقي.
     * الكلمة بلا تسجيل تُتخطّى ولا تُولَّد — هذا جوهر المنتج لا خيار فيه.
     */
    val humanOnly: Boolean = true,
    /** يُسمح بمحرك النظام فقط حين يُطفأ الوضع البشري صراحةً */
    val allowSystemVoice: Boolean = false,

    // ===== الصوت السحابي =====
    val cloudEnabled: Boolean = true,
    val cloudProvider: String = "azure",
    /** منطقة أزور — جزء من العنوان لا من المفتاح، فلا بد من حفظها معه */
    val cloudRegion: String = "",
    val cloudVoice: String = "",
    /** صوت العربية — منفصل لأن جوجل يربط كل صوت بلغة واحدة */
    val cloudArabicVoice: String = "",
    val cloudModel: String = "tts-1",
    /** UNIFIED = صوت واحد موحّد · HUMAN_FIRST = تسجيل بشري أولاً */
    val voiceStrategy: String = "UNIFIED",
    /** إجمالي الأحرف المولَّدة — أساس عدّاد التكلفة */
    val cloudCharacters: Long = 0,
    val sleepTimerDefault: Int = 30,
)

data class AppSettings(
    /** هوية تورنادو داكنة أصلاً، والوضع الفاتح يكسر مزاج جلسة الاستماع */
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val dailyGoal: Int = 20,
    val quizDueOnly: Boolean = true,
    val quizLimit: Int = 40,
    val showArabicFirst: Boolean = false,
    val seeded: Boolean = false,
    val streakDays: Int = 0,
    val lastStudyDay: Long = 0,
    val reviewedToday: Int = 0
)

/**
 * كل التفضيلات في مكان واحد. DataStore غير محجوب على الخيط الرئيسي —
 * وهو فرق ملموس في زمن الإقلاع مقارنة بـ SharedPreferences.
 */
class SettingsStore(private val context: Context) {

    private object K {
        val SPEED = floatPreferencesKey("audio_speed")
        val DETAILED = booleanPreferencesKey("audio_detailed")
        val SHUFFLE = booleanPreferencesKey("audio_shuffle")
        val WORD_REPEAT = intPreferencesKey("audio_word_repeat")
        val LIST_REPEAT = intPreferencesKey("audio_list_repeat")
        val SPEAK_ARABIC = booleanPreferencesKey("audio_speak_arabic")
        val AUTO_ADVANCE = booleanPreferencesKey("audio_auto_advance")
        val PREFETCH = booleanPreferencesKey("audio_prefetch")
        val EN_VOICE = stringPreferencesKey("tts_en_voice")
        val AR_VOICE = stringPreferencesKey("tts_ar_voice")
        val ENGINE = stringPreferencesKey("tts_engine")
        val CACHE_MB = intPreferencesKey("audio_cache_mb")
        val HUMAN_ONLY = booleanPreferencesKey("audio_human_only")
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_VOICE = stringPreferencesKey("cloud_voice")
        val CLOUD_VOICE_AR = stringPreferencesKey("cloud_voice_ar")
        val CLOUD_REGION = stringPreferencesKey("cloud_region")
        val ENGINE_AR = stringPreferencesKey("tts_engine_ar")
        val SYNC_REPO = stringPreferencesKey("sync_repo")
        val KOKORO_SID = intPreferencesKey("kokoro_sid")
        val USE_KOKORO = booleanPreferencesKey("use_kokoro")
        val CLOUD_PROVIDER = stringPreferencesKey("cloud_provider")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val VOICE_STRATEGY = stringPreferencesKey("voice_strategy")
        val CLOUD_CHARS = longPreferencesKey("cloud_characters")
        val ALLOW_SYSTEM = booleanPreferencesKey("audio_allow_system")
        val SLEEP_DEFAULT = intPreferencesKey("audio_sleep_default")

        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val GOAL = intPreferencesKey("daily_goal")
        val QUIZ_DUE_ONLY = booleanPreferencesKey("quiz_due_only")
        val QUIZ_LIMIT = intPreferencesKey("quiz_limit")
        val AR_FIRST = booleanPreferencesKey("quiz_arabic_first")
        val SEEDED = booleanPreferencesKey("seeded")
        val STREAK = intPreferencesKey("streak_days")
        val LAST_DAY = longPreferencesKey("last_study_day")
        val TODAY_COUNT = intPreferencesKey("reviewed_today")
    }

    val audio: Flow<AudioSettings> = context.dataStore.data.map { it.toAudio() }
    val app: Flow<AppSettings> = context.dataStore.data.map { it.toApp() }

    private fun Preferences.toAudio() = AudioSettings(
        // ١٫٠ يعني تشغيلاً بلا تمديد زمني إطلاقاً؛ أي قيمة أخرى تمرّ بخوارزمية
        // شدّ الزمن في المشغّل وتضيف تلوّناً معدنياً يُسمع كخشخشة
        speed = this[K.SPEED] ?: 1.0f,
        detailed = this[K.DETAILED] ?: true,
        shuffle = this[K.SHUFFLE] ?: false,
        wordRepeat = this[K.WORD_REPEAT] ?: 1,
        listRepeat = this[K.LIST_REPEAT] ?: 1,
        speakArabic = this[K.SPEAK_ARABIC] ?: false,
        autoAdvance = this[K.AUTO_ADVANCE] ?: true,
        prefetchEnabled = this[K.PREFETCH] ?: true,
        englishVoice = this[K.EN_VOICE] ?: "",
        arabicVoice = this[K.AR_VOICE] ?: "",
        ttsEngine = this[K.ENGINE] ?: "",
        ttsArabicEngine = this[K.ENGINE_AR] ?: "",
        kokoroSid = this[K.KOKORO_SID] ?: 26,
        useKokoro = this[K.USE_KOKORO] ?: true,
        cacheLimitMb = this[K.CACHE_MB] ?: 256,
        humanOnly = this[K.HUMAN_ONLY] ?: true,
        allowSystemVoice = this[K.ALLOW_SYSTEM] ?: false,
        cloudEnabled = this[K.CLOUD_ENABLED] ?: true,
        /*
         * المزوّد القديم حُذف من المنتج، فأي إعداد محفوظ يشير إليه لم يعد صالحاً.
         *
         * ولا نكتفي بترجمة اسم المزوّد: الصوت المحفوظ معه معرّف مبهم لا يعني
         * شيئاً عند جوجل، وإرساله يعني فشلاً صامتاً وسقوطاً إلى صوت آلي. وفحص
         * اسم المزوّد وحده لا يكفي لأنه قد لا يكون مكتوباً أصلاً حين كان القديم
         * هو الافتراضي — فنتحقق من شكل المعرّف نفسه: أصوات جوجل تبدأ دائماً
         * برمز اللغة مثل en-US أو ar-XA.
         */
        cloudProvider = (this[K.CLOUD_PROVIDER] ?: "azure")
            .let { if (it == "elevenlabs") "azure" else it },
        cloudRegion = this[K.CLOUD_REGION] ?: "",
        cloudVoice = (this[K.CLOUD_VOICE] ?: "").takeIf { it.looksLikeLocaleVoice() }.orEmpty(),
        cloudArabicVoice =
            (this[K.CLOUD_VOICE_AR] ?: "").takeIf { it.looksLikeLocaleVoice() }.orEmpty(),
        cloudModel = this[K.CLOUD_MODEL] ?: "tts-1",
        voiceStrategy = this[K.VOICE_STRATEGY] ?: "UNIFIED",
        cloudCharacters = this[K.CLOUD_CHARS] ?: 0L,
        sleepTimerDefault = this[K.SLEEP_DEFAULT] ?: 30,
    )

    /**
     * أزور وجوجل يسمّيان أصواتهما بالشكل نفسه: رمز لغة ثم بلد ثم بقية الاسم،
     * مثل ar-SA-HamedNeural أو ar-XA-Chirp3-HD-Aoede. أي معرّف لا يطابق هذا
     * الشكل بقيّة من مزوّد محذوف، وإرساله يعني فشلاً صامتاً وصوتاً آلياً.
     */
    private fun String.looksLikeLocaleVoice(): Boolean =
        Regex("^[a-z]{2}-[A-Z]{2}-.+").matches(this)

    private fun Preferences.toApp() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(this[K.THEME] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
        dynamicColor = this[K.DYNAMIC] ?: false,
        dailyGoal = this[K.GOAL] ?: 20,
        quizDueOnly = this[K.QUIZ_DUE_ONLY] ?: true,
        quizLimit = this[K.QUIZ_LIMIT] ?: 40,
        showArabicFirst = this[K.AR_FIRST] ?: false,
        seeded = this[K.SEEDED] ?: false,
        streakDays = this[K.STREAK] ?: 0,
        lastStudyDay = this[K.LAST_DAY] ?: 0L,
        reviewedToday = this[K.TODAY_COUNT] ?: 0
    )

    suspend fun setSpeed(v: Float) = edit { it[K.SPEED] = v }
    suspend fun setDetailed(v: Boolean) = edit { it[K.DETAILED] = v }
    suspend fun setShuffle(v: Boolean) = edit { it[K.SHUFFLE] = v }
    suspend fun setWordRepeat(v: Int) = edit { it[K.WORD_REPEAT] = v }
    suspend fun setListRepeat(v: Int) = edit { it[K.LIST_REPEAT] = v }
    suspend fun setSpeakArabic(v: Boolean) = edit { it[K.SPEAK_ARABIC] = v }
    suspend fun setAutoAdvance(v: Boolean) = edit { it[K.AUTO_ADVANCE] = v }
    suspend fun setPrefetch(v: Boolean) = edit { it[K.PREFETCH] = v }
    suspend fun setEnglishVoice(v: String) = edit { it[K.EN_VOICE] = v }
    suspend fun setArabicVoice(v: String) = edit { it[K.AR_VOICE] = v }
    suspend fun setEngine(v: String) = edit { it[K.ENGINE] = v }
    suspend fun setArabicEngine(v: String) = edit { it[K.ENGINE_AR] = v }
    suspend fun setCacheLimitMb(v: Int) = edit { it[K.CACHE_MB] = v }
    suspend fun setHumanOnly(v: Boolean) = edit { it[K.HUMAN_ONLY] = v }
    suspend fun setCloudEnabled(v: Boolean) = edit { it[K.CLOUD_ENABLED] = v }
    suspend fun setCloudVoice(v: String) = edit { it[K.CLOUD_VOICE] = v }
    suspend fun setCloudArabicVoice(v: String) = edit { it[K.CLOUD_VOICE_AR] = v }
    suspend fun setCloudRegion(v: String) = edit { it[K.CLOUD_REGION] = v.trim().lowercase() }
    suspend fun setSyncRepo(v: String) = edit { it[K.SYNC_REPO] = v.trim() }
    suspend fun setKokoroSid(v: Int) = edit { it[K.KOKORO_SID] = v }
    suspend fun setUseKokoro(v: Boolean) = edit { it[K.USE_KOKORO] = v }
    suspend fun syncRepo(): String =
        context.dataStore.data.first()[K.SYNC_REPO]?.takeIf { it.isNotBlank() }
            ?: GitHubSync.DEFAULT_REPO
    suspend fun setCloudProvider(v: String) = edit { it[K.CLOUD_PROVIDER] = v }
    suspend fun setCloudModel(v: String) = edit { it[K.CLOUD_MODEL] = v }
    suspend fun setVoiceStrategy(v: String) = edit { it[K.VOICE_STRATEGY] = v }

    /** يراكم الأحرف المولَّدة ليعرض المستخدم تكلفته الفعلية لا تقديراً */
    suspend fun addCloudCharacters(n: Long) = edit {
        it[K.CLOUD_CHARS] = (it[K.CLOUD_CHARS] ?: 0L) + n
    }

    suspend fun resetCloudCharacters() = edit { it[K.CLOUD_CHARS] = 0L }
    suspend fun setAllowSystemVoice(v: Boolean) = edit { it[K.ALLOW_SYSTEM] = v }
    suspend fun setSleepDefault(v: Int) = edit { it[K.SLEEP_DEFAULT] = v }

    suspend fun setTheme(v: ThemeMode) = edit { it[K.THEME] = v.name }
    suspend fun setDynamicColor(v: Boolean) = edit { it[K.DYNAMIC] = v }
    suspend fun setDailyGoal(v: Int) = edit { it[K.GOAL] = v }
    suspend fun setQuizDueOnly(v: Boolean) = edit { it[K.QUIZ_DUE_ONLY] = v }
    suspend fun setQuizLimit(v: Int) = edit { it[K.QUIZ_LIMIT] = v }
    suspend fun setArabicFirst(v: Boolean) = edit { it[K.AR_FIRST] = v }
    suspend fun setSeeded(v: Boolean) = edit { it[K.SEEDED] = v }

    /**
     * يسجّل مراجعة اليوم ويحدّث سلسلة الأيام المتتالية.
     * يوم واحد فائت يكسر السلسلة — وهو ما يجعلها حافزاً حقيقياً.
     */
    suspend fun recordReview(count: Int = 1) = edit { prefs ->
        val today = todayIndex()
        val last = prefs[K.LAST_DAY] ?: 0L
        val streak = prefs[K.STREAK] ?: 0
        when (last) {
            today -> prefs[K.TODAY_COUNT] = (prefs[K.TODAY_COUNT] ?: 0) + count
            today - 1 -> { prefs[K.STREAK] = streak + 1; prefs[K.TODAY_COUNT] = count }
            else -> { prefs[K.STREAK] = 1; prefs[K.TODAY_COUNT] = count }
        }
        prefs[K.LAST_DAY] = today
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        fun todayIndex(): Long {
            val tz = java.util.TimeZone.getDefault()
            val now = System.currentTimeMillis()
            return (now + tz.getOffset(now)) / 86_400_000L
        }
    }
}
