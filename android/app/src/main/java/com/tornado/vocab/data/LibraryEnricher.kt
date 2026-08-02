package com.tornado.vocab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * يملأ نواقص البطاقات القديمة.
 *
 * المكتبة بُنيت على دفعات، وكل دفعة بمصادر ومنطق ذلك الوقت. والنتيجة أن نصفها
 * تقريباً بلا جملة مثال، ونصفها بلا رابط نطق بشري، ونصفها بلا مستوى — لا لأن
 * المعلومة غير موجودة بل لأن البطاقة بُنيت قبل أن نتعلّم كيف نجلبها.
 *
 * وكلمة بلا جملة مثال تُحفظ ولا تُستعمل. هذا أعمق أثراً على التعلّم من نبرة
 * الصوت التي أنفقنا عليها يوماً كاملاً.
 *
 * المرور تدريجي ومحترم للمصادر المجانية: بضع كلمات في الجولة، مهلة بينها،
 * ويتوقّف فور إغلاق التطبيق. وما يُملأ يُعلَّم فلا يُعاد سؤاله.
 */
class LibraryEnricher(
    private val repository: WordRepository,
    private val dictionary: DictionaryService,
    private val examples: ExampleSource = ExampleSource()
) {

    /**
     * @param budget كم كلمة تُعالَج في هذه الجولة. صغير عمداً: المصادر مجانية
     *   ومحدودة الطلبات، والإثراء عمل خلفي لا يستحق أن يزاحم ما يفعله المستخدم.
     */
    suspend fun runPass(budget: Int = 8): Int = withContext(Dispatchers.IO) {
        var filled = 0
        val candidates = repository.allWords().filter { it.isEligible() }.take(budget)

        for (word in candidates) {
            coroutineContext.ensureActive()
            val fresh = runCatching { dictionary.lookup(word.word) }.getOrNull()
            var updated = if (fresh is LookupResult.Success) {
                word.mergeMissingFrom(fresh.word)
            } else {
                word.withAttemptRecorded()
            }

            /*
             * مصدر الجمل يُسأل عند العجز لا قبله.
             *
             * القاموس يعطي التعريف مجاناً وبلا حصة، فلا معنى لإنفاق طلب من حصة
             * محدودة على كلمة وجدنا مثالها أصلاً. والسؤال هنا يقع فقط حين يبقى
             * الفراغ بعد كل ما هو مجاني.
             */
            if (updated.examples.isEmpty()) {
                val sentences = runCatching { examples.examplesFor(word.word) }
                    .getOrDefault(emptyList())
                if (sentences.isNotEmpty()) {
                    updated = updated.copy(
                        examples = sentences.map { LangPair(it, "") }
                    ).derive()
                }
            }

            runCatching { repository.update(updated) }
            if (updated.gapCount() < word.gapCount()) filled++
            // مهلة بين الطلبات: المصادر المجانية تخنق المتسرّع
            delay(1_200)
        }
        filled
    }

    /** كم بطاقة ما زالت ناقصة — يخدم العرض والاختبار معاً */
    suspend fun pendingCount(): Int =
        withContext(Dispatchers.IO) { repository.allWords().count { it.isEligible() } }

    /**
     * يواصل الإثراء ما دام التطبيق حيّاً.
     *
     * جولة واحدة لكل فتحة تعني أن مكتبة فيها خمسون فراغاً تحتاج سبع فتحات
     * لتكتمل — وهذا يترك المستخدم يرى نقصاً لأسابيع. والاستمرار بمهلة بين
     * الجولات يُغلق المكتبة في جلسة واحدة بلا أن يشعر بشيء.
     *
     * ويتوقّف وحده حين لا يبقى ما يُملأ، فلا يدور على فراغ.
     */
    suspend fun runUntilComplete(roundGapMs: Long = 15_000) {
        while (true) {
            coroutineContext.ensureActive()
            if (pendingCount() == 0) return
            val filled = runPass()
            // لا شيء تغيّر ولا شيء بقي مؤهّلاً: انتهى ما يمكن فعله
            if (filled == 0 && pendingCount() == 0) return
            delay(roundGapMs)
        }
    }

    /**
     * البطاقة مرشّحة للإثراء ما دام فيها فراغ ولم نستنفد محاولاتها.
     *
     * الشرط لا يذكر «قديمة» ولا «جديدة» عمداً: كلمة تُضاف اليوم قد يعجز مصدرها
     * عن إعطاء مثال لها في تلك اللحظة، فتُولد ناقصة تماماً كبطاقات الأمس. ربط
     * الإثراء بإصدار المحرك كان يستثنيها للأبد لأنها «حديثة» — وهذا يعيد إنتاج
     * النقص بدل أن يمنعه.
     *
     * والحدّ على المحاولات ضروري: كلمة نادرة لا مثال لها في أي مصدر ستبقى
     * ناقصة مهما سألنا، وسؤالها في كل فتحة يستهلك حصة مصدر مجاني بلا فائدة.
     */
    private fun Word.isEligible(): Boolean =
        gapCount() > 0 && attemptsSoFar() < MAX_ATTEMPTS

    private fun Word.gapCount(): Int {
        var gaps = 0
        if (examples.isEmpty()) gaps++
        if (audioUS.isBlank() && audioUK.isBlank()) gaps++
        if (cefr.isBlank() && estCefr.isBlank()) gaps++
        if (synonyms.isEmpty()) gaps++
        if (meanings.isEmpty()) gaps++
        return gaps
    }

    /**
     * عدّاد المحاولات مخبّأ في حقل إصدار المحرك.
     *
     * إضافة عمود للعدّاد تعني ترحيلاً لقاعدة البيانات على جهاز المستخدم مقابل
     * رقم صغير. والحقل القائم يحمل المعنى نفسه: ما زاد عن الإصدار الحالي هو
     * عدد المرات التي حاولنا فيها ولم نكمل.
     */
    private fun Word.attemptsSoFar(): Int = (engineVersion - ENGINE_VERSION).coerceAtLeast(0)

    private fun Word.withAttemptRecorded(): Word =
        copy(engineVersion = maxOf(engineVersion, ENGINE_VERSION) + 1)

    /**
     * يدمج الجديد في الفراغات وحدها.
     *
     * لا يُستبدل شيء موجود: المستخدم قد يكون حرّر معنى بيده، وتقدّمه في المراجعة
     * وتصنيفه وتاريخه ملكه لا ملك المصدر. الإثراء يضيف ولا يمحو.
     */
    private fun Word.mergeMissingFrom(fresh: Word): Word {
        var changed = false
        fun <T> pick(mine: T, theirs: T, isEmpty: (T) -> Boolean): T =
            if (isEmpty(mine) && !isEmpty(theirs)) { changed = true; theirs } else mine

        val merged = copy(
            examples = pick(examples, fresh.examples) { it.isEmpty() },
            synonyms = pick(synonyms, fresh.synonyms) { it.isEmpty() },
            collocations = pick(collocations, fresh.collocations) { it.isEmpty() },
            derivatives = pick(derivatives, fresh.derivatives) { it.isEmpty() },
            inflections = pick(inflections, fresh.inflections) { it.isEmpty() },
            audioUS = pick(audioUS, fresh.audioUS) { it.isBlank() },
            audioUK = pick(audioUK, fresh.audioUK) { it.isBlank() },
            ipa = pick(ipa, fresh.ipa) { it.isBlank() },
            ipaUS = pick(ipaUS, fresh.ipaUS) { it.isBlank() },
            ipaUK = pick(ipaUK, fresh.ipaUK) { it.isBlank() },
            arabicPron = pick(arabicPron, fresh.arabicPron) { it.isBlank() },
            cefr = pick(cefr, fresh.cefr) { it.isBlank() },
            estCefr = pick(estCefr, fresh.estCefr) { it.isBlank() },
            oxford = pick(oxford, fresh.oxford) { it.isBlank() },
            freqLabel = pick(freqLabel, fresh.freqLabel) { it.isBlank() },
            pos = pick(pos, fresh.pos) { it.isEmpty() },
            // المعاني تُملأ فقط إن كانت فارغة تماماً — لا تُوسَّع ولا تُبدَّل
            meanings = pick(meanings, fresh.meanings) { it.isEmpty() },
            // المحاولة تُسجَّل دائماً، نجحت أو لم تنجح — وإلا دارت الجولة بلا نهاية
            engineVersion = maxOf(engineVersion, ENGINE_VERSION) + 1
        )
        return if (changed) merged.derive() else merged
    }

    private companion object {
        /** ثلاث محاولات لكل بطاقة، ثم نقبل أن المصدر لا يملك ما ينقصها */
        const val MAX_ATTEMPTS = 3
    }
}
