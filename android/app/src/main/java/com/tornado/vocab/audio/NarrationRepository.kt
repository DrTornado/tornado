package com.tornado.vocab.audio

import android.content.Context
import com.tornado.vocab.data.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** إعدادات السرد التي تؤثر على الملف الناتج */
data class NarrationSpec(
    val repeat: Int,
    val mode: NarrationMode,
    val detail: NarrationDetail,
    val speakArabic: Boolean,
    val voiceTag: String
)

/** وحدة نطق واحدة وموضعها الزمني — تُظهر للمستخدم ما يُنطق الآن */
data class NarrationLine(val startMs: Long, val endMs: Long, val text: String)

data class NarrationResult(
    val file: File,
    val durationMs: Long,
    /** أعلى مصدر شارك في هذه البطاقة — يظهر كشارة للمستخدم */
    val source: VoiceSource,
    val humanSegments: Int,
    val totalSegments: Int,
    /** حدود الجمل داخل الملف — فارغة للكلمات، مملوءة للملاحظات */
    val lines: List<NarrationLine> = emptyList()
)

/**
 * يبني ويخزّن صوت كل بطاقة.
 *
 * طبقتا تخزين: المقاطع الثابتة تُخزَّن مرة وتُعاد، والبطاقة المدموجة تُخزَّن
 * كاملة فالتشغيل الثاني فوري. سقف حجم مع إزاحة الأقدم استخداماً يمنع امتلاء
 * الجهاز عند مكتبة كبيرة.
 */
