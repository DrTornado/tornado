package com.tornado.vocab.data

/**
 * المصدر الوحيد للبطاقة كما تُقرأ وكما تُسمع.
 *
 * كان الدمج يقع في شاشة الكلمة وحدها، وكان ذلك يكفي لو أن الشاشة هي الطريق
 * الوحيد إلى البطاقة. وليست كذلك: القائمة تنسدل فيها البطاقة في مكانها، وزرّ
 * «Full» يقرأها، وتبويب الاستماع يبنيها كلّها. وكلّ أولئك كانوا يقرأون
 * `repository.word(id)` خاماً — فيُعرض ما بناه التطبيق لنفسه قديماً، ويُسمَع
 * معه، بينما ما كُتب بيدٍ يجلس في قاعدة الإثراء لا يراه أحد إلا من فتح شاشة
 * الكلمة كاملةً.
 *
 * وهذا سبب شكوى «الشرح مخلوط والصوت قديم»: لم تكن البطاقة المكتوبة تصل إلى
 * الموضع الذي ينظر فيه صاحبها.
 *
 * فالدمج نزل إلى طبقة البيانات: من أراد بطاقةً ليعرضها أو ينطقها يطلبها من
 * هنا، ولا يبقى في التطبيق طريقٌ إلى نصّ البطاقة يتجاوز الإثراء.
 *
 * ويبقى `repository.word` على حاله للكتابة والتعديل: النسخة المدموجة للعرض
 * وحده — لو حُفظت لانتقل الإثراء إلى بيانات المستخدم ثم رُفع إلى المستودع.
 */
class CardSource(
    private val repository: WordRepository,
    private val enrich: EnrichSync
) {

    /**
     * البطاقة المدموجة ومعها الإثراء كما وصل.
     *
     * ويُعاد الإثراء معها لأن فيه أقساماً لا موضع لها في `Word` أصلاً —
     * الأضداد وملاحظات الاستعمال وأنماط التركيب وملاحظة النطق. ومن أراد
     * بطاقةً كاملة على أي شاشة وجد كل ذلك من نداءٍ واحد.
     */
    suspend fun full(id: Long): DisplayCard? {
        val raw = repository.word(id) ?: return null
        val e = runCatching { enrich.forWord(raw.word) }.getOrNull()
        return DisplayCard(raw.withEnrichment(e), e)
    }

    /** ما يُنطق — البطاقة المدموجة وحدها */
    suspend fun card(id: Long): Word? = full(id)?.word
}

/** البطاقة كما تُعرض: النصّ المدموج، ومعه ما لا يحمله `Word` من أقسام */
data class DisplayCard(val word: Word, val extra: Enrichment?)

private fun norm(s: String) = s.trim().lowercase()

private val ARABIC = Regex("[\\u0600-\\u06FF]")
private val TAIL_DOT = Regex("[ \\u00A0]*\\.\\s*$")

/**
 * سطرٌ عربيّ بلا نقطةٍ لاتينية في آخره.
 *
 * النقطة محايدة الاتّجاه، فتصير جرياً مستقلاً في فقرةٍ من اليمين إلى اليسار
 * فينزل معها آخر الكلام سطراً — قيس على الجهاز: ١٢٧ بكسل مقابل ٦٧.
 *
 * ونظّفتُ منها بطاقاتي المكتوبة، فبقي ما يبنيه التطبيق لنفسه من قاموسه
 * الآليّ حين تُضاف كلمةٌ جديدة: «تتجاوز النفقات الحكومية الفعلية الميزانية
 * بشكل كبير.» — وهي أكثر ما يراه المستخدم قبل أن تُكتب بطاقتها. فالتنظيف
 * موضعه العرض لا الملفّ، ليشمل كل مصدرٍ حاضرٍ ومستقبَل.
 */
internal fun arLine(s: String): String =
    if (s.isNotBlank() && ARABIC.containsMatchIn(s)) TAIL_DOT.replace(s, "") else s

private fun Meaning.tidy() = copy(ar = arLine(ar))
private fun LangPair.tidy() = copy(ar = arLine(ar), exAr = arLine(exAr), note = arLine(note))
private fun List<LangPair>.tidy() = map { it.tidy() }

/**
 * البطاقة كما تُعرض: القائم أوّلاً، والإثراء يملأ الفراغ.
 *
 * نسخةٌ لا تُحفظ: `Word` تُرفع إلى المستودع، فالكتابة فيها تنقل خطأ العرض
 * إلى بيانات المستخدم. وما لا يُكتب لا يُفسد.
 *
 * والفراغ وحده يُملأ — ما بناه التطبيق لنفسه لا يُزاح، لأن فيه أحياناً
 * ترجمةً عربية ليست في القاعدة.
 */
