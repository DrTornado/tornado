package com.tornado.vocab.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * تسجيلات بشرية حقيقية لنطق الكلمات.
 *
 * المصدر: ويكيميديا كومنز — مشروع Lingua Libre وغيره، وفيه أكثر من مئة ألف
 * تسجيل إنجليزي بأصوات ناطقين حقيقيين، بتراخيص حرة (CC BY-SA / CC0).
 *
 * لماذا هذا المصدر لا أكسفورد: ملفات أكسفورد ملك لدار نشر جامعة أكسفورد،
 * وتخزينها على الجهاز أو توزيعها مع التطبيق انتهاك صريح لحقوقها. ويكيميديا
 * حرّة الترخيص فعلاً، فيمكن تنزيلها وتخزينها دون أي التباس قانوني.
 *
 * التسجيلات تُخزَّن محلياً بعد أول جلب، فالجلسات التالية تعمل دون اتصال.
 */
class HumanAudioRepository(private val context: Context) {

    private val cacheDir = File(context.filesDir, "human-audio").apply { mkdirs() }
    private val missDir = File(context.filesDir, "human-audio/.misses").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile var enabled: Boolean = true
    @Volatile var allowNetwork: Boolean = true

    private fun key(word: String) = word.trim().lowercase()

    private fun localFile(word: String) = File(cacheDir, hash(key(word)) + ".wav")

    /** شاهد "لا يوجد تسجيل" — يمنع استجواب الشبكة لكل كلمة في كل جلسة */
    private fun missMarker(word: String) = File(missDir, hash(key(word)) + ".miss")

    fun hasLocal(word: String): Boolean = localFile(word).length() > 512

    fun cachedCount(): Int = cacheDir.listFiles()?.count { it.isFile && it.name.endsWith(".wav") } ?: 0

    fun cachedBytes(): Long =
        cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        missDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * تسجيل بشري لجملة مثال، من Tatoeba.
     *
     * Tatoeba مشروع جمل مفتوح فيه تسجيلات بأصوات متطوعين حقيقيين بترخيص CC BY.
     * التغطية أقل بكثير من تغطية الكلمات المفردة — كثير من أمثلة القاموس
     * مأخوذة من مصادر أخرى ولا وجود لها في Tatoeba أصلاً.
     */
    suspend fun fetchSentence(sentence: String, target: File): Boolean {
        if (!enabled || sentence.isBlank()) return false
        val trimmed = sentence.trim()
        if (trimmed.length > 200) return false

        val local = localFile("s:$trimmed")
        if (local.length() > 512) return copyCanonical(local, target)
        if (!allowNetwork) return false
        if (missMarker("s:$trimmed").exists()) return false

        return withContext(Dispatchers.IO) {
            val audioId = runCatching { findTatoebaAudio(trimmed) }.getOrNull()
            if (audioId == null) {
                runCatching { missMarker("s:$trimmed").createNewFile() }
                return@withContext false
            }
            val raw = File(context.cacheDir, "tat-$audioId")
            val ok = runCatching {
                download("https://tatoeba.org/audio/download/$audioId", raw)
            }.getOrDefault(false)
            if (!ok) { runCatching { raw.delete() }; return@withContext false }

            val pcm = decodeToCanonical(raw)
            runCatching { raw.delete() }
            if (pcm == null || pcm.isEmpty()) {
                runCatching { missMarker("s:$trimmed").createNewFile() }
                return@withContext false
            }
            WavUtils.writeWav(local, listOf(pcm))
            copyCanonical(local, target)
        }
    }