class NarrationRepository(
    private val context: Context,
    private val voices: VoiceChain
) {

    private val cardDir = File(context.filesDir, "narration/cards")
    private val segDir = File(context.filesDir, "narration/segments")
    private val tmpDir = File(context.cacheDir, "narration-tmp")
    private val buildLock = Mutex()

    /**
     * بصمة الصوت أثناء بناء ملاحظة — تُتيح تخزين جملها منفردة.
     * الملاحظات لا تحمل NarrationSpec لأنها ليست بطاقات، فتحتاج المسار نفسه
     * إلى معرفة الصوت من مكان آخر ليصنع مفتاحاً صحيحاً.
     */
    @Volatile private var noteVoiceTag: String? = null

    @Volatile var maxCacheBytes: Long = 512L * 1024 * 1024

    init { cardDir.mkdirs(); segDir.mkdirs(); tmpDir.mkdirs() }

    fun cachedFile(word: Word, spec: NarrationSpec): File =
        File(cardDir, "${word.id}-${cardKey(word, spec)}.${AudioEncoder.EXTENSION}")

    fun isCached(word: Word, spec: NarrationSpec): Boolean {
        val f = cachedFile(word, spec)
        return f.exists() && f.length() > 128
    }

    suspend fun getOrBuild(
        word: Word,
        spec: NarrationSpec,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): NarrationResult? = withContext(Dispatchers.IO) {
        val target = cachedFile(word, spec)
        if (target.exists() && target.length() > 128) {
            target.setLastModified(System.currentTimeMillis())
            val meta = readMeta(target)
            return@withContext NarrationResult(
                target, storedDuration(target), meta.source, meta.human, meta.total
            )
        }
        buildLock.withLock {
            if (target.exists() && target.length() > 128) {
                val meta = readMeta(target)
                return@withLock NarrationResult(
                    target, storedDuration(target), meta.source, meta.human, meta.total
                )
            }
            build(word, spec, target, onProgress)
        }
    }

    /**
     * يبني صوتاً لنصّ حرّ لا لبطاقة.
     *
     * الملاحظات الصوتية نصّ يُقرأ من أوّله إلى آخره، بلا معانٍ ولا تصريفات ولا
     * ترتيب أقسام. فتمريرها عبر بنّاء البطاقات كان سيتطلّب اختلاق بطاقة وهمية
     * حولها — والوهم في البيانات يكلّف لاحقاً أكثر مما يوفّر الآن.
     *
     * وما دون ذلك مشترك بالكامل: نفس التقسيم إلى جُمل، ونفس المحرّك، ونفس
     * الترميز، ونفس التخزين والحدّ الأعلى له.
     */
    suspend fun getOrBuildText(
        key: String,
        text: String,
        voiceTag: String,
        /** كم مرة تُعاد كل وحدة — زرّ Say */
        repeat: Int = 1,
        /** true = الوحدة فقرة (FULL) · false = الوحدة جملة (SHORT) */
        byParagraph: Boolean = false,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): NarrationResult? = withContext(Dispatchers.IO) {
        val n = repeat.coerceAtLeast(1)
        /*
         * وحدة التشغيل صارت جملة، فوحدة التكرار لم يعد لها أثر.
         *
         * كان المفتاح يحمل «فقرة أم جملة»، وذلك صحيح حين كان العنصر مقطعاً من
         * ألف ومئتي حرف. أما الآن فالعنصر جملة واحدة، وتقسيمها بالفقرات أو
         * بالجمل يعطي الجملة نفسها — فيُبنى ملفٌّ مطابق باسمٍ آخر، ويرى
         * المستخدم «يحمّل» بعد ضغطة FULL بلا أن يتغيّر شيء في سمعه.
         *
         * والعدد يبقى في المفتاح لأنه يغيّر الناتج فعلاً، لكن تكلفته صارت
         * دمجاً لمقاطع مخزَّنة لا توليداً من جديد.
         */
        val tag = "v$pipelineVersion|$voiceTag|x$n"
        val target = File(cardDir, "$key-${hash(tag)}.${AudioEncoder.EXTENSION}")
        if (target.exists() && target.length() > 128) {
            target.setLastModified(System.currentTimeMillis())
            val meta = readMeta(target)
            return@withContext NarrationResult(
                target, storedDuration(target), meta.source, meta.human, meta.total,
                lines = readLines(target)
            )
        }
        buildLock.withLock {
            if (target.exists() && target.length() > 128) {
                val meta = readMeta(target)
                return@withLock NarrationResult(
                    target, storedDuration(target), meta.source, meta.human, meta.total,
                    lines = readLines(target)
                )
            }
            /*
             * الوحدة تحدّدها SHORT/FULL، وعددها يحدّده Say.
             *
             * SHORT يكرّر كل جملة، وFULL يكرّر كل فقرة — والتكرار متتابع في
             * موضعه لا في نهاية النص: من يعيد جملة ليفهمها يريدها الآن، لا
             * بعد دقيقتين حين تكون قد فاتته.
             */
            noteVoiceTag = voiceTag
            val units = splitUnits(text, byParagraph && n > 1)
            if (units.isEmpty()) return@withLock null
            val segments = units.flatMap { unit ->
                List(n) { Segment(unit, SegLang.EN, pauseMs = 350, role = SegRole.GENERATED) }
            }
            /*
             * ما يُعرض جملة دائماً، وإن كان ما يُكرَّر فقرة.
             *
             * كانت حدود العرض تُشتقّ من وحدات التكرار نفسها، فمع FULL تصير
             * الفقرة كلها سطراً واحداً — عشرات الأسطر تُلقى على الشاشة دفعة
             * واحدة، ولا يعرف المستمع أين هو منها.
             *
             * والعرض والتكرار غرضان مختلفان: التكرار يخدم الحفظ، والعرض يخدم
             * المتابعة بالعين. فالثاني جملةً جملةً مهما كان الأول.
             */
            buildSegments(segments, target, "note", onProgress)
                ?.also { writeLines(target, splitUnits(text, byParagraph = false), it.durationMs) }
                ?.copy(lines = readLines(target))
        }
    }

    /**
     * يقسّم النص إلى وحدات نطق — فقرات أو جملاً.
     *
     * الفقرة تُعرَّف بسطر فارغ لا بسطر واحد: نصّ ملصوق من صفحة ويب تتقطّع
     * أسطره عشوائياً، فاعتبار كل سطر فقرةً كان سيحوّل FULL إلى SHORT صامتاً.
     */
    private fun splitUnits(text: String, byParagraph: Boolean): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()
        val parts =
            if (byParagraph) clean.split(Regex("\\n\\s*\\n+"))
            else clean.split(Regex("(?<=[.!?؟])\\s+"))
        // نفس بوابة النطق التي تمرّ منها الكلمات: السلاش والرموز لا تُقرأ حرفياً
        return parts.map { speakable(it.replace(Regex("\\s+"), " ")) }.filter { it.isNotBlank() }
    }

    /**
     * يحفظ حدود كل وحدة زمنياً بجانب الصوت.
     *
     * بدونها لا سبيل لمعرفة أي جملة تُنطق الآن — والمستخدم يريد أن يقرأ ما
     * يسمعه لا مقتطفاً ثابتاً لا علاقة له بموضع التشغيل. والتقدير بالتساوي
     * كافٍ هنا لأن كل الوحدات تمرّ بنفس المحرك وبنفس السرعة.
     */
    private fun writeLines(target: File, texts: List<String>, durationMs: Long) {
        if (texts.isEmpty() || durationMs <= 0) return
        val weights = texts.map { it.length.coerceAtLeast(1).toDouble() }
        val totalWeight = weights.sum()
        var acc = 0.0
        val rows = texts.indices.map { i ->
            val start = (acc / totalWeight * durationMs).toLong()
            acc += weights[i]
            val end = (acc / totalWeight * durationMs).toLong()
            "$start$end${texts[i].replace('\n', ' ')}"
        }
        runCatching { linesFile(target).writeText(rows.joinToString("\n")) }
    }

    private fun readLines(target: File): List<NarrationLine> = runCatching {
        linesFile(target).readLines().mapNotNull { row ->
            val p = row.split('')
            if (p.size < 3) null
            else NarrationLine(p[0].toLong(), p[1].toLong(), p[2])
        }
    }.getOrDefault(emptyList())

    private fun linesFile(target: File) = File(target.parentFile, target.name + ".lines")

    private suspend fun build(
        word: Word,
        spec: NarrationSpec,
        target: File,
        onProgress: ((Int, Int) -> Unit)?
    ): NarrationResult? {
        val segments = NarrationBuilder.build(word, spec.repeat, spec.mode, spec.detail, spec.speakArabic)
        if (segments.isEmpty()) return null
        return buildSegments(segments, target, word.word, onProgress, spec, word.id)
    }

    /**
     * خطّ البناء المشترك: مقاطع ← صوت مرمَّز مخزَّن.
     *
     * الكلمة والملاحظة يختلفان في كيفية اشتقاق المقاطع فقط. وما بعد ذلك واحد:
     * التوليد المتوازي، والتطبيع، والتلاشي، والدمج، والترميز، والحدّ الأعلى
     * للتخزين. نسختان من هذا الخطّ تعنيان أن كل إصلاح فيه يجب أن يُكتب مرتين —
     * وأن إحداهما ستُنسى.
     */
    private suspend fun buildSegments(
        segments: List<Segment>,
        target: File,
        headword: String,
        onProgress: ((Int, Int) -> Unit)?,
        spec: NarrationSpec? = null,
        ownerId: Long = 0L
    ): NarrationResult? {
        val chunks = ArrayList<ShortArray>(segments.size * 2)
        var humanCount = 0
        var produced = 0
        var best = VoiceSource.NONE

        /*
         * توليد المقاطع بالتوازي.
         *
         * كان كل مقطع ينتظر الذي قبله، وبطاقة من سبعة مقاطع تعني سبع انتظارات
         * متتابعة — والمستخدم يرى «Preparing» طوال ذلك بدل أن يرى مشغّلاً.
         *
         * والعائق لم يكن المحرّك بل ملف مؤقت واحد تتقاسمه كل المقاطع: توليدان
         * متزامنان يكتبان فوق بعضهما. ملف لكل مقطع يرفع العائق.
         *
         * والحدّ أربعة: أعلى منه يزاحم خيط التشغيل نفسه على المعالج فيتقطّع
         * الصوت الجاري — وتسريع التحضير لا يستحق تخريب ما يُسمع الآن.
         */
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_SEGMENTS)
        val results = kotlinx.coroutines.coroutineScope {
            segments.mapIndexed { index, seg ->
                async {
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        val scratch = File(tmpDir, "seg-$ownerId-$index.wav")
                        val r = runCatching {
                            synthesizeSegment(seg, spec, scratch, headword)
                        }.getOrNull()
                        runCatching { scratch.delete() }
                        onProgress?.invoke(done.incrementAndGet(), segments.size)
                        r
                    }
                }
            }.awaitAll()
        }

        // الدمج بالترتيب الأصلي مهما اختلف ترتيب الانتهاء
        results.forEachIndexed { index, result ->
            if (result != null && result.samples.isNotEmpty()) {
                // التطبيع أولاً لتوحيد الشدة ومنع القصّ، ثم التلاشي لمنع الطقطقة
                chunks += WavUtils.applyEdgeFade(WavUtils.normalize(result.samples))
                chunks += WavUtils.silence(segments[index].pauseMs)
                produced++
                if (result.source == VoiceSource.HUMAN) humanCount++
                best = rank(best, result.source)
            }
        }
        if (produced == 0 || chunks.isEmpty()) return null

        /*
         * البطاقة تُرمَّز قبل أن تُحفظ.
         *
         * الصيغة الخام تكلّف ثلاثة ميغابايت للبطاقة — أي ثلاثة جيجابايت لألف
         * كلمة، وهو حجم لا يُنقل ولا يُنسخ ولا يُشارك. والترميز يُنزلها إلى نحو
         * مئة كيلوبايت بلا فرق مسموع في الكلام.
         *
         * وإن أخفق المرمّز نكتب الخام كما كان: بطاقة ثقيلة خير من بطاقة صامتة.
         */
        val merged = ShortArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) { c.copyInto(merged, at); at += c.size }

        val tmpOut = File(tmpDir, target.name)
        val encoded = runCatching { AudioEncoder.encodeToAac(merged, tmpOut) }.getOrDefault(false)
        if (!encoded) {
            runCatching { tmpOut.delete() }
            runCatching { WavUtils.writeWav(tmpOut, chunks) }.getOrElse {
                runCatching { tmpOut.delete() }
                return null
            }
        }
        runCatching { target.delete() }
        if (!tmpOut.renameTo(target)) {
            runCatching { tmpOut.copyTo(target, overwrite = true); tmpOut.delete() }
                .getOrElse { return null }
        }
        // المدة تُحسب من العيّنات قبل الترميز — وهي الحقيقة التي لا يعرفها الحجم
        val durationMs = WavUtils.durationMs(merged.size)
        writeMeta(target, best, humanCount, produced, durationMs)
        trimCache()
        return NarrationResult(target, durationMs, best, humanCount, produced)
    }

    /**
     * الأولوية للأعلى جودة: بشري ← سحابي ← عصبي ← نظام.
     *
     * غياب CLOUD عن هذه القائمة جعل كل بطاقة مولَّدة سحابياً تُسجَّل NONE،
     * فبدا الإحصاء وكأن الصوت المدفوع لا يعمل إطلاقاً بينما هو يعمل.
     */
    private fun rank(current: VoiceSource, incoming: VoiceSource): VoiceSource {
        val order = listOf(
            VoiceSource.NONE, VoiceSource.SYSTEM, VoiceSource.KOKORO,
            VoiceSource.CLOUD, VoiceSource.HUMAN
        )
        return if (order.indexOf(incoming) > order.indexOf(current)) incoming else current
    }

    private suspend fun synthesizeSegment(
        seg: Segment,
        spec: NarrationSpec?,
        scratch: File,
        headword: String
    ): SegmentAudio? {
        /*
         * التخزين المنفرد لكل مقطع مولَّد — للكلمات والملاحظات معاً.
         *
         * كان مشروطاً بوجود `spec`، وهي فارغة للملاحظات، فكانت كل جملة تُولَّد
         * من الصفر في كل مرة: تغيير Say من ×١ إلى ×٢ يعيد توليد النصّ كاملاً،
         * والجملة الواحدة تُولَّد مرتين داخل البناء الواحد لأنها تتكرّر.
         *
         * والمستخدم لاحظ ذلك ووصفه بدقة: «ليش كل طريقة تشغيل تحميل جديد؟
         * المفروض تنزل الجملة خلاص، ما يحتاج إعادة بناء». وكان محقاً — الجملة
         * نفسها بنفس الصوت تعطي نفس الصوت مهما تغيّر ترتيبها أو تكرارها.
         */
        val cacheTag = spec?.voiceTag ?: noteVoiceTag
        if (seg.role == SegRole.GENERATED && cacheTag != null) {
            // الإصدار يدخل مفتاح المقاطع أيضاً، وإلا بقيت العناوين القديمة مخشخشة
            val key = hash("v$pipelineVersion|" + seg.text + "|" + seg.lang.name + "|" + cacheTag)
            val cached = File(segDir, "$key.wav")
            val sourceTag = File(segDir, "$key.src")
            if (cached.exists() && cached.length() > 64) {
                // المصدر يُقرأ من جواره لا يُخمَّن: تخمينه بكوكورو كان يزوّر الإحصاء
                val known = runCatching { VoiceSource.valueOf(sourceTag.readText()) }
                    .getOrDefault(VoiceSource.KOKORO)
                WavUtils.decodeToCanonical(cached)?.let { return SegmentAudio(it, known) }
            }
            val source = voices.synthesize(seg, cached)
            if (source == VoiceSource.NONE) return null
            runCatching { sourceTag.writeText(source.name) }
            val pcm = WavUtils.decodeToCanonical(cached) ?: return null
            return SegmentAudio(pcm, source)
        }
        val source = voices.synthesize(seg, scratch, headword)
        if (source == VoiceSource.NONE) return null
        val pcm = WavUtils.decodeToCanonical(scratch) ?: return null
        return SegmentAudio(pcm, source)
    }

    // ===== بيانات مرافقة =====

    private fun metaFile(target: File) = File(target.absolutePath + ".meta")

    private fun writeMeta(
        target: File,
        source: VoiceSource,
        human: Int,
        total: Int,
        durationMs: Long
    ) {
        runCatching { metaFile(target).writeText("${source.name}|$human|$total|$durationMs") }
    }

    private class CardMeta(
        val source: VoiceSource,
        val human: Int,
        val total: Int,
        val durationMs: Long
    )

    private fun readMeta(target: File): CardMeta = runCatching {
        val parts = metaFile(target).readText().split('|')
        CardMeta(
            VoiceSource.valueOf(parts[0]),
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            parts.getOrNull(2)?.toIntOrNull() ?: 0,
            parts.getOrNull(3)?.toLongOrNull() ?: 0L
        )
    }.getOrDefault(CardMeta(VoiceSource.NONE, 0, 0, 0L))

    /**
     * المدة تُقرأ من البيانات المرافقة لا من حجم الملف.
     *
     * حساب المدة من الحجم كان يصحّ حين كانت البطاقة خاماً بترويسة ثابتة.
     * وبعد الترميز صار الحجم دالة على معدّل البت لا على الزمن، فحسابه منه
     * يعطي أرقاماً خاطئة يظهر أثرها في شريط التقدّم مباشرة.
     */
    private fun storedDuration(target: File): Long {
        val fromMeta = readMeta(target).durationMs
        if (fromMeta > 0) return fromMeta
        // بطاقة قديمة بصيغة خام وبلا مدة مسجّلة — نحسبها من الحجم كما كان
        val dataBytes = (target.length() - 44).coerceAtLeast(0)
        return WavUtils.durationMs((dataBytes / 2).toInt())
    }

    /**
     * إصدار خط الصوت.
     *
     * يُرفَع عند أي تغيير في معالجة الصوت نفسها (تلاشي الأطراف، الترشيح قبل
     * الخفض، الدمج). بدونه تبقى البطاقات المبنية بالمعالجة القديمة في التخزين
     * ويظل المستخدم يسمع الخشخشة رغم إصلاحها — فالمفتاح لا يعرف أن المعالجة تغيّرت.
     */
    /*
     * ٨: البطاقة المراجَعة صارت تحلّ محلّ القديمة لا تُضمّ إليها، فما
     * وُلّد قبلها يحمل الشرح القديم. ورفعُ الرقم يُسقط المخزون كلّه مرّةً
     * واحدة — أرخص من تتبّع أيّ ملفٍّ بُني قبل الإصلاح وأيّها بعده.
     */
    private val pipelineVersion = 8

    /** حدّ التوازي — أعلى منه يزاحم خيط التشغيل على المعالج فيتقطّع الصوت */
    private val MAX_PARALLEL_SEGMENTS = 4

    /*
     * المفتاح هو النصّ المنطوق نفسه — لا قائمةُ حقولٍ أصونها بيدي.
     *
     * كان يُبنى من حقولٍ مُعدَّدة: المعاني والأمثلة. ثم أُضيفت المرادفات
     * والمتلازمات إلى السرد ولم تُضَف إلى المفتاح، فبقي الصوت القديم
     * يُخدَم بعد إعادة كتابة البطاقة — بلا خطأ ولا أثر. وكلّما زاد السرد
     * حقلاً لزم أن يتذكّر أحدٌ تحديثَ هذه القائمة، ونسيانُه صامتٌ.
     *
     * فصار المفتاح بصمةَ المقاطع التي سيقرؤها المحرّك فعلاً: إن تغيّر
     * حرفٌ ممّا يُسمَع تغيّر المفتاح، وإن لم يتغيّر شيء أُعيد استعمال
     * الملفّ. لا رقمَ إصدارٍ يُرفع يدوياً، ولا حقلَ يُنسى.
     */
    private fun spokenKey(word: Word, spec: NarrationSpec): String {
        val segs = NarrationBuilder.build(
            word, spec.repeat, spec.mode, spec.detail, spec.speakArabic
        )
        return hash(
            buildString {
                append("v").append(pipelineVersion).append('|')
                append(spec.voiceTag).append('|')
                segs.forEach {
                    append(it.lang.name).append('>').append(it.text).append('\n')
                }
            }
        )
    }

    private fun cardKey(word: Word, spec: NarrationSpec): String =
        spokenKey(word, spec)

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    // ===== إدارة التخزين =====

    fun cacheSizeBytes(): Long =
        (cardDir.listFiles()?.sumOf { it.length() } ?: 0L) +
            (segDir.listFiles()?.sumOf { it.length() } ?: 0L)

    fun cachedCardCount(): Int =
        cardDir.listFiles()?.count { it.name.endsWith(".wav") } ?: 0

    private fun trimCache() {
        val files = cardDir.listFiles()?.filter { it.name.endsWith(".wav") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        val floor = (maxCacheBytes * 0.8).toLong()
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= floor) break
            val len = f.length()
            if (f.delete()) { total -= len; metaFile(f).delete() }
        }
    }

    fun clearCache() {
        cardDir.listFiles()?.forEach { runCatching { it.delete() } }
        segDir.listFiles()?.forEach { runCatching { it.delete() } }
        tmpDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun invalidate(wordId: Long) {
        val prefix = "$wordId-"
        cardDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix)) runCatching { f.delete() }
        }
    }
}
