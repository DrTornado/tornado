package com.tornado.vocab.data

/**
 * تنقية المعاني قبل حفظها.
 *
 * المصادر المجانية تُرجع أكثر من معانٍ: صيغاً صرفية («present participle of ski»)،
 * وملاحظات استعمال بين أقواس، وبدائل مكدّسة خلف فاصلة منقوطة، ووسوماً نحوية.
 * وكنّا نبتلع ذلك كله ثم نمرّره على مترجم آلي — فينتج ما رآه المستخدم فعلاً:
 * «المشاركة الحالية و gerund من التزلج».
 *
 * الخلل لم يكن في الترجمة بل فيما نُطعمه لها. فالتنقية تسبق الترجمة هنا،
 * ونرفض أي ناتج عربي يحمل آثار فشل بدل أن نعرضه.
 */
object MeaningQuality {

    /**
     * مداخل ليست معاني بل إحالات صرفية.
     * قيمتها لمن يقرأ قاموساً لغوياً، أما من يحفظ مفردات فهي ضجيج خالص:
     * لا تشرح شيئاً وتشغل مكان معنى حقيقي.
     */
    private val REFERENCE_ONLY = Regex(
        "^\\s*(" + listOf(
            "present participle", "past participle", "simple past", "past tense",
            "plural", "singular", "third-person", "comparative", "superlative",
            "alternative form", "alternative spelling", "obsolete form", "obsolete spelling",
            "misspelling", "archaic form", "archaic spelling", "inflection", "gerund",
            "abbreviation", "initialism", "acronym", "clipping", "contraction",
            "diminutive", "eye dialect"
        ).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    /** إحالة صريحة إلى مدخل آخر بلا شرح خاص بها */
    private val CROSS_REFERENCE = Regex(
        """^\s*(see|see also|used in|form of|variant of|compare)\b""",
        RegexOption.IGNORE_CASE
    )

    /** وسوم المجال والسِّجل التي يضعها ويكاموس في أول التعريف */
    private val LEADING_LABEL = Regex(
        """^\s*\((?:[^()]{1,60})\)\s*""",
    )

    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 170
    // يطابق سقف DictionaryService — رقمان مختلفان يعنيان أن التنقية تقصّ ما جمعه الجلب
    private const val MAX_MEANINGS = 8

    /**
     * مجالات متخصّصة تُؤخِّر المعنى ولا تحذفه.
     *
     * ويكاموس يرتّب المعاني تاريخياً لا حسب الشيوع، فيأتي المعنى التشريحي
     * أولاً والمعنى الشائع رابعاً. والمستخدم رأى «articulation» فقُدِّم له
     * «مفصل» بينما ما يقصده الناس هو «وضوح النطق» — والبطاقة تفقد مصداقيتها
     * كلها بمعنىً واحد كهذا.
     *
     * والتأخير لا الحذف: المعنى التشريحي صحيح ويفيد من يقرؤه، لكنه ليس ما
     * يبدأ به متعلّم.
     */
    private val NARROW_DOMAINS = setOf(
        "anatomy", "zoology", "botany", "biology", "medicine", "pathology",
        "chemistry", "physics", "astronomy", "geology", "mathematics",
        "phonetics", "phonology", "linguistics", "grammar",
        "music", "heraldry", "nautical", "military", "law", "legal",
        "computing", "programming", "engineering", "architecture",
        "archaic", "obsolete", "dated", "historical", "rare",
        "dialectal", "regional", "slang", "poetic", "literary"
    )

    /**
     * يعيد ترتيب المعاني: العام قبل المتخصّص، مع حفظ الترتيب داخل كل فئة.
     *
     * المجال يُقرأ من القوس في أوّل التعريف لا من حقل الوسوم. وهذا ما أثبته
     * الفحص: حقل `tags` يحمل وسوماً نحوية («countable») بينما المجال نفسه
     * («anatomy»، «phonetics»، «music») مكتوبٌ داخل النصّ. ترتيبٌ مبنيّ على
     * الحقل كان سيبدو صحيحاً في الشيفرة ولا يغيّر شيئاً في البطاقة.
     *
     * والترتيب مستقرّ عمداً: معنيان بنفس الدرجة يبقيان كما وردا من المصدر،
     * فلا يتبدّل شكل البطاقة بين بناء وآخر بلا سبب.
     */
    fun rankBySpread(items: List<Meaning>): List<Meaning> =
        items.withIndex()
            .sortedWith(compareBy({ if (isNarrow(it.value.en)) 1 else 0 }, { it.index }))
            .map { it.value }

    /**
     * يرتّب المعاني بحسب شيوع الاستعمال الحقيقي.
     *
     * [order] قائمة تعريفات مرتّبة تواتُرياً من مصدر خارجي. وهي أدقّ بكثير من
     * أي حكم على شكل النصّ: ترتيب ويكاموس تاريخي، فيتصدّر «المفصل» كلمةَ
     * articulation ويتصدّر «ضفة النهر» كلمةَ bank — بينما الناس تقصد
     * «الفصاحة» و«المصرف». والتواتر يعرف ذلك، والقواعد لا تعرفه.
     *
     * وما لا يُعرف ترتيبه يُلحَق بالآخر على ترتيبه الأصلي، فلا يضيع معنى
     * لمجرّد غيابه عن قائمة المرجع.
     */
    fun rankByFrequency(items: List<Meaning>, order: List<String>): List<Meaning> {
        if (order.isEmpty()) return rankBySpread(items)
        val rank = order.withIndex().associate { (i, d) -> key(d) to i }
        return items.withIndex()
            .sortedWith(
                compareBy(
                    { rank[key(it.value.en)] ?: Int.MAX_VALUE },
                    { it.index }
                )
            )
            .map { it.value }
    }

    /** مفتاح مطابقة متسامح — الفروق في الترقيم والمسافات لا تكسر المطابقة */
    private fun key(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().take(60)

    /** هل يبدأ التعريف بوسم مجال متخصّص؟ */
    private fun isNarrow(definition: String): Boolean {
        val head = Regex("""^\s*\(([^()]{1,60})\)""").find(definition)?.groupValues?.get(1)
            ?: return false
        return head.split(',', ';', '|')
            .flatMap { it.split(" or ") }
            .any { it.trim().lowercase() in NARROW_DOMAINS }
    }

    /** هل هذا النص معنى حقيقياً أم إحالة صرفية؟ */
    fun isRealDefinition(text: String): Boolean {
        val t = text.trim()
        if (t.length < MIN_LENGTH) return false
        if (REFERENCE_ONLY.containsMatchIn(t)) return false
        if (CROSS_REFERENCE.containsMatchIn(t)) return false
        return true
    }

    /**
     * يُنظّف تعريفاً إنجليزياً.
     *
     * البدائل خلف الفاصلة المنقوطة تُقصّ: القارئ يريد معنى واحداً واضحاً لا
     * ثلاث صياغات متشابهة في سطر واحد. والوسم الافتتاحي يُحذف لأنه تصنيف
     * لا تعريف.
     */
    fun cleanEnglish(raw: String): String {
        var t = raw.trim()
        t = LEADING_LABEL.replace(t, "")
        t = t.substringBefore(';').trim()
        t = t.replace(Regex("\\s{2,}"), " ")
        t = t.trimEnd(',', ':', '—', '-', ' ')
        if (t.length > MAX_LENGTH) {
            // نقطع عند حدّ كلمة لا في منتصفها، وبلا نقاط حذف مبعثرة
            t = t.take(MAX_LENGTH).substringBeforeLast(' ').trimEnd(',', ' ') + "…"
        }
        if (t.isNotBlank() && !t.last().isLetterOrDigit() && t.last() != '…') {
            t = t.dropLast(1).trim()
        }
        return t
    }

    /**
     * هل الترجمة العربية صالحة للعرض؟
     *
     * المترجم الآلي يفشل بطرق مميّزة: يترك مصطلحات نحوية بالإنجليزية داخل
     * النص، أو يعيد الجملة كما هي. عرض هذا الناتج أسوأ من إخفائه — المستخدم
     * يقرأ ما لا معنى له ويفقد الثقة في البطاقة كلها.
     */
    fun isUsableArabic(arabic: String, english: String): Boolean {
        val t = arabic.trim()
        if (t.length < 3) return false
        if (t.equals(english.trim(), ignoreCase = true)) return false

        /*
         * بقايا ترميز.
         *
         * خدمة الترجمة تُرجع أحياناً وسوماً مثل <g id="1"> حول النص. وفحص
         * الكلمات اللاتينية لا يمسكها لأن «g» و«id» أقصر من الحدّ، فتظهر
         * للمستخدم داخل المعنى كما رآها فعلاً. الوسم يُنظَّف قبل هذا الفحص،
         * وبقاؤه هنا يعني نصاً مشوّهاً لا يُعرض.
         */
        if (t.contains('<') || t.contains('>') || t.contains("&#")) return false

        /*
         * ردود الفشل المقنّعة.
         *
         * خدمة الترجمة لا تُعلن عجزها برمز خطأ، بل تُرجع نصاً عربياً سليم
         * الحروف بلا معنى — «ماذا؟» مقابل تعريف كامل. فينجو من كل فحص يسأل
         * «هل هذا عربي؟» ويظهر للمستخدم كأنه شرح.
         *
         * الطول هو ما يفضحه: ترجمة جملة تقارب الجملة، ولا تختصرها إلى كلمة.
         */
        val source = english.trim()
        if (source.length >= 12 && t.length < source.length * 0.3) return false

        // سؤال مقابل تعريف ليس ترجمة بل استفهام المترجم نفسه
        if ((t.endsWith('؟') || t.endsWith('?')) && !source.endsWith('?')) return false

        val arabicLetters = t.count { it in '؀'..'ۿ' }
        if (arabicLetters < 3) return false

        // كلمة لاتينية كاملة داخل نص عربي = مصطلح عجز المترجم عنه
        if (Regex("[A-Za-z]{3,}").containsMatchIn(t)) return false

        // العربية يجب أن تغلب على النص لا أن تكون بقيّة فيه
        return arabicLetters.toDouble() / t.count { !it.isWhitespace() }.coerceAtLeast(1) > 0.5
    }

    /**
     * العربية تُقصّ عند أول فاصلة منقوطة كالإنجليزية تماماً.
     *
     * ترك البدائل يجعل سطر المعنى ثلاث صياغات متشابهة محشورة معاً، وهو أسوأ
     * في العربية لأن الترجمة الآلية تزيدها ركاكة مع كل بديل.
     */
    fun cleanArabic(raw: String): String = raw.trim()
        // الوسوم والكيانات تُزال أولاً، وإلا قُصّ النص عند فاصلة داخل وسم
        .replace(Regex("</?[a-zA-Z][^>]*>"), "")
        .replace(Regex("&[a-zA-Z#0-9]{1,8};"), " ")
        .substringBefore('؛')
        .substringBefore(';')
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .trimEnd('،', '؛', '.', ':', '-', ' ')

    /**
     * يمرّ على قائمة معانٍ فيُسقط الضجيج ويزيل التكرار ويحدّ العدد.
     * التكرار يُقاس بالنص الإنجليزي بعد التنقية، فصيغتان مختلفتان لمعنى واحد
     * تُحسبان واحدة.
     */
    fun refine(meanings: List<Meaning>): List<Meaning> {
        val kept = ArrayList<Set<String>>(MAX_MEANINGS)
        val out = ArrayList<Meaning>(MAX_MEANINGS)
        for (m in meanings) {
            if (out.size >= MAX_MEANINGS) break
            if (!isRealDefinition(m.en)) continue
            val en = cleanEnglish(m.en)
            if (en.length < MIN_LENGTH) continue
            val words = significantWords(en)
            if (kept.any { overlaps(it, words) }) continue
            kept += words
            val ar = cleanArabic(m.ar).takeIf { isUsableArabic(it, en) }.orEmpty()
            out += Meaning(m.pos, en, ar)
        }
        return out
    }

    private val STOP_WORDS = setOf(
        "a", "an", "the", "of", "to", "in", "on", "for", "as", "or", "and",
        "with", "that", "which", "is", "are", "be", "by", "at", "from", "its"
    )

    private fun significantWords(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()

    /**
     * تكراران بصياغتين مختلفتين.
     *
     * المصادر تعيد المعنى نفسه بمفردات متبادلة — «utilizing» و«using» — فتنجو
     * من مقارنة النص الحرفي وتظهر للمستخدم سطرين متطابقين في المعنى. المقارنة
     * بالكلمات الدالة تمسكها: تشارُك معظم المفردات يعني معنى واحداً.
     */
    private fun overlaps(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.count { it in b }
        return shared.toDouble() / minOf(a.size, b.size) >= 0.7
    }
}
