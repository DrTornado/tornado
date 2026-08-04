package com.tornado.vocab.audio

import java.io.File


/** من نطق هذا المقطع فعلاً — KOKORO هو المحرك الأساسي */
enum class VoiceSource { HUMAN, CLOUD, KOKORO, SYSTEM, NONE }

data class SegmentAudio(val samples: ShortArray, val source: VoiceSource) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** ترتيب مصادر الصوت الذي يختاره المستخدم */
enum class VoiceStrategy {
    /** صوت واحد موحّد للمكتبة كلها */
    UNIFIED,

    /** تسجيل بشري حقيقي للكلمة حين يتوفّر، ومحرك الجهاز لكل ما عداه */
    HUMAN_FIRST
}

/**
 * سلسلة مصادر الصوت — الضمانة الأساسية: لا كلمة صامتة أبداً.
 *
 *   كوكورو (الأساسي) ← محرك الجهاز، ومع HUMAN_FIRST يسبقهما تسجيل بشري.
 *
 * وقد مرّت هنا طبقتان حُذفتا:
 *
 * الأولى عصبية على الجهاز (Piper) — ثلاثون ميغابايت داخل التطبيق واثنان
 * وثمانون تُنزَّل، مقابل صوت أضعف من محرك الجهاز نفسه.
 *
 * والثانية سحابية بثلاثة مزوّدين — بُنيت لصوت طبيعي عالٍ، ثم تبيّن أن كل
 * مزوّد يشترط بطاقة دولية لا تعمل. طبقة لا يستطيع صاحبها تشغيلها ليست ميزة
 * معطّلة بل شيفرة تُصان بلا مقابل.
 *
 * وكوكورو ليس عودة إلى الأول: ذاك كان أدنى من محرك الجهاز، وهذا أعلى منه
 * بمسافة سمعها المستخدم بأذنه — وهو نفس المعيار الذي حَكم على الاثنين.
 */
class VoiceChain(
    private val human: HumanAudioRepository,
    private val system: TtsSynthesizer,
    /** كوكورو اختياري: يعمل فقط إن نزّل المستخدم نموذجه من الإعدادات */
    private val kokoro: KokoroEngine? = null
) {

    @Volatile var strategy: VoiceStrategy = VoiceStrategy.UNIFIED

    /** يفضّل المستخدم كوكورو؟ الافتراضي نعم — وهو المحرك الأساسي */
    @Volatile var preferKokoro: Boolean = true

    @Volatile var lastSource: VoiceSource = VoiceSource.NONE
        private set

    /**
     * @param voiceSid صوت كوكورو لهذا النداء وحده — لزرّي اللهجة.
     * @param forceHuman يطلب التسجيل البشري مهما كان وضع الصوت المختار.
     *
     * الثاني يخصّ زرّي 🇺🇸 و🇬🇧 وحدهما: تسجيلٌ بشريّ حقيقي لكلمة مفردة هو
     * بالضبط ما يطلبه من يضغطهما، بينما سرد البطاقة يبقى على المحرّك المختار.
     * وربطهما بوضع واحد جعل الطبقة البشرية تُعطَّل لمن اختار كوكورو — فيسمع
     * الزرّان صوتاً مولَّداً واحداً ولا يفترقان.
     */
    /**
     * @param humanAllowed هل يجوز استعمال تسجيل بشري في هذا النداء؟
     *
     * زرّا 🇺🇸 و🇬🇧 يمنعانه: وظيفتهما مقارنة لهجتين، فيلزمهما صوتان ثابتان
     * يعرفهما المستخدم سلفاً — سارة وجورج. والتسجيل البشري يغيّر الصوت من
     * كلمة لأخرى بلا أن يفهم أحد لماذا، فتضيع المقارنة وهي كل غرض الزرّين.
     *
     * والتسجيلات لم تُحذف: هي مصدر سرد البطاقات كما كانت لمن اختار
     * «الصوت البشري أولاً».
     */
    suspend fun synthesize(
        segment: Segment,
        target: File,
        headword: String? = null,
        voiceSid: Int? = null,
        forceHuman: Boolean = false,
        humanAllowed: Boolean = true
    ): VoiceSource {
        val result = resolve(segment, target, headword, voiceSid, forceHuman, humanAllowed)
        lastSource = result
        return result
    }

    private suspend fun resolve(
        segment: Segment,
        target: File,
        headword: String?,
        voiceSid: Int? = null,
        forceHuman: Boolean = false,
        humanAllowed: Boolean = true
    ): VoiceSource {
        // ١ — تسجيل بشري: للكلمة وجملها فقط، وفي هذا الوضع أو بطلب صريح
        if (humanAllowed && (strategy == VoiceStrategy.HUMAN_FIRST || forceHuman)) {
            when (segment.role) {
                SegRole.HEADWORD -> {
                    val word = headword ?: segment.text
                    if (human.fetchWord(word, target, force = forceHuman, strictAccent = forceHuman)) return VoiceSource.HUMAN
                }
                SegRole.EXAMPLE -> {
                    if (human.fetchSentence(segment.text, target)) return VoiceSource.HUMAN
                }
                SegRole.GENERATED -> Unit
            }
        }

        /*
         * ٢ — كوكورو: المحرك الأساسي للإنجليزية.
         *
         * أعلى جودة متاحة بلا خدمة خارجية ولا بطاقة، ويعمل بلا إنترنت بعد
         * التنزيل. والعربية تتجاوزه — النموذج لا يدعمها، والسقوط لمحرك
         * الجهاز أصدق من نطق مكسور.
         */
        if (preferKokoro && segment.lang == SegLang.EN && kokoro?.isReady == true &&
            kokoro.synthesize(segment.text, target, voiceSid)
        ) return VoiceSource.KOKORO

        /*
         * ٣ — محرك الجهاز.
         *
         * ليس بديلاً أدنى فحسب بل شبكة أمان: يعمل حين لا يكون النموذج
         * منزَّلاً بعد، وللعربية دائماً، ولمن يختاره صراحةً من الإعدادات.
         * وجوده هو ما يجعل الوعد «لا كلمة صامتة» صحيحاً من أول تشغيل.
         */
        if (system.synthesize(segment.text, segment.lang, target)) {
            return VoiceSource.SYSTEM
        }

        // ٤ — كوكورو كملاذ أخير حتى لو اختار المستخدم محرك الجهاز:
        // محرك جهاز معطوب لا يبرّر بطاقة صامتة والبديل حاضر
        if (!preferKokoro && segment.lang == SegLang.EN && kokoro?.isReady == true &&
            kokoro.synthesize(segment.text, target, voiceSid)
        ) return VoiceSource.KOKORO

        return VoiceSource.NONE
    }

    suspend fun canGuaranteeCoverage(): Boolean {
        val status = system.status()
        return status.ready && status.englishAvailable
    }

    /**
     * بصمة تدخل مفتاح التخزين.
     * تغيير الصوت أو الترتيب يعني صوتاً مختلفاً تماماً، فيجب أن يُبطل المخزَّن
     * بدل أن يخلط صوتين في مكتبة واحدة.
     */
    fun signature(): String = buildString {
        append(strategy.name.take(1))
        // كوكورو وصوته يدخلان البصمة: تبديل الصوت يعني إعادة بناء البطاقات
        // هوية النموذج مع رقم الصوت: رقمان متطابقان في نموذجين مختلفين
        // كانا يجعلان البطاقات القديمة تنجو من استبدال النموذج
        if (preferKokoro && kokoro?.isReady == true)
            append("/kk").append(kokoro.modelTag).append(kokoro.sid)
        append("/sys")
    }
}