/**
 * ما لم يُكتب بيدٍ لا يُعرض ولا يُنطق.
 *
 * القاموس الآليّ كان يملأ البطاقة عند إضافة الكلمة: مرادفاتٌ بلا عربيّة
 * («present · true · genuine»)، ومتلازماتٌ بلا ترجمة، وأمثلةٌ مترجمة آلياً.
 * وصاحب المكتبة طلب إلغاءه نهائياً — وهو محقّ: بطاقةٌ فارغة تقول «لم تُكتب
 * بعد» أصدق من بطاقةٍ ممتلئة بما لا يُوثق به.
 *
 * ويبقى ما ليس من القاموس: الكلمة ورمزها الصوتيّ وتسجيلاتُ نطقها وتقدّم
 * مراجعتها — تلك ليست شرحاً يُقرأ.
 */
private fun Word.withoutMachineContent(): Word = copy(
    meanings = emptyList(), derivatives = emptyList(), synonyms = emptyList(),
    collocations = emptyList(), examples = emptyList(), differences = emptyList()
)

internal fun Word.withEnrichment(e: Enrichment?): Word {
    if (e == null || !e.curated) return withoutMachineContent()
    val extraMeanings = e.meanings.filter { m ->
        m.en.isNotBlank() && meanings.none { norm(it.en) == norm(m.en) }
    }
    /*
     * المراجَعة تغلب في المفردات كما تغلب في القوائم.
     *
     * كان الخام يفوز في هذه الحقول ما دام غير فارغ — «ما بناه التطبيق لنفسه
     * لا يُزاح». وذلك صحيحٌ للبطاقة الآليّة، وخطأٌ للمكتوبة بيد: نطقُ `abide`
     * في القاموس الآليّ «بايد» بمقطعٍ واحد، وفي البطاقة المكتوبة «أَبايْد»
     * بمقطعين كما يقتضي /əˈbaɪd/. فكان القارئ يرى الخطأ ويسمعه، والصواب
     * مكتوبٌ لا يصل إليه.
     *
     * وكذلك المستوى: يظهر «‏≈ C1» في سطر القائمة و«CEFR B2» داخل البطاقة —
     * رقمان متناقضان في شاشةٍ واحدة، مصدرهما هذا التفضيل نفسه.
     */
    fun pick(mine: String, base: String) = if (e.curated && mine.isNotBlank()) mine else base

    return copy(
        ipaUS = pick(e.ipaUS, ipaUS.ifBlank { e.ipaUS }),
        ipaUK = pick(e.ipaUK, ipaUK.ifBlank { e.ipaUK }),
        ipa = pick(
            e.ipaGen,
            if (ipaUS.isBlank() && ipaUK.isBlank() && e.ipaUS.isBlank() &&
                e.ipaUK.isBlank()
            ) ipa.ifBlank { e.ipaGen } else ipa
        ),
        arabicPron = pick(e.arabicPron, arabicPron.ifBlank { e.arabicPron }),
        oxford = pick(e.oxford, oxford.ifBlank { e.oxford }),
        cefr = pick(e.cefr, cefr.ifBlank { e.cefr }),
        estCefr = pick(
            e.cefrEst,
            if (cefr.isBlank() && e.cefr.isBlank()) estCefr.ifBlank { e.cefrEst } else estCefr
        ),
        // البطاقة المكتوبة تحلّ محلّ ما بناه التطبيق لنفسه، لا تُضاف إليه
        pos = e.pos.ifEmpty { pos },
        meanings = e.meanings.map { it.tidy() },
        inflections = e.inflections.ifEmpty { inflections },
        /*
         * القوائم تُدمج هنا لا عند الرسم.
         *
         * كان الدمج في الشاشة وحدها، فقرأ الصوتُ الخام: يُعرض ما كتبناه
         * ويُسمَع ما لم نكتبه. ونقطةُ دمجٍ واحدة تمنع افتراقهما مستقبلاً.
         */
        derivatives = e.derivatives.tidy(),
        synonyms = e.synonyms.tidy(),
        collocations = e.collocations.tidy(),
        examples = e.examples.tidy(),
        differences = e.differences.tidy()
    )
}

/*
 * المراجَعة تحلّ محلّ القديمة، ولا تُضاف إليها.
 *
 * كان الدمج ضمّاً، وبطاقة التطبيق القديمة جاءت من قاموسٍ آليّ كثيرٌ من
 * معانيها وأمثلتها بلا عربية. فتتصدّر سطورٌ إنجليزية عارية ما كُتب
 * كاملاً، فيظنّ القارئ البطاقة ناقصة — وهي مسبوقة بما لا ينفع لا ناقصة.
 *
 * وغير المراجَعة تبقى على الضمّ: فيها ما ليس عندنا، وحذفُه خسارة.
 */
private fun take(base: List<LangPair>, extra: List<LangPair>,
                 curated: Boolean): List<LangPair> =
    if (curated && extra.isNotEmpty()) extra else mergedPairs(base, extra)

private fun mergedPairs(base: List<LangPair>, extra: List<LangPair>?): List<LangPair> {
    if (extra.isNullOrEmpty()) return base
    val seen = base.mapTo(HashSet()) { norm(it.en) }
    return base + extra.filter { it.en.isNotBlank() && seen.add(norm(it.en)) }
}
