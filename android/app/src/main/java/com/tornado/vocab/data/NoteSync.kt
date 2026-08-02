package com.tornado.vocab.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * مزامنة الملاحظات الصوتية مع الكمبيوتر.
 *
 * نفس المستودع ونفس الرمز ونفس الآلية التي تنقل الكلمات — الفرق ملفّ واحد.
 * وهذا مقصود: المستخدم أعدّ المزامنة مرة، فليس من حقّنا أن نطلب منه إعدادها
 * ثانيةً لأننا أضفنا نوع محتوى جديد.
 *
 * والملف نصّي مقروء بالعين: يستطيع فتحه على حاسوبه ولصق نصّ فيه مباشرة بلا
 * أداة ولا تطبيق، فيصل إلى جواله في المزامنة التالية.
 */
class NoteSync(
    private val repository: NoteRepository,
    private val keys: SecureKeyStore
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile var repo: String = GitHubSync.DEFAULT_REPO

    private fun token(): String = keys.keyFor(GitHubSync.PROVIDER)
    val canPull: Boolean get() = repo.contains('/')
    val canPush: Boolean get() = canPull && token().length > 15

    private fun request(builder: Request.Builder): Request {
        val t = token()
        if (t.length > 15) builder.header("Authorization", "Bearer $t")
        return builder.header("Accept", "application/vnd.github+json").build()
    }

    private fun url() = "https://api.github.com/repos/$repo/contents/$PATH"

    suspend fun sync(push: Boolean = true): SyncResult = withContext(Dispatchers.IO) {
        if (!canPull) return@withContext SyncResult.NotConfigured
        runCatching {
            val remote = fetch()
            var pulled = 0

            remote?.notes?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val note = parse(o) ?: continue
                    if (repository.mergeRemote(note)) pulled++
                }
            }

            var pushed = 0
            if (push && canPush) {
                /*
                 * السعة بلا حدّ محلياً، وبحدّ واقعي في المزامنة.
                 *
                 * قاعدة البيانات تتّسع لنصوص بالميغابايتات — كتاب كامل يمرّ.
                 * لكن واجهة GitHub ترفض الملف فوق نحو ميغابايت، وملاحظة واحدة
                 * ضخمة كانت ستكسر مزامنة كل الملاحظات معها.
                 *
                 * فالضخمة تُستثنى من الرفع وتبقى تعمل محلياً كاملة — تشغيلاً
                 * وتقسيماً واستئنافاً — بدل أن يُرفض حفظها أو تُعطّل غيرها.
                 */
                val local = repository.all().filter { it.text.length <= MAX_SYNC_CHARS }
                val body = payload(local)
                val put = JSONObject().apply {
                    put("message", "Tornado notes from phone")
                    put("content", Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP))
                    remote?.sha?.let { put("sha", it) }
                }.toString()
                val ok = client.newCall(
                    request(
                        Request.Builder().url(url())
                            .put(put.toRequestBody("application/json".toMediaType()))
                    )
                ).execute().use { it.isSuccessful }
                if (ok) pushed = local.size
            }
            SyncResult.Success(pulled, pushed, 0)
        }.getOrElse { SyncResult.Failed(it.message?.take(80) ?: "No connection") }
    }

    private class Remote(val notes: JSONArray?, val sha: String?)

    private fun fetch(): Remote? {
        client.newCall(request(Request.Builder().url(url()).get())).execute().use { r ->
            // الملف غير موجود بعد: ليس خطأً بل مكتبة ملاحظات لم تبدأ
            if (r.code == 404) return Remote(null, null)
            if (!r.isSuccessful) return null
            val meta = JSONObject(r.body?.string().orEmpty())
            val raw = meta.optString("content").replace("\n", "")
            if (raw.isBlank()) return Remote(null, meta.optString("sha").takeIf { it.isNotBlank() })
            val decoded = String(Base64.decode(raw, Base64.DEFAULT))
            val root = runCatching { JSONObject(decoded) }.getOrNull()
            return Remote(
                root?.optJSONArray("notes"),
                meta.optString("sha").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun parse(o: JSONObject): Note? {
        val text = o.optString("text").trim()
        if (text.length < 20) return null
        val id = o.optLong("id").takeIf { it > 0 } ?: System.currentTimeMillis()
        return Note(
            id = id,
            title = o.optString("title").ifBlank { NoteChunker.titleFrom(text) },
            text = text,
            createdAt = o.optLong("createdAt", id),
            updatedAt = o.optLong("updatedAt", id),
            lastChunk = o.optInt("lastChunk", 0),
            favorite = o.optBoolean("favorite", false)
        )
    }

    private fun payload(notes: List<Note>): String {
        val arr = JSONArray()
        notes.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("text", n.text)
                    put("createdAt", n.createdAt)
                    put("updatedAt", n.updatedAt)
                    put("lastChunk", n.lastChunk)
                    put("favorite", n.favorite)
                }
            )
        }
        return JSONObject().apply {
            put("app", "tornado")
            put("kind", "notes")
            put("version", 1)
            put("exportedAt", java.time.Instant.now().toString())
            put("notes", arr)
        }.toString(1)
    }

    private companion object {
        const val PATH = "tornado-notes.json"
        /** ~٦٠٠ ألف حرف للملاحظة — يترك هامشاً تحت حدّ GitHub بعد ترميز base64 */
        const val MAX_SYNC_CHARS = 600_000
    }
}
