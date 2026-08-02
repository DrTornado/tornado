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

sealed interface SyncResult {
    data class Success(val pulled: Int, val pushed: Int, val deleted: Int) : SyncResult
    data object NotConfigured : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * مزامنة المكتبة عبر ملف واحد على GitHub.
 *
 * تطبيق الويب — الذي يعمل على الكمبيوتر — يحفظ مكتبته في `tornado-words.json`
 * داخل مستودع المستخدم منذ البداية. فبدل بناء قناة جديدة بين الجهازين نستعمل
 * القناة القائمة نفسها: الكمبيوتر للإضافة والإدارة، والجوال للاستماع بالخلفية،
 * والملف بينهما.
 *
 * وهذا يعمل على بيانات الجوال بلا شبكة محلية ولا كيبل ولا حساب جديد — وهي
 * القيود الحقيقية التي أسقطت كل بديل آخر.
 *
 * الدمج بالأحدث لا بالأكبر: كل بطاقة لها زمن إنشاء، وكل حذف يترك شاهدة بزمنه،
 * فلا يعيد جهازٌ إحياء ما حذفه الآخر.
 */
class GitHubSync(
    private val repository: WordRepository,
    private val keys: SecureKeyStore
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile var repo: String = DEFAULT_REPO

    private fun token(): String = keys.keyFor(PROVIDER)

    /**
     * السحب لا يحتاج رمزاً على مستودع عام.
     *
     * اشتراط الرمز للاتجاهين كان سيؤخّر الفائدة كلها خلف خطوة إعداد لا لزوم
     * لها: الكلمات التي يضيفها المستخدم على حاسوبه تصل إلى جواله فوراً وبلا
     * تهيئة. الرمز يبقى مطلوباً للرفع وحده لأن الكتابة تحتاج صلاحية.
     */
    val canPull: Boolean get() = repo.contains('/')
    val canPush: Boolean get() = canPull && token().length > 15
    val isConfigured: Boolean get() = canPull

    private fun request(builder: Request.Builder): Request {
        val t = token()
        if (t.length > 15) builder.header("Authorization", "Bearer $t")
        return builder.header("Accept", "application/vnd.github+json").build()
    }

    private fun contentsUrl() = "https://api.github.com/repos/$repo/contents/$PATH"

    /** يتحقق من الوصول قبل أي مزامنة — رسالة واضحة خير من فشل غامض */
    suspend fun check(): SyncResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext SyncResult.NotConfigured
        runCatching {
            client.newCall(request(Request.Builder().url(contentsUrl()).get())).execute()
                .use { r ->
                    when {
                        r.isSuccessful -> SyncResult.Success(0, 0, 0)
                        r.code == 404 -> SyncResult.Failed("File not found in $repo")
                        r.code == 401 -> SyncResult.Failed("Token rejected — needs Contents access")
                        r.code == 403 -> SyncResult.Failed("Access denied to $repo")
                        else -> SyncResult.Failed("GitHub error ${r.code}")
                    }
                }
        }.getOrElse { SyncResult.Failed(it.message?.take(90) ?: "No connection") }
    }

