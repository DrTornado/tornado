package com.tornado.vocab.data

import android.content.Context
import com.tornado.vocab.tornado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * يجلب بطاقات الإثراء من المستودع — سحبٌ فقط، بلا رفع.
 *
 * البطاقات تُبنى على خوادم GitHub لا هنا: جهاز صاحب المشروع ضعيف، وجواله
 * أضعف، ونموذج الترجمة وحده يريد خمس غيغابايتات. فما يصل الجوالَ نتيجةٌ
 * جاهزة، وكلفته تنزيل بضعة كيلوبايتات.
 *
 * ولا يرفع شيئاً: الإثراء يأتي من المستودع ولا يعود إليه، فلا تعارض ولا
 * سباق مع مزامنة الكلمات والملاحظات على نفس الملفات.
 *
 * والفهرس يحمل بصمة كل شريحة، فلا يُنزَّل إلا ما تغيّر — وهذا ما يجعل
 * «يجري مع كل مزامنة» رخيصاً بحقّ بدل أن يكون عبئاً على الباقة.
 */
class EnrichSync(
    private val context: Context,
    private val keys: SecureKeyStore
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile var repo: String = GitHubSync.DEFAULT_REPO

    private fun token(): String = keys.keyFor(GitHubSync.PROVIDER)
    val canPull: Boolean get() = repo.contains('/') && token().length > 15

    private val dao: EnrichDao get() = AppDatabase.get(context).enrichDao()

    /**
     * `vnd.github.raw` يعيد الملف نفسه لا وصفه.
     *
     * الصيغة الأخرى تغلّف المحتوى في JSON وترمّزه base64، فيصير التنزيل
     * أكبر بالثلث ويحتاج فكّ ترميزٍ بلا فائدة — والشرائح ملفات JSON أصلاً.
     */
    private fun fetch(path: String): String? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/contents/$path")
            .header("Authorization", "Bearer ${token()}")
            .header("Accept", "application/vnd.github.raw")
            .get().build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null       // لم تُبنَ الشرائح بعد
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string()
        }
    }

    /** @return عدد الشرائح التي وصلت جديدةً في هذه الجولة */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        if (!canPull) return@withContext 0
        runCatching {
            val indexText = fetch("$DIR/index.json") ?: return@runCatching 0
            val shards = JSONObject(indexText).optJSONObject("shards")
                ?: return@runCatching 0

            val have = dao.shards().associate { it.key to it.hash }
            var fresh = 0

            val keys = shards.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val want = shards.optJSONObject(key)?.optString("hash").orEmpty()
                if (want.isBlank() || have[key] == want) continue

                /*
                 * شريحةٌ تتعثّر لا تُسقط البقيّة.
                 *
                 * أربعٌ وتسعون شريحة، وانقطاعٌ في واحدة كان سيُلغي الجولة كلها
                 * لو أحطناها بمحاولةٍ واحدة. وبصمتُها تبقى قديمةً فتُعاد في
                 * المزامنة التالية — فالتعثّر تأخيرٌ لا خسارة.
                 */
                runCatching {
                    val body = fetch("$DIR/$key.json") ?: return@runCatching
                    val cards = JSONObject(body)
                    val rows = ArrayList<EnrichCard>()
                    val words = cards.keys()
                    while (words.hasNext()) {
                        val w = words.next()
                        cards.optJSONObject(w)?.let {
                            rows.add(
                                EnrichCard(
                                    word = w.lowercase(),
                                    json = it.toString(),
                                    curated = it.optBoolean("curated", false)
                                )
                            )
                        }
                    }
                    if (rows.isNotEmpty()) {
                        dao.putCards(rows)
                        // البصمة بعد البطاقات: لو انقطع بينهما أُعيد التنزيل لا فُقد
                        dao.putShard(EnrichShard(key, want))
                        fresh++
                    }
                }
            }
            fresh
        }.getOrDefault(0)   // انقطاعٌ أو مستودعٌ بلا إثراء — صمتٌ وإعادةٌ لاحقاً
    }

    /** بطاقة كلمة، أو `null` إن لم يصلها إثراء — فتُعرض كما كانت */
    suspend fun forWord(word: String): Enrichment? = withContext(Dispatchers.IO) {
        Enrichment.parse(dao.cardJson(word.trim().lowercase()))
    }

    /** الكلمات التي وصلتها بطاقةٌ مكتوبة بيد — ما عداها ينتظر في الطابور */
    fun curatedWords(): Flow<List<String>> = dao.curatedWords()

    private companion object {
        const val DIR = "enrich"
    }
}

val Context.enrichSync: EnrichSync get() = tornado.enrichSync
