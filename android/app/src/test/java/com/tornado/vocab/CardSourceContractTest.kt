package com.tornado.vocab

import com.tornado.vocab.audio.NarrationBuilder
import com.tornado.vocab.audio.NarrationDetail
import com.tornado.vocab.audio.NarrationMode
import com.tornado.vocab.data.Enrichment
import com.tornado.vocab.data.LangPair
import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.withEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * البطاقة المعروضة والمنطوقة تأتيان من نقطة دمجٍ واحدة.
 *
 * العطل الذي استدعى هذا الاختبار: كان الدمج يقع في شاشة الكلمة وحدها، بينما
 * القائمة المنسدلة وزرّ «Full» وتبويب الاستماع يقرأون `repository.word` خاماً.
 * فيرى صاحب المكتبة على شاشته شرحاً قديماً ويسمع معه شرحاً قديماً، وما كُتب
 * بيدٍ جالسٌ في القاعدة لا يصل إليه — والعطل صامت: لا انهيار ولا رسالة.
 *
 * ولا يكفي إصلاحه مرّة. فأيّ شاشةٍ تُضاف غداً قد تقرأ الخام كما قرأه هؤلاء،
 * فيعود العطل نفسه بلا أن يلاحظه أحد. لذلك يُفحص المصدر نفسه: من أراد نصّ
 * بطاقةٍ ليعرضه أو ينطقه فليأخذه من `CardSource`.
 */
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class CardSourceContractTest {

    /**
     * الإعفاء بالسطر لا بالملف.
     *
     * إعفاء ملفٍ كاملاً يُبطل الحراسة عن أكثر الملفات حاجةً إليها: شاشة
     * المكتبة تكتب ترجمةً في الخام بحقّ، ولو أُعفيت لمرّت فيها قراءةٌ خام
     * جديدة للعرض بلا أن يمسكها أحد. فالسطر الذي يقرأ الخام عن قصد يُعلَّم
     * بـ RAW-OK ومعه سببه، وما عداه خطأ.
     */
    private val exempt = setOf("WordRepository.kt", "CardSource.kt")
    private val marker = "RAW-OK"

    private fun mainSources(): List<File> {
        val root = File("src/main/java/com/tornado/vocab")
        assertTrue("لم يُعثر على مصادر التطبيق: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no display or audio path reads the raw word`() {
        val reader = Regex("""\b(repo|repository)\.word\(""")
        val offenders = mutableListOf<String>()

        for (f in mainSources()) {
            if (f.name in exempt) continue
            f.readLines().forEachIndexed { i, line ->
                if (reader.containsMatchIn(line) && !line.contains(marker)) {
                    offenders += "${f.name}:${i + 1}"
                }
            }
        }

        assertEquals(
            "هذه السطور تقرأ الكلمة خاماً — خذها من CardSource حتى لا يفترق " +
                "المقروء عن المسموع، أو علّمها بـ $marker مع سببها إن كانت للكتابة: " +
                offenders,
            emptyList<String>(), offenders
        )
    }

    /** الحارس نفسه يُمسك ما وُضع له — لا يمرّ لأن تعبيره لا يطابق شيئاً */
    @Test
    fun `the guard actually catches a raw read`() {
        val reader = Regex("""\b(repo|repository)\.word\(""")
        assertTrue(reader.containsMatchIn("val w = repo.word(id) ?: return"))
        assertTrue(reader.containsMatchIn("repository.word(row.id)"))
        assertTrue(
            "التعليم يجب أن يُعفي السطر",
            "val raw = repo.word(id)   // RAW-OK: للكتابة".contains(marker)
        )
    }

    /** نقطة الدمج موجودة فعلاً — حتى لا ينجح الفحص أعلاه بحذفها */
    @Test
    fun `the single merge point exists`() {
        val src = mainSources().first { it.name == "CardSource.kt" }.readText()
        assertTrue("CardSource فقد دالة الدمج", src.contains("fun Word.withEnrichment"))
        assertTrue("CardSource فقد قراءة البطاقة", src.contains("suspend fun card("))
    }

    /**
     * البطاقة المراجَعة تحلّ محلّ ما بناه التطبيق، ولا تُضاف إليه.
     *
     * البيانات أدناه تحاكي `abide`: التطبيق بنى لها «To pay for» من قاموسٍ
     * آليّ، والبطاقة المكتوبة تقول غير ذلك. فلو ضُمّت لتصدّر المعنى الخطأ
     * الصحيحَ في القائمة والصوت معاً.
     */
    @Test
    fun `curated card replaces what the app built for itself`() {
        val raw = Word(
            id = 1, word = "abide",
            meanings = listOf(
                Meaning(en = "To endure without yielding", ar = ""),
                Meaning(en = "To pay for", ar = "")
            ),
            synonyms = listOf(LangPair(en = "tolerate", ar = ""))
        )
        val curated = Enrichment.parse(
            """
            {"curated":true,
             "meanings":[{"en":"(usually negative) to tolerate someone or something",
                          "ar":"يطيق · يحتمل","pos":"verb"}],
             "synonyms":[{"en":"stand","ar":"يطيق","note":"مع النفي"}],
             "antonyms":[{"en":"violate","ar":"يخالف","note":"ضدّ abide by لا ضدّ «يطيق»"}]}
            """.trimIndent()
        )

        val shown = raw.withEnrichment(curated)

        assertEquals(1, shown.meanings.size)
        assertTrue(shown.meanings.first().ar.contains("يطيق"))
        assertTrue(
            "المعنى الآليّ الخاطئ ما زال معروضاً",
            shown.meanings.none { it.en == "To pay for" }
        )
        assertEquals(listOf("stand"), shown.synonyms.map { it.en })
        // الأضداد لا موضع لها في Word — تبقى في الإثراء وتُقرأ منه
        assertEquals(listOf("violate"), curated?.antonyms?.map { it.en })
    }

    /**
     * الصوت يقرأ البطاقة كاملة، لا نصفها.
     *
     * الأضداد وأنماط التركيب وملاحظتا النطق والاستعمال لا موضع لها في `Word`
     * أصلاً — تسكن الإثراء. وكان البنّاء يقرأ `Word` وحدها فيقف عند «الفروق»،
     * فيسمع المتعلّم نصف ما يقرأ ولا يدري أن ثمّة نصفاً آخر.
     */
    @Test
    fun `full narration speaks the sections that live only in the enrichment`() {
        val w = Word(
            id = 3, word = "abide",
            meanings = listOf(Meaning(en = "to tolerate", ar = "يطيق"))
        )
        val e = Enrichment.parse(
            """
            {"curated":true,
             "antonyms":[{"en":"violate","ar":"يخالف"}],
             "grammarPatterns":[{"en":"abide by + a rule","ar":"يلتزم بقاعدة",
                                 "ex":"You must abide by the rules","exAr":"يجب أن تلتزم"}],
             "pronunciationNote":[{"en":"abide","ar":"النبر على المقطع الثاني"}],
             "usageNotes":[{"ar":"تكاد تقتصر على النفي","ex":"can't abide",
                            "exAr":"لا يطيق"}]}
            """.trimIndent()
        )

        fun spoken(extra: Enrichment?) = NarrationBuilder.build(
            w, repeat = 1, mode = NarrationMode.RICH,
            detail = NarrationDetail.FULL, speakArabic = true, extra = extra
        ).joinToString("\n") { it.text }

        val withExtra = spoken(e)
        val without = spoken(null)

        for (piece in listOf("violate", "abide by + a rule",
                             "You must abide by the rules", "can't abide")) {
            assertTrue("الصوت لا يقول: $piece", withExtra.contains(piece))
            assertFalse("ظهر بلا إثراء — فالاختبار لا يثبت شيئاً: $piece",
                        without.contains(piece))
        }
        assertTrue(withExtra.contains("النبر على المقطع الثاني"))
    }

    /**
     * المفردات أيضاً: النطق والمستوى يأتيان من المراجَعة لا من القاموس الآليّ.
     *
     * `abide` نطقها في القاموس «بايد» بمقطعٍ واحد، وفي البطاقة المكتوبة
     * «أَبايْد» بمقطعين كما يقتضي /əˈbaɪd/. وكان القديم يغلب لأنه غير فارغ.
     */
    @Test
    fun `curated scalars win over what the app built for itself`() {
        val raw = Word(
            id = 4, word = "abide",
            arabicPron = "بايد", cefr = "B2", estCefr = "B2",
            meanings = listOf(Meaning(en = "to tolerate", ar = "يطيق"))
        )
        val curated = Enrichment.parse(
            """{"curated":true,"arabicPron":"أَبايْد","cefrEst":"C1",
                "meanings":[{"en":"to tolerate","ar":"يطيق","pos":"verb"}]}"""
        )
        val shown = raw.withEnrichment(curated)
        assertEquals("أَبايْد", shown.arabicPron)
        assertEquals("C1", shown.estCefr)

        // والآليّة لا تُزيح شيئاً — فيها ما ليس عندنا وحذفه خسارة
        val machine = Enrichment.parse("""{"curated":false,"arabicPron":"شيء آخر"}""")
        assertEquals("بايد", raw.withEnrichment(machine).arabicPron)
    }

    /** غير المراجَعة تُضاف ولا تُزيح — فيها ما ليس عندنا وحذفه خسارة */
    @Test
    fun `machine card is added not substituted`() {
        val raw = Word(
            id = 2, word = "cope",
            meanings = listOf(Meaning(en = "to manage", ar = "يتدبّر"))
        )
        val machine = Enrichment.parse(
            """{"curated":false,"meanings":[{"en":"to deal with","ar":"يتعامل"}]}"""
        )
        val shown = raw.withEnrichment(machine)
        assertEquals(2, shown.meanings.size)
        assertEquals("to manage", shown.meanings.first().en)
    }
}