    /**
     * يسحب الملف ويدمجه، ثم يرفع النتيجة إن تغيّرت المكتبة المحلية.
     * السحب يسبق الرفع دائماً حتى لا يمحو الجوالُ عملاً جرى على الكمبيوتر.
     */
    suspend fun sync(push: Boolean = true): SyncResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext SyncResult.NotConfigured
        runCatching {
            val remote = fetch() ?: return@runCatching SyncResult.Failed("Could not read $PATH")
            val merged = merge(remote.json, remote.text)
            if (push && merged.localChanged && canPush) {
                val body = payload()
                val put = JSONObject().apply {
                    put("message", "Tornado sync from phone")
                    put("content", Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP))
                    remote.sha?.let { put("sha", it) }
                }.toString()
                val ok = client.newCall(
                    request(
                        Request.Builder().url(contentsUrl())
                            .put(put.toRequestBody("application/json".toMediaType()))
                    )
                ).execute().use { it.isSuccessful }
                if (!ok) return@runCatching SyncResult.Failed("Upload rejected by GitHub")
            }
            SyncResult.Success(merged.added, merged.uploaded, merged.removed)
        }.getOrElse { SyncResult.Failed(it.message?.take(90) ?: "No connection") }
    }

    private class Remote(val json: JSONObject, val text: String, val sha: String?)

    private fun fetch(): Remote? {
        client.newCall(request(Request.Builder().url(contentsUrl()).get())).execute().use { r ->
            if (!r.isSuccessful) return null
            val meta = JSONObject(r.body?.string().orEmpty())
            val raw = meta.optString("content").replace("\n", "")
            if (raw.isBlank()) return null
            val decoded = String(Base64.decode(raw, Base64.DEFAULT))
            return Remote(
                JSONObject(decoded), decoded,
                meta.optString("sha").takeIf { it.isNotBlank() }
            )
        }
    }

    private class MergeReport(
        val added: Int,
        val removed: Int,
        val uploaded: Int,
        val localChanged: Boolean
    )

    private suspend fun merge(remote: JSONObject, rawText: String): MergeReport {
        val localWords = repository.allWords()
        val localByName = localWords.associateBy { it.word.lowercase() }
        val localTombstones = repository.tombstones().associateBy { it.word.lowercase() }

        // شواهد الحذف البعيدة تُطبَّق أولاً: كلمة حذفها الكمبيوتر لا تُستورَد
        val remoteDeleted = HashSet<String>()
        remote.optJSONArray("tombstones")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("word").lowercase()
                if (name.isBlank()) continue
                remoteDeleted += name
                localByName[name]?.let { repository.deleteById(it.id, it.word) }
            }
        }

        /*
         * التحليل يمرّ بنفس مسار الاستيراد الموجود.
         * كتابة محلّل ثانٍ للصيغة نفسها تعني نسختين تتباعدان مع الوقت، وحقلاً
         * يُضاف في مكان وينسى في الآخر.
         */
        var added = 0
        val remoteNames = HashSet<String>()
        repository.parseExport(rawText).forEach { w ->
            val name = w.word.trim().lowercase()
            if (name.isBlank()) return@forEach
            remoteNames += name
            if (name in remoteDeleted) return@forEach
            // حذفناها هنا عمداً — لا نعيدها لمجرّد أنها ما زالت في الملف البعيد
            if (name in localTombstones) return@forEach
            if (localByName.containsKey(name)) return@forEach
            repository.addWord(w)
            added++
        }

        val localOnly = localWords.count { it.word.lowercase() !in remoteNames }
        return MergeReport(
            added = added,
            removed = remoteDeleted.size,
            uploaded = localOnly,
            localChanged = localOnly > 0 || localTombstones.isNotEmpty()
        )
    }

    /**
     * الحمولة تُبنى بمُصدِّر التطبيق نفسه ثم تُزاد بالشواهد.
     * صيغة واحدة على الطرفين تعني أن ما يكتبه الجوال يقرأه الكمبيوتر بلا ترجمة.
     */
    private suspend fun payload(): String {
        val exported = JSONObject(repository.exportJson())
        val stones = JSONArray()
        repository.tombstones().forEach {
            stones.put(
                JSONObject().apply {
                    put("id", it.id); put("word", it.word); put("deletedAt", it.deletedAt)
                }
            )
        }
        exported.put("tombstones", stones)
        return exported.toString()
    }

    companion object {
        const val PROVIDER = "github"
        /*
         * لا مستودع افتراضي.
         *
         * كان هنا مستودع المطوّر، والسحب لا يحتاج رمزاً — فكان كل من ينزّل
         * التطبيق يجذب مكتبة المطوّر الشخصية وملاحظاته إلى جهازه في أول
         * فتحة، ويجدها كأنها محتوى التطبيق. مكتبةٌ خاصة تتحوّل إلى محتوى
         * عام لكل غريب، بلا أن يطلب أحدهما ذلك.
         *
         * المزامنة الآن اختيار صريح: من يريدها يكتب مستودعه هو.
         */
        const val DEFAULT_REPO = ""
        private const val PATH = "tornado-words.json"
    }
}
