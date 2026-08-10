package com.tornado.vocab

import android.app.Application
import android.content.Context
import com.tornado.vocab.data.AudioLibrarySync
import com.tornado.vocab.data.CardSource
import com.tornado.vocab.data.DictionaryService
import com.tornado.vocab.data.EnrichSync
import com.tornado.vocab.data.GitHubSync
import com.tornado.vocab.data.LibraryEnricher
import com.tornado.vocab.data.NoteRepository
import com.tornado.vocab.data.NoteSync
import java.io.File
import com.tornado.vocab.data.SecureKeyStore
import com.tornado.vocab.data.SettingsStore
import com.tornado.vocab.data.WordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * حاوية اعتماديات بسيطة.
 *
 * التطبيق صغير بما يكفي ليكون إطار حقن كامل عبئاً بلا مقابل: هذه الحاوية
 * تعطي نفس الفائدة — كائن واحد لكل خدمة، وإمكانية استبدالها في الاختبار —
 * بلا أي كلفة على زمن الإقلاع.
 */
class TornadoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: WordRepository by lazy { WordRepository(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val dictionary: DictionaryService by lazy { DictionaryService(this) }
    val sync: GitHubSync by lazy { GitHubSync(repository, SecureKeyStore(this)) }

    /**
     * مزامنة الصوت المولَّد.
     *
     * يشير إلى نفس مجلد البطاقات الذي يكتب فيه مولّد السرد، فما يُبنى هنا
     * يُرفع، وما يُنزَّل يُستعمل مباشرة بلا نسخ ولا تحويل.
     */
    val audioSync: AudioLibrarySync by lazy {
        AudioLibrarySync(SecureKeyStore(this), File(filesDir, "narration/cards"))
    }


    val enricher: LibraryEnricher by lazy {
        LibraryEnricher(repository, dictionary,
            writtenByHand = { enrichSync.curatedWordSet() })
    }

    /** الملاحظات الصوتية — نصوص طويلة تُسمع بنفس المشغّل */
    val notes: NoteRepository by lazy { NoteRepository(this) }
    val noteSync: NoteSync by lazy { NoteSync(notes, SecureKeyStore(this)) }

    /**
     * بطاقات الإثراء — تصل جاهزةً من المستودع.
     *
     * نفس المستودع ونفس الرمز الذي ينقل الكلمات والملاحظات: المستخدم أعدّ
     * المزامنة مرّةً، فليس من حقّنا أن نطلب إعداداً ثانياً لأننا أضفنا
     * نوع محتوى جديد.
     */
    val enrichSync: EnrichSync by lazy { EnrichSync(this, SecureKeyStore(this)) }

    /**
     * البطاقة كما تُقرأ وتُسمع — نقطة الدمج الوحيدة.
     *
     * من طلب نصّ بطاقةٍ ليعرضه أو ينطقه يطلبه من هنا لا من المستودع مباشرة،
     * فلا يبقى في التطبيق طريقٌ يتجاوز ما كُتب بيد.
     */
    val cards: CardSource by lazy { CardSource(repository, enrichSync) }

    /** المحرك الصوتي الأساسي — نسخة واحدة يشاركها التطبيق كله */
    val kokoro: com.tornado.vocab.audio.KokoroEngine by lazy {
        com.tornado.vocab.audio.KokoroEngine(this)
    }

    override fun onCreate() {
        super.onCreate()
        // الاستيراد الأولي خارج الخيط الرئيسي — الشاشة الأولى تظهر فوراً
        appScope.launch {
            runCatching {
                val added = repository.seedIfEmpty()
                if (added > 0) settings.setSeeded(true)
                /*
                 * ما لا يحتاجه أول رسم يُؤجَّل عنه.
                 *
                 * تنقية المعاني والمزامنة عملان لا ينتظرهما المستخدم ولا يظهر
                 * أثرهما في الشاشة الأولى، لكنهما ينافسان بناء الواجهة على
                 * المعالج في أحرج لحظة — فيطول الإقلاع بلا مقابل مرئي.
                 *
                 * تأخير قصير يكفي: الشاشة تُرسم أولاً، ثم يجري الباقي بهدوء.
                 */
                kotlinx.coroutines.delay(1_500)

                /*
                 * الصوت الأساسي يُجهَّز من تلقاء نفسه عند أول تشغيل.
                 *
                 * لا زرّ ولا سؤال: المستخدم أراد صوتاً فاخراً لا مهمة إعداد.
                 * والشرط الوحيد شبكة غير محدودة — ابتلاع مئة وأربعين ميغابايت
                 * من باقة جوال في أول فتحة خارج البيت ضررٌ لا يبرّره أي
                 * تحسين في الصوت. وعلى بيانات الجوال يبقى الزرّ في الإعدادات
                 * لمن يقرّر بنفسه، ومحرك الجهاز يعمل في الأثناء فلا صمت.
                 */
                /*
                 * التنزيل في مساره الخاص لا في هذا الطابور.
                 *
                 * وضعُه هنا مباشرةً كان يحجب كل ما بعده: ثلاثمئة وثلاثة
                 * وثلاثون ميغابايت تُنزَّل قبل أن تصل المكتبة إلى التنقية أو
                 * المزامنة أو الإثراء. وقياسٌ على المحاكي أظهر أن تنظيف روابط
                 * النطق لم يجرِ إطلاقاً لهذا السبب — لا لخلل فيه.
                 *
                 * والعملان مستقلان أصلاً: الصوت لا ينتظر الكلمات، والكلمات لا
                 * تنتظر الصوت.
                 */
                appScope.launch {
                    runCatching {
                        if (settings.audio.first().useKokoro) kokoro.installIfAppropriate()
                    }
                }
                /*
                 * تنقية المكتبة القائمة.
                 *
                 * إصلاح مصدر المعاني لا يلمس ما هو محفوظ أصلاً، فتبقى البطاقات
                 * المشوّهة أمام المستخدم رغم الإصلاح. المرور ثابت النتيجة:
                 * يغيّر ما يحتاج تغييراً في أول تشغيل ولا يفعل شيئاً بعده.
                 */
                repository.refineStoredMeanings()
                // ونمسح روابط النطق المملوكة من بطاقات بُنيت قبل حذف المصدر
                repository.purgeUnlicensedAudio()
            }
            /*
             * مزامنة صامتة عند كل فتح.
             *
             * نسخة الويب تسحب تلقائياً منذ البداية، فتوقّع المستخدم أن تصل
             * كلماته بلا ضغطة توقّع مبنيّ على تجربته لا على تخيّله. ومزامنة
             * تحتاج زراً تُنسى، ومكتبة تتفرّع بين جهازين بلا أن يدري أحد.
             *
             * والفشل هنا صامت عمداً: انقطاع الشبكة لحظة الفتح ليس خبراً
             * يستحق مقاطعة المستخدم، والمحاولة تتكرر في الفتحة التالية.
             */
            runCatching {
                val repo = settings.syncRepo()
                sync.repo = repo
                if (sync.canPull) sync.sync(push = sync.canPush)
                // الصوت بعد الكلمات: بطاقة بلا كلمة لا معنى لها
                audioSync.repo = repo
                audioSync.sync()
                // الملاحظات مع الكلمات في نفس الجولة — إعداد واحد لمحتويين
                noteSync.repo = repo
                if (noteSync.canPull) noteSync.sync(push = noteSync.canPush)
                /*
                 * الإثراء بعد الكلمات: بطاقةٌ لكلمةٍ ليست عندنا لا تُعرض.
                 *
                 * وهو سحبٌ فقط، فلا يزاحم رفع الكلمات ولا الملاحظات على أي
                 * ملف — ولا يُنزّل إلا الشرائح التي تغيّرت بصماتها.
                 */
                enrichSync.repo = repo
                enrichSync.sync()
            }
            /*
             * إثراء تدريجي بعد كل شيء آخر.
             *
             * نصف المكتبة بلا مثال أو نطق أو مستوى — لا لأن المعلومة مفقودة بل
             * لأن تلك البطاقات بُنيت قبل أن نتعلّم كيف نجلبها. المرور يعالج
             * ثماني كلمات في كل فتحة، فتكتمل المكتبة على مدى أيام بلا أن يشعر
             * المستخدم بشيء ولا أن نرهق مصدراً مجانياً.
             */
            /*
             * الإثراء في مساره الخاص لا خلف المزامنة.
             *
             * كان يقع بعد مزامنة الصوت في نفس الكوروتين، وتلك تنزّل ملفات
             * البطاقات من المستودع فتستغرق دقائق. فيبقى تصحيح المعاني منتظراً
             * خلفها، ويرى المستخدم كلماته على حالها ويظنّ الإصلاح لم يصل.
             *
             * والعملان مستقلان: الكلمات لا تنتظر الصوت، والصوت لا ينتظرها.
             */
            /*
             * مسحُ ما خزّنه القاموس الآليّ قبل إلغائه — مرّةً واحدة.
             *
             * منعُ الجديد لا يكفي: المكتبة القائمة فيها بطاقاتٌ كُتبت من ذلك
             * القاموس، وتبقى في القاعدة وتُرفع مع المزامنة. والمسح يقع على
             * الشرح وحده — لا على الكلمة ولا نطقها ولا تقدّم مراجعتها.
             */
            appScope.launch {
                runCatching {
                    if (!settings.machinePurged()) {
                        val n = enricher.purgeMachineContent()
                        settings.setMachinePurged(true)
                        android.util.Log.i("Tornado", "مُسح شرحٌ آليّ من $n كلمة")
                    }
                }
            }

            /*
             * البطاقة تنزل حين تجهز، لا حين يتذكّر صاحبها أن يضغط Sync.
             *
             * الكلمة تُرفع في ثوانٍ، ويكتب المسار بطاقتها على خوادم GitHub في
             * دقائق. وكان وصولها إلى الجهاز يتوقّف على مزامنةٍ يبدؤها المستخدم
             * — فيضيف كلمةً ويمضي، ثم يفتح التطبيق بعد يومين فلا يجدها وقد
             * كُتبت من أوّل ساعة.
             *
             * والاستطلاع مربوطٌ بالحاجة لا بالساعة: يسأل كل دقيقتين ما دامت
             * كلمةٌ تنتظر بطاقتها، ويسكت تماماً حين تكتمل المكتبة. فلا يستهلك
             * بطاريةً ولا باقةً في حالةٍ لا جديد فيها — وسؤالُه فهرسٌ صغير،
             * والشرائح لا تُنزَّل إلا إن تغيّرت بصماتها.
             */
            appScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(2 * 60_000L)
                    val waiting = runCatching {
                        val written = enrichSync.curatedWordSet()
                        repository.allWords().count {
                            it.word.trim().lowercase() !in written
                        }
                    }.getOrDefault(0)
                    if (waiting == 0) continue
                    runCatching {
                        if (enrichSync.sync() > 0) {
                            android.util.Log.i("Tornado", "وصلت بطاقاتٌ جديدة")
                        }
                    }
                }
            }
        }
    }
}

val Context.tornado: TornadoApp
    get() = applicationContext as TornadoApp
