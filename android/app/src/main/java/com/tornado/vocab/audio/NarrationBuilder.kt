package com.tornado.vocab.audio

import com.tornado.vocab.data.Enrichment
import com.tornado.vocab.data.Linguistics
import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.Word

/** لغة المقطع — تحدد أي صوت يقرؤه */
enum class SegLang { EN, AR }

/** دور المقطع — يحدد مصدر الصوت المسموح له */
enum class SegRole {
    /** الكلمة نفسها — لها تسجيل بشري في ويكيميديا */
    HEADWORD,
    /** جملة مثال — لها تسجيل بشري في Tatoeba */
    EXAMPLE,
    /** نص من إنشاء التطبيق أو القاموس — لا وجود لتسجيل بشري له */
    GENERATED
}

/**
 * يحوّل نصاً مكتوباً إلى نصّ يصلح للنطق.
 *
 * المكتوب والمنطوق ليسا واحداً. القارئ يرى «and/or» فيفهمها في لمحة، ومحرك
 * النطق ينطقها «and slash or» — والمستمع يسمع كلمة لا وجود لها ويظنها جزءاً
 * من التعريف. وكذلك «(transitive)» التي يضعها المصدر وسماً نحوياً: تُقرأ
 * بصوت عالٍ في وسط الشرح بلا معنى لمن يستمع.
 *
 * التنظيف هنا لا في مواضع الاستدعاء: كل نصّ منطوق يمرّ من هذه البوابة، فما
 * يُصلَح فيها يُصلَح للكلمات والملاحظات معاً.
 */
internal fun speakable(raw: String): String {
    var t = raw.trim()
    // الوسوم النحوية بين قوسين في أول التعريف — تُقرأ ولا تُفهم مسموعة
    t = t.replace(Regex("""^\s*\((?:[^()]{1,40})\)\s*"""), "")
    // الشرطة المائلة بين كلمتين تعني «أو»، وبين رقمين تعني كسراً
    t = t.replace(Regex("""(?<=\w)\s*/\s*(?=\w)"""), " or ")
    t = t.replace("/", " ")
    // رموز تُقرأ حرفياً بلا فائدة للمستمع
    t = t.replace(Regex("""[\[\]{}<>|*_~#^]"""), " ")
    t = t.replace(Regex("""\s*&\s*"""), " and ")
    return t.replace(Regex("\\s{2,}"), " ").trim()
}

data class Segment(
    val text: String,
    val lang: SegLang,
    val pauseMs: Int = 450,
    val role: SegRole = SegRole.GENERATED
) {
    val isLabel: Boolean get() = role == SegRole.GENERATED
}

/**
 * كيف يُبنى صوت البطاقة.
 *
 * HUMAN_ONLY هو الوضع الافتراضي وهو جوهر المنتج: لا يُنطق إلا ما سجّله إنسان
 * حقيقي — الكلمة وأمثلتها. كل ما عداه (تعريفات القاموس، الترجمات، عبارات
 * الربط التي يولّدها التطبيق) يُقرأ على الشاشة لا في الأذن.
 *
 * السبب أن هذه النصوص لا تملك تسجيلات بشرية ولن تملكها: لم يسجّلها أحد لأنها
 * ليست لغة متداولة بل سقالة أنشأها التطبيق. توليدها آلياً هو ما كان يجعل
 * التجربة تبدو آلية، فالحل حذفها لا تحسين توليدها.
 */
enum class NarrationMode {
    /** الكلمة وأمثلتها فقط، بأصوات بشرية حقيقية */
    HUMAN_ONLY,
    /** الكلمة وأمثلتها ومعانيها — يسمح بصوت عصبي لما لا تسجيل له */
    RICH
}

/**
 * ماذا نفعل بكلمة لا تسجيل بشري لها.
 * SYNTHESIZE هو الافتراضي ولا يُغيَّر عادةً: كلمة صامتة تجربة مكسورة،
 * وصوت عصبي جيد أفضل من لا شيء بما لا يقاس.
 */
enum class MissingAudioPolicy { SKIP, SYNTHESIZE }

/** مستوى تفصيل السرد في الوضع الغني */
enum class NarrationDetail { BRIEF, FULL }

/**
 * يبني نص السرد لبطاقة واحدة.
 * الوقفة تُحدَّد حسب دور المقطع لا حسب طوله، فيسمع المتعلّم بنية واضحة.
 */
object NarrationBuilder {

