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
import java.io.File
import java.util.concurrent.TimeUnit

data class AudioSyncReport(
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val error: String? = null
)

/**
 * مشاركة الصوت المولَّد بين الأجهزة عبر المستودع.
 *
 * توليد بطاقة واحدة يكلّف ثوانيَ من المعالجة وربما طلباً مدفوعاً لخدمة سحابية.
 * وكان هذا العمل يُهدر بالكامل مرتين: عند فتح التطبيق على جهاز آخر، وعند إعادة
 * تثبيته على الجهاز نفسه — يبدأ من الصفر كأن شيئاً لم يكن.
 *
 * فالبطاقة تُرفع بعد بنائها مباشرة، وتُنزَّل جاهزة حيثما احتيج إليها. النتيجة
 * أن الكلمة تُولَّد **مرة واحدة في عمرها** مهما تعدّدت الأجهزة أو تكرّر التثبيت.
 *
 * وهذا لم يكن ممكناً قبل الترميز: ثلاثة ميغابايت للبطاقة تعني ثلاثة جيجابايت
 * لمكتبة كاملة — حجم لا يُرفع ولا يُنزَّل. ومئة وثمانون كيلوبايت تجعله عادياً.
 */
class AudioLibrarySync(
    private val keys: SecureKeyStore,
    private val cardDir: File
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile var repo: String = GitHubSync.DEFAULT_REPO

    private fun token(): String = keys.keyFor(GitHubSync.PROVIDER)
    val canPush: Boolean get() = repo.contains('/') && token().length > 15

    private fun request(builder: Request.Builder): Request {
        val t = token()
        if (t.length > 15) builder.header("Authorization", "Bearer $t")
        return builder.header("Accept", "application/vnd.github+json").build()
    }

    private fun dirUrl() = "https://api.github.com/repos/$repo/contents/$AUDIO_DIR"
    private fun fileUrl(name: String) = "${dirUrl()}/$name"

    /**
     * يزامن الصوت في الاتجاهين.
     *
     * التنزيل أولاً: بطاقة موجودة بعيداً لا يجوز أن يعاد بناؤها محلياً. ثم
     * الرفع لما بُني هنا ولا وجود له هناك.
     *
     * @param budget حدّ أعلى لعدد الملفات في الجولة الواحدة — المزامنة تجري
     *   في الخلفية أثناء الاستماع، وإغراق الشبكة يضرّ بما يسمعه المستخدم الآن.
     */
    suspend fun sync(budget: Int = 25): AudioSyncReport = withContext(Dispatchers.IO) {
        if (!repo.contains('/')) return@withContext AudioSyncReport(error = "No repository")
        runCatching {
            val remote = listRemote() ?: return@runCatching AudioSyncReport(
                error = "Could not read the audio folder"
            )
            /*
             * الملف المرافق يسافر مع الصوت.
             *
             * فيه مصدر الصوت ومدته، والمدة لا تُستخرج من الملف المرمَّز بحساب
             * بسيط. بطاقة تصل بلا مرافقها تُشغَّل بمدة صفر فيبدو شريط التقدّم
             * معطّلاً — فهما وحدة واحدة لا ملفان.
             */
            val localNames = cardDir.listFiles()
                ?.filter { it.name.endsWith(".m4a") || it.name.endsWith(".meta") }
                ?.associateBy { it.name }
                .orEmpty()

            var downloaded = 0
            for ((name, sha) in remote) {
                if (downloaded >= budget) break
                if (localNames.containsKey(name)) continue
                if (downloadOne(name, sha)) downloaded++
            }

            var uploaded = 0
            if (canPush) {
                for ((name, file) in localNames) {
                    if (uploaded >= budget) break
                    if (remote.containsKey(name)) continue
                    if (uploadOne(name, file)) uploaded++
                }
            }
            AudioSyncReport(downloaded, uploaded, localNames.size - uploaded)
        }.getOrElse { AudioSyncReport(error = it.message?.take(80) ?: "No connection") }
    }

    /** أسماء الملفات البعيدة مع بصماتها — البصمة مطلوبة للاستبدال لا للتنزيل */
    private fun listRemote(): Map<String, String>? {
        val req = request(Request.Builder().url(dirUrl()).get())
        client.newCall(req).execute().use { r ->
            // مجلد غير موجود بعد: ليس خطأً بل مكتبة صوتية لم تبدأ
            if (r.code == 404) return emptyMap()
            if (!r.isSuccessful) return null
            val arr = JSONArray(r.body?.string().orEmpty())
            val out = HashMap<String, String>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.endsWith(".m4a") || name.endsWith(".meta")) {
                    out[name] = o.optString("sha")
                }
            }
            return out
        }
    }

    private fun downloadOne(name: String, sha: String): Boolean = runCatching {
        val req = request(
            Request.Builder()
                .url(fileUrl(name))
                .header("Accept", "application/vnd.github.raw")
                .get()
        )
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body ?: return false
            // نكتب في ملف مؤقت ثم نعيد التسمية: تنزيل مقطوع لا يترك بطاقة نصفها
            val tmp = File(cardDir, "$name.part")
            tmp.outputStream().buffered().use { out -> body.byteStream().copyTo(out) }
            if (tmp.length() < 128) { tmp.delete(); return false }
            val target = File(cardDir, name)
            target.delete()
            tmp.renameTo(target)
        }
    }.getOrDefault(false)

    private fun uploadOne(name: String, file: File): Boolean = runCatching {
        if (file.length() > MAX_UPLOAD_BYTES) return false
        val payload = JSONObject().apply {
            put("message", "Tornado audio: $name")
            put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }.toString()
        val req = request(
            Request.Builder()
                .url(fileUrl(name))
                .put(payload.toRequestBody("application/json".toMediaType()))
        )
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private companion object {
        const val AUDIO_DIR = "tornado-audio"
        /** حدّ رفع الملف الواحد — بطاقة أكبر من هذا علامة على خلل لا على محتوى */
        const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024
    }
}
