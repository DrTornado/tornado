package com.tornado.vocab.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * مدخل من قائمة أكسفورد المرجعية — المستوى وحده.
 *
 * كان يبني أيضاً روابط نطق تشير إلى خوادم أكسفورد مباشرة، وتلك تسجيلات
 * مملوكة لا رخصة لنا فيها: أوضح مخاطرة قانونية كانت في التطبيق. حُذفت،
 * ومحلّها كوكورو الذي صار المحرك الأساسي.
 *
 * والمستوى نفسه يبقى: أن كلمة «abide» من مستوى B2 واقعةٌ لغوية منشورة لا
 * نصٌّ مؤلَّف يُملَك، وهي أنفع ما في القائمة للمتعلّم.
 */
data class OxfordEntry(val level: String) {
    /** أكسفورد ٣٠٠٠ تغطي حتى B2؛ ما فوقها ينتمي لقائمة ٥٠٠٠ */
    val listName: String get() = if (level == "C1") "5000" else "3000"
}

data class FreqEntry(val rank: Int, val label: String, val estCefr: String)

/**
 * قائمتا المرجع المدمجتان مع التطبيق: قائمة أكسفورد الرسمية (~٥٠٠٠ كلمة) وقائمة
 * التردد الموسّعة (١٨٬٠٠٠ كلمة). كلتاهما تعملان بلا إنترنت.
 *
 * التحميل كسول ويحدث مرة واحدة فقط: قراءة ٥٨٥ كيلوبايت نصاً عند أول استخدام فعلي،
 * لا عند إقلاع التطبيق — فزمن الإقلاع لا يتأثر إطلاقاً.
 */
object ReferenceData {

    private val mutex = Mutex()
    @Volatile private var oxford: Map<String, OxfordEntry>? = null
    @Volatile private var freq: Map<String, Int>? = null

    suspend fun oxfordMap(context: Context): Map<String, OxfordEntry> {
        oxford?.let { return it }
        return mutex.withLock {
            oxford ?: loadOxford(context).also { oxford = it }
        }
    }

    suspend fun freqMap(context: Context): Map<String, Int> {
        freq?.let { return it }
        return mutex.withLock {
            freq ?: loadFreq(context).also { freq = it }
        }
    }

    private suspend fun loadOxford(context: Context): Map<String, OxfordEntry> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, OxfordEntry>(5200)
            runCatching {
                context.assets.open("oxford.txt").bufferedReader().forEachLine { line ->
                    val p = line.split('|')
                    // الملف يحمل أربعة حقول تاريخياً؛ نقرأ المستوى ونتجاهل مسارَي الصوت
                    if (p.size >= 2 && p[0].isNotBlank()) {
                        out[p[0]] = OxfordEntry(p[1])
                    }
                }
            }
            out
        }

    private suspend fun loadFreq(context: Context): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, Int>(19000)
            runCatching {
                context.assets.open("freq.txt").bufferedReader().forEachLine { line ->
                    val p = line.split('|')
                    if (p.size == 2 && p[0].isNotBlank()) {
                        p[1].trim().toIntOrNull()?.let { out[p[0]] = it }
                    }
                }
            }
            out
        }

    suspend fun lookupOxford(context: Context, word: String): OxfordEntry? {
        val m = oxfordMap(context)
        return wordFormTries(word.lowercase().trim()).firstNotNullOfOrNull { m[it] }
    }

    suspend fun lookupFreq(context: Context, word: String): FreqEntry? {
        val m = freqMap(context)
        val rank = wordFormTries(word.lowercase().trim()).firstNotNullOfOrNull { m[it] } ?: return null
        return FreqEntry(rank, freqTierLabel(rank), estCefrFromRank(rank))
    }

    /** مرشحات الجذر: تسمح بإيجاد "run" حين يبحث المستخدم عن "running" */
    fun wordFormTries(w: String): List<String> {
        if (w.isBlank()) return emptyList()
        val tries = mutableListOf(w)
        if (w.endsWith("ies")) tries += w.dropLast(3) + "y"
        if (w.endsWith("es")) tries += w.dropLast(2)
        if (w.endsWith("s")) tries += w.dropLast(1)
        if (w.endsWith("ing")) { tries += w.dropLast(3); tries += w.dropLast(3) + "e" }
        if (w.endsWith("ed")) { tries += w.dropLast(2); tries += w.dropLast(1) }
        return tries.filter { it.isNotBlank() }.distinct()
    }

    fun freqTierLabel(rank: Int): String = when {
        rank <= 1000 -> "Top 1,000 words"
        rank <= 3000 -> "Top 3,000 words"
        rank <= 8000 -> "Top 8,000 words"
        else -> "Top 18,000 words"
    }

    /**
     * تقدير تقريبي مبني على تردد الاستخدام وحده — ليس تقييماً رسمياً من أكسفورد أو كامبردج.
     * تعرضه الواجهة دائماً بحدّ منقّط وكلمة "تقديري" حتى لا يُخلط بالتصنيف المعتمد.
     */
    fun estCefrFromRank(rank: Int): String = when {
        rank <= 1000 -> "A1"
        rank <= 2500 -> "A2"
        rank <= 5000 -> "B1"
        rank <= 10000 -> "B2"
        else -> "C1"
    }
}
