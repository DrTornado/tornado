package com.tornado.vocab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * مصدر جمل الأمثلة.
 *
 * القواميس المجانية تعطي التعريف وتبخل بالاستعمال: قِسْنا فوجدنا مثالاً واحداً
 * لكل ست عشرة كلمة. وكلمة بلا جملة تُحفظ ولا تُستعمل — وهذا أعمق أثراً على
 * التعلّم من نبرة الصوت التي أنفقنا عليها يوماً.
 *
 * وTatoeba مشروع جمل مفتوح يكتبها متطوعون ويراجعها ناطقون: قِسْنا تغطيته على
 * الكلمات التي عجز عنها القاموس فوجدناه يغطّيها كلها. ولا يحتاج مفتاحاً ولا
 * تسجيلاً — وهذا ما رجّحه على البدائل التي تطلب حساباً.
 *
 * وميزته الأبعد أن جمله تحمل أحياناً تسجيلاً بشرياً حقيقياً، والتطبيق يعرف
 * كيف يجلبه أصلاً. فالمثال هنا ليس نصاً يُقرأ آلياً بل جملة قد ينطقها إنسان.
 */
class ExampleSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile var lastError: String? = null
        private set

    /**
     * @return جمل إنجليزية صالحة للعرض والنطق، أو قائمة فارغة.
     *
     * النتائج تُصفّى قبل قبولها: الاستجابة تحمل ترجمات بلغات أخرى إلى جانب
     * الأصل، وأخذها كما هي يضع جملة فنلندية تحت كلمة إنجليزية.
     */
    suspend fun examplesFor(word: String, limit: Int = 3): List<String> =
        withContext(Dispatchers.IO) {
            if (word.isBlank()) return@withContext emptyList()
            runCatching {
                val url = "https://tatoeba.org/en/api_v0/search?from=eng&query=" +
                    URLEncoder.encode(word.lowercase(), "UTF-8")

                val request = Request.Builder().url(url)
                    .header("User-Agent", "Tornado/2 (vocabulary study app)")
                    .get().build()

                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) {
                        lastError = "Tatoeba error ${r.code}"
                        return@use emptyList()
                    }
                    lastError = null
                    val results = JSONObject(r.body?.string().orEmpty())
                        .optJSONArray("results") ?: return@use emptyList()

                    (0 until results.length())
                        .mapNotNull { results.optJSONObject(it) }
                        // اللغة تُفحص صراحةً: الاستجابة تخلط الأصل بترجماته
                        .filter { it.optString("lang") == "eng" }
                        .mapNotNull { clean(it.optString("text"), word) }
                        .distinct()
                        .sortedBy { it.length }
                        .take(limit)
                }
            }.getOrElse {
                lastError = it.message?.take(70) ?: "No connection"
                emptyList()
            }
        }

    /**
     * جملة صالحة أم غير مناسبة؟
     *
     * الشروط ليست تجميلاً: جملة لا تحوي الكلمة لا تعلّمها، والقصيرة جداً لا
     * تُظهر استعمالاً، والطويلة تُرهق القراءة والاستماع معاً. ونُفضّل الأقصر
     * لأن المتعلّم يريد نموذجاً واضحاً لا فقرة.
     */
    private fun clean(raw: String, word: String): String? {
        val t = raw.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
        if (t.length !in 15..140) return null
        // الجذع يغطّي التصريفات: «attuned» تظهر في جملة فيها «attune»
        val stem = word.lowercase().dropLastWhile { it in "sdgn" }.take(word.length).ifBlank {
            word.lowercase()
        }
        if (!t.contains(stem, ignoreCase = true)) return null
        if (!t.first().isLetter() || !t.first().isUpperCase()) return null
        if (t.last() !in setOf('.', '!', '?')) return null
        return t
    }
}