    /** يبحث عن جملة مطابقة لها تسجيل صوتي */
    private fun findTatoebaAudio(sentence: String): String? {
        val url = "https://tatoeba.org/en/api_v0/search" +
            "?from=eng&query=" + enc("=$sentence") + "&has_audio=yes&limit=5"
        val body = httpGet(url) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val results = (root["results"] as? kotlinx.serialization.json.JsonArray) ?: return null
        val normalized = normalize(sentence)
        for (r in results) {
            val obj = r.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content ?: continue
            if (normalize(text) != normalized) continue
            val audios = (obj["audios"] as? kotlinx.serialization.json.JsonArray) ?: continue
            val id = audios.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * يضع تسجيل الكلمة في [target] بالصيغة الموحّدة.
     * يعيد false بهدوء إن لم يوجد تسجيل.
     */
    suspend fun fetchWord(word: String, target: File, british: Boolean = false): Boolean {
        if (!enabled || word.isBlank()) return false
        if (word.trim().contains(Regex("\\s"))) return false // عبارات مركبة: لا تسجيلات مفردة لها

        val variant = if (british) "uk:$word" else word
        val local = localFile(variant)
        if (local.length() > 512) return copyCanonical(local, target)
        if (!allowNetwork) return false
        if (missMarker(variant).exists()) return false

        return withContext(Dispatchers.IO) {
            val url = runCatching { findRecordingUrl(word, british) }.getOrNull()
            if (url == null) {
                runCatching { missMarker(variant).createNewFile() }
                return@withContext false
            }
            val raw = File(context.cacheDir, "human-${hash(key(variant))}")
            val ok = runCatching { download(url, raw) }.getOrDefault(false)
            if (!ok) { runCatching { raw.delete() }; return@withContext false }

            // ملفات ويكيميديا بصيغ ogg/flac/wav — نوحّدها كلها لصيغة السرد
            val pcm = decodeToCanonical(raw)
            runCatching { raw.delete() }
            if (pcm == null || pcm.isEmpty()) {
                runCatching { missMarker(variant).createNewFile() }
                return@withContext false
            }
            WavUtils.writeWav(local, listOf(pcm))
            copyCanonical(local, target)
        }
    }

    private fun copyCanonical(source: File, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        target.length() > 512
    }.getOrDefault(false)

    /**
     * يبحث عن تسجيل بشري إنجليزي للكلمة في ويكيميديا كومنز.
     *
     * الاستعلام السابق كان يرجع تسجيلات فرنسية وهولندية للكلمة نفسها، فيسمع
     * المستخدم نطقاً بلغة أخرى أو لا يسمع شيئاً. التصحيح على محورين:
     *
     *  ١ — تجربة الأسماء القياسية مباشرة قبل أي بحث. ويكاموس يسمّي تسجيلاته
     *      "En-us-word.ogg" و"En-uk-word.ogg"، وطلبها بالاسم إصابة مؤكدة
     *      بلا اعتماد على ترتيب نتائج البحث.
     *  ٢ — قصر البحث على الإنجليزية فعلياً: LL-Q1860 هو معرّف ويكي بيانات
     *      للغة الإنجليزية، وهو الفاصل الوحيد الموثوق بين تسجيل إنجليزي
     *      وتسجيل فرنسي يحمل الكلمة نفسها.
     */
    private fun findRecordingUrl(word: String, preferBritish: Boolean): String? {
        val w = key(word)

        // ١ — الأسماء القياسية: إصابة مباشرة بلا بحث
        val direct = buildList {
            if (preferBritish) {
                add("En-uk-$w.ogg"); add("En-gb-$w.ogg"); add("En-us-$w.ogg")
            } else {
                add("En-us-$w.ogg"); add("En-uk-$w.ogg"); add("En-gb-$w.ogg")
            }
            add("En-us-$w.wav"); add("En-$w.ogg")
        }
        directLookup(direct)?.let { return it }

        // ٢ — بحث مقصور على الإنجليزية
        val body = httpGet(
            "https://commons.wikimedia.org/w/api.php" +
                "?action=query&format=json&generator=search" +
                "&gsrnamespace=6&gsrlimit=25" +
                "&gsrsearch=" + enc("intitle:$w") +
                "&prop=imageinfo&iiprop=url|mime"
        ) ?: return null

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return null

        data class Candidate(val url: String, val score: Int)
        val candidates = pages.values.mapNotNull { page ->
            val obj = page.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val info = (obj["imageinfo"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val url = info["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (info["mime"]?.jsonPrimitive?.content.orEmpty().startsWith("audio").not()) {
                return@mapNotNull null
            }

            val name = title.removePrefix("File:").lowercase()
            val stem = name.substringBeforeLast('.')

            // الاسم يجب أن ينتهي بالكلمة نفسها — لا مجرد أن يحتويها
            if (stem != w && !stem.endsWith("-$w")) return@mapNotNull null

            // الفلتر الحاسم: إنجليزي فقط
            val isEnglish = name.contains("ll-q1860 (eng)") ||
                name.startsWith("en-us-") || name.startsWith("en-uk-") ||
                name.startsWith("en-gb-") || name.startsWith("en-")
            if (!isEnglish) return@mapNotNull null

            var score = 50
            if (preferBritish) {
                if (name.startsWith("en-uk-") || name.startsWith("en-gb-")) score += 40
                if (name.startsWith("en-us-")) score += 10
            } else {
                if (name.startsWith("en-us-")) score += 40
                if (name.startsWith("en-uk-") || name.startsWith("en-gb-")) score += 10
            }
            if (name.contains("ll-q1860 (eng)")) score += 20
            Candidate(url, score)
        }
        return candidates.maxByOrNull { it.score }?.url
    }

    /** يطلب عدة أسماء ملفات دفعة واحدة ويعيد أول موجود */
    private fun directLookup(titles: List<String>): String? {
        val joined = titles.joinToString("|") { "File:$it" }
        val body = httpGet(
            "https://commons.wikimedia.org/w/api.php" +
                "?action=query&format=json&titles=" + enc(joined) +
                "&prop=imageinfo&iiprop=url|mime"
        ) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val pages = (root["query"] as? JsonObject)?.get("pages") as? JsonObject ?: return null

        // نحترم ترتيب الأفضلية الذي أُرسل، لا ترتيب استجابة الخادم
        val found = pages.values.mapNotNull { page ->
            val obj = page.jsonObject
            if (obj["missing"] != null) return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.content?.removePrefix("File:") ?: return@mapNotNull null
            val info = (obj["imageinfo"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val url = info["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            title to url
        }.toMap()

        for (t in titles) {
            found.entries.firstOrNull { it.key.equals(t, ignoreCase = true) }?.let { return it.value }
        }
        return null
    }

    private fun httpGet(url: String): String? = runCatching {
        client.newCall(
            Request.Builder().url(url)
                // ويكيميديا تطلب معرّف عميل صريحاً وترفض الطلبات المجهولة
                .header("User-Agent", "TornadoVocab/2.1 (Android; educational vocabulary app)")
                .build()
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun download(url: String, target: File): Boolean = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "TornadoVocab/2.1 (Android; educational vocabulary app)")
                .build()
        ).execute().use { r ->
            if (!r.isSuccessful) return@runCatching false
            val body = r.body ?: return@runCatching false
            target.outputStream().buffered().use { out -> body.byteStream().copyTo(out) }
            target.length() > 512
        }
    }.getOrDefault(false)

    /**
     * يفكّ ترميز أي صيغة صوتية يدعمها النظام إلى عيّنات موحّدة.
     * MediaExtractor يغطي ogg/vorbis وflac وmp3 وwav محلياً بلا مكتبات إضافية.
     */
    private fun decodeToCanonical(file: File): ShortArray? =
        runCatching { MediaDecoder.decodeToPcm(file, WavUtils.SAMPLE_RATE) }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(28)
}
