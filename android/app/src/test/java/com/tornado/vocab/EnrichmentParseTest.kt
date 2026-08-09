package com.tornado.vocab

import com.tornado.vocab.data.Enrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * تحليل بطاقة الإثراء كما تصل من المستودع حرفاً بحرف.
 *
 * البطاقة أدناه منسوخة من `enrich/co.json` بلا تعديل — لا مصنوعة للاختبار.
 * لأن ما يُختبر على بيانات مُصطنعة ينجح ثم يسقط على الحقيقية: العربية
 * والرموز الصوتية والحقول الغائبة كلّها مواضع انكسار لا تظهر إلا بها.
 */
// Robolectric ٤٫١٣ يقف عند ٣٤ والتطبيق يستهدف ٣٥. وorg.json لا يتغيّر
// بين النسختين، فالتثبيت هنا حدُّ أداة لا تنازلٌ عن تغطية.
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class EnrichmentParseTest {

    private val realCard = """
      {"arabicPron":"كوب","cefr":"B2","collocations":[
       {"ar":"يتعامل مع التوتّر","en":"cope with stress"},
       {"ar":"يكافح ليتدبّر","en":"struggle to cope"}],
       "curated":true,
       "derivatives":[{"ar":"التأقلم · التعامل مع الضغط","en":"coping (noun)"}],
       "differences":[{"ar":"التركيز على القدرة على الاحتمال",
                       "en":"cope = يصمد أمام صعوبة ويتحمّلها"}],
       "examples":[{"ar":"لا أستطيع التعامل مع هذا التوتّر.",
                    "en":"I can't cope with this stress."}],
       "inflections":["cope","copes","coped","coping"],
       "ipa":{"gen":"/kəʊp/"},
       "meanings":[
        {"ar":"يتدبّر أمره · يتعامل بنجاح مع موقف صعب","arSrc":null,
         "en":"to deal successfully with a difficult situation","pos":"verb","src":"curated"},
        {"ar":"عباءة طويلة يلبسها الكاهن في المناسبات الدينية","arSrc":null,
         "en":"a long cloak worn by a priest at ceremonies","pos":"noun","src":"curated"}],
       "oxford":"3000","pos":["verb","noun"],
       "synonyms":[{"ar":"يتدبّر","en":"manage"},{"ar":"يتحمّل","en":"endure"}],
       "usageNotes":["يأتي غالباً مع with: cope with stress."],
       "absent":["antonyms","phrasalVerbs","idioms"],
       "v":1,"word":"cope"}
    """.trimIndent()

    @Test
    fun `يقرأ البطاقة الحقيقية بكل حقولها`() {
        val e = Enrichment.parse(realCard)!!
        assertEquals("cope", e.word)
        assertEquals("/kəʊp/", e.ipaGen)
        assertEquals("كوب", e.arabicPron)          // العربية تعبر سليمة
        assertEquals("3000", e.oxford)
        assertEquals("B2", e.cefr)
        assertEquals(listOf("verb", "noun"), e.pos)
        assertEquals(4, e.inflections.size)
        assertEquals(1, e.usageNotes.size)
    }

    @Test
    fun `المعاني تحمل قسم الكلام وعربيتها`() {
        val m = Enrichment.parse(realCard)!!.meanings
        assertEquals(2, m.size)
        assertEquals("verb", m[0].pos)
        assertEquals("يتدبّر أمره · يتعامل بنجاح مع موقف صعب", m[0].ar)
        assertTrue(m[1].en.startsWith("a long cloak"))
    }

    @Test
    fun `الأزواج تُقرأ بشكلها الجديد en و ar`() {
        val e = Enrichment.parse(realCard)!!
        assertEquals("cope with stress", e.collocations[0].en)
        assertEquals("يتعامل مع التوتّر", e.collocations[0].ar)
        assertEquals("coping (noun)", e.derivatives[0].en)
        assertEquals("manage", e.synonyms[0].en)
        assertEquals("لا أستطيع التعامل مع هذا التوتّر.", e.examples[0].ar)
    }

    @Test
    fun `الغائب يُقرأ ويُترجم عنوانه`() {
        val e = Enrichment.parse(realCard)!!
        assertEquals(listOf("antonyms", "phrasalVerbs", "idioms"), e.absent)
        assertEquals("الأضداد", Enrichment.absentLabel("antonyms"))
        assertEquals("الأفعال المركّبة", Enrichment.absentLabel("phrasalVerbs"))
        assertTrue(e.antonyms.isEmpty())            // غائبٌ فارغ لا null
    }

    /*
     * بطاقةٌ معطوبة تعني كلمةً بلا إثراء — لا شاشةً ساقطة.
     *
     * الشريحة تصل عبر الشبكة وقد تُقطع في منتصفها، وسقوطُ التطبيق عند
     * فتح كلمة أسوأ من عرضها بلا إثراء.
     */
    @Test
    fun `المعطوب لا يُسقط شيئاً`() {
        assertNull(Enrichment.parse(null))
        assertNull(Enrichment.parse(""))
        assertNull(Enrichment.parse("{ليس"))
        assertNull(Enrichment.parse("[1,2,3]"))
        val empty = Enrichment.parse("{}")!!
        assertEquals("", empty.word)
        assertTrue(empty.meanings.isEmpty())
    }

    @Test
    fun `المتلازمة بالشكل القديم من القاعدة تُقرأ أيضاً`() {
        // القاعدة تكتب {col, pat}؛ المكتوب بيدٍ يكتب {en, ar}
        val old = """{"word":"x","collocations":[{"col":"soil","pat":"compound"}]}"""
        val e = Enrichment.parse(old)!!
        assertEquals(1, e.collocations.size)
        assertEquals("soil", e.collocations[0].en)
        assertEquals("compound", e.collocations[0].ar)
    }
}