    /**
     * @param extra أقسامٌ لا موضع لها في `Word` أصلاً: الأضداد وملاحظات
     *   الاستعمال وأنماط التركيب وملاحظة النطق والأفعال المركّبة والتعابير.
     *
     *   كان البنّاء يقرأ `Word` وحدها، فيقرأ الصوتُ نصفَ البطاقة ويسكت عن
     *   نصفها — لا لأن النصف الآخر ناقص بل لأنه في جدولٍ آخر لا يصل إليه.
     *   والمتعلّم يسمع أكثر ممّا يقرأ، فما لا يُقال لا يُحفَظ.
     */
    fun build(
        word: Word,
        repeat: Int,
        mode: NarrationMode,
        detail: NarrationDetail,
        speakArabic: Boolean,
        extra: Enrichment? = null
    ): List<Segment> = when (mode) {
        NarrationMode.HUMAN_ONLY -> buildHumanOnly(word, repeat)
        NarrationMode.RICH -> buildRich(word, repeat, detail, speakArabic, extra)
    }

    /**
     * الوضع البشري الخالص.
     * الكلمة تُنطق، ثم وقفة يقرأ فيها المتعلّم المعنى من الشاشة، ثم الأمثلة.
     * الوقفة هنا ليست فراغاً بل جزء من الطريقة: وقت الاسترجاع قبل رؤية الجواب.
     */
    private fun buildHumanOnly(word: Word, repeat: Int): List<Segment> {
        val out = mutableListOf<Segment>()
        val rep = repeat.coerceAtLeast(1)

        repeat(rep) {
            out += Segment(word.word, SegLang.EN, pauseMs = 1400, role = SegRole.HEADWORD)
        }
        word.examples.take(3).forEach { ex ->
            if (ex.en.isNotBlank()) {
                out += Segment(ex.en.trim(), SegLang.EN, pauseMs = 1100, role = SegRole.EXAMPLE)
            }
        }
        return out
    }

    private fun buildRich(
        word: Word,
        repeat: Int,
        detail: NarrationDetail,
        speakArabic: Boolean,
        extra: Enrichment? = null
    ): List<Segment> {
        val rep = repeat.coerceAtLeast(1)
        val detailed = detail == NarrationDetail.FULL
        val out = mutableListOf<Segment>()

        fun push(text: String?, lang: SegLang?, pause: Int = 450, role: SegRole = SegRole.GENERATED) {
            if (text.isNullOrBlank()) return
            val t = speakable(text).replace(Regex("\\.{2,}$"), ".")
            if (t.isEmpty()) return
            val l = lang ?: if (Linguistics.isArabic(t)) SegLang.AR else SegLang.EN
            if (l == SegLang.AR && !speakArabic) return
            out += Segment(t, l, pause, role)
        }

        repeat(rep) { push(word.word, SegLang.EN, 1300, SegRole.HEADWORD) }

        val meanings = word.meanings

        /**
         * العربية تُنطق في الوضع الكامل وحده.
         * الوضع المختصر مخصّص للمراجعة السريعة بالإنجليزية: كل المعاني تُقرأ
         * مهما بلغ عددها، لكن بلا ترجمة — فالترجمة تضاعف زمن البطاقة تقريباً
         * وتكسر إيقاع المراجعة السريعة.
         */
        fun pushMeaning(m: Meaning) {
            repeat(rep) {
                push(m.en + ".", SegLang.EN, 900)
                if (detailed && m.ar.isNotBlank()) push(m.ar + ".", SegLang.AR)
            }
        }

        if (detailed && word.arabicPron.isNotBlank()) push(word.arabicPron, SegLang.AR)
        if (detailed && word.pos.isNotEmpty()) {
            push("It is a " + word.pos.joinToString(", or a ") + ".", SegLang.EN, 1000)
        }

        fun pushPair(en: String, ar: String) {
            repeat(rep) {
                push("$en.", SegLang.EN, 900)
                if (ar.isNotBlank()) push("$ar.", SegLang.AR)
            }
        }

        // SHORT: الكلمة ومعانيها فقط — بلا نوعها ولا عبارات ربط
        when {
            !detailed -> meanings.forEach { pushMeaning(it) }
            meanings.size == 1 -> { push("It means.", SegLang.EN, 500); pushMeaning(meanings[0]) }
            meanings.size > 1 -> {
                push("It has ${meanings.size} meanings.", SegLang.EN, 1000)
                meanings.forEachIndexed { i, m ->
                    val posBit = m.pos?.takeIf { it.isNotBlank() }?.let { ", as a $it" } ?: ""
                    push("Number ${i + 1}$posBit.", SegLang.EN, 600)
                    pushMeaning(m)
                }
            }
        }

        if (!detailed) return out

        // كل أقسام تطبيق الويب محفوظة كما هي — الوضع الغني لا ينقص عنه شيئاً
        if (word.inflections.isNotEmpty()) {
            push("Its forms are.", SegLang.EN, 500)
            push(word.inflections.joinToString(", ") + ".", SegLang.EN, 1000)
        }
        if (word.derivatives.isNotEmpty()) {
            push("Related words.", SegLang.EN, 500)
            word.derivatives.forEach { pushPair(it.en, it.ar) }
        }
        if (word.synonyms.isNotEmpty()) {
            push("Similar words.", SegLang.EN, 500)
            word.synonyms.forEach { pushPair(it.en, it.ar) }
        }
        if (word.collocations.isNotEmpty()) {
            push("Common combinations.", SegLang.EN, 500)
            word.collocations.forEach { pushPair(it.en, it.ar) }
        }
        val examples = word.examples.take(3)
        if (examples.isNotEmpty()) {
            push(if (examples.size > 1) "Examples." else "Example.", SegLang.EN, 500)
            examples.forEach { ex ->
                if (ex.en.isNotBlank()) {
                    // المثال يبقى دوره EXAMPLE ليُطلب له تسجيل بشري أولاً
                    repeat(rep) {
                        push(ex.en.trim(), SegLang.EN, 900, SegRole.EXAMPLE)
                        if (ex.ar.isNotBlank()) push(ex.ar + ".", SegLang.AR)
                    }
                }
            }
        }
        word.differences.forEach {
            push("Note the difference: ${it.en}.", SegLang.EN)
            if (it.ar.isNotBlank()) push("${it.ar}.", SegLang.AR)
        }

        /*
         * بقيّة البطاقة — ما لا يحمله `Word` ويسكن الإثراء.
         *
         * كان الصوت يقف عند «الفروق»، فيسمع المتعلّم نصف ما يقرأ: لا أضداد،
         * ولا نمط تركيبٍ واحد، ولا ملاحظة نطقٍ ولا استعمال. والترتيب هنا هو
         * ترتيب الشاشة نفسه، فما يُقرأ هو ما يُسمَع بلا اختلاف.
         */
        extra?.let { e ->
            if (e.antonyms.isNotEmpty()) {
                push("Opposites.", SegLang.EN, 500)
                e.antonyms.forEach { pushPair(it.en, it.ar) }
            }
            if (e.grammarPatterns.isNotEmpty()) {
                push("Grammar patterns.", SegLang.EN, 500)
                e.grammarPatterns.forEach { p ->
                    pushPair(p.en, p.ar)
                    if (p.ex.isNotBlank()) {
                        push(p.ex.trim(), SegLang.EN, 900, SegRole.EXAMPLE)
                        if (p.exAr.isNotBlank()) push("${p.exAr}.", SegLang.AR)
                    }
                }
            }
            e.phrasalVerbs.orEmpty().takeIf { it.isNotEmpty() }?.let { list ->
                push("Phrasal verbs.", SegLang.EN, 500)
                list.forEach { pushPair(it.phrase, it.gloss) }
            }
            e.idioms.orEmpty().takeIf { it.isNotEmpty() }?.let { list ->
                push("Idioms.", SegLang.EN, 500)
                list.forEach { pushPair(it.phrase, it.gloss) }
            }
            if (e.pronunciationNote.isNotEmpty()) {
                push("A note on pronunciation.", SegLang.EN, 500)
                e.pronunciationNote.forEach { pushPair(it.en, it.ar) }
            }
            if (e.usageNotes.isNotEmpty()) {
                push("How it is used.", SegLang.EN, 500)
                e.usageNotes.forEach { n ->
                    if (n.ar.isNotBlank()) push("${n.ar}.", SegLang.AR)
                    if (n.ex.isNotBlank()) {
                        push(n.ex.trim(), SegLang.EN, 900, SegRole.EXAMPLE)
                        if (n.exAr.isNotBlank()) push("${n.exAr}.", SegLang.AR)
                    }
                }
            }
        }
        return out
    }

    /** النص الكامل — يخدم عرض "النص المقروء" في المشغّل */
    fun transcript(
        word: Word,
        repeat: Int,
        mode: NarrationMode,
        detail: NarrationDetail,
        speakArabic: Boolean,
        extra: Enrichment? = null
    ): String =
        build(word, repeat, mode, detail, speakArabic, extra).joinToString("\n") { it.text }
}
