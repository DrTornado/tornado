package com.tornado.vocab.data

import android.content.Context

/**
 * أدوات صرفية وصوتية منقولة عن محرّك تطبيق الويب، بنفس القواعد تماماً
 * حتى تتطابق البطاقات المولّدة على الأندرويد مع البطاقات المحفوظة سابقاً.
 */
object Linguistics {

    private val ARABIC_RANGE = Regex("[\\u0600-\\u06FF]")
    fun isArabic(t: String?): Boolean = !t.isNullOrEmpty() && ARABIC_RANGE.containsMatchIn(t)

    // ===== النطق بحروف عربية من رموز IPA (يعمل بلا إنترنت) =====
    // الترتيب مقصود: الثنائيات قبل المفردات، وإلا حُلّل "tʃ" حرفين منفصلين.
    private val IPA_MAP: List<Pair<String, String>> = listOf(
        "tʃ" to "تش", "dʒ" to "ج", "eɪ" to "ي", "aɪ" to "اي", "ɔɪ" to "وي", "aʊ" to "او",
        "əʊ" to "و", "oʊ" to "و", "ɪə" to "ير", "eə" to "ير", "ʊə" to "ور", "ɜː" to "ير",
        "ɝ" to "ير", "ɚ" to "ر", "iː" to "ي", "uː" to "و", "ɑː" to "ا", "ɔː" to "و",
        "ɒ" to "و", "ŋk" to "نك", "ŋɡ" to "نق", "ʃ" to "ش", "ʒ" to "ج", "θ" to "ث",
        "ð" to "ذ", "ŋ" to "نق", "æ" to "ا", "ʌ" to "ا", "ɛ" to "ي", "e" to "ي",
        "ɪ" to "ي", "i" to "ي", "ʊ" to "و", "u" to "و", "a" to "ا", "ɔ" to "و",
        "ɑ" to "ا", "p" to "ب", "b" to "ب", "t" to "ت", "d" to "د", "k" to "ك",
        "ɡ" to "ق", "g" to "ق", "f" to "ف", "v" to "ڤ", "s" to "س", "z" to "ز",
        "h" to "ه", "m" to "م", "n" to "ن", "l" to "ل", "r" to "ر", "ɹ" to "ر",
        "j" to "ي", "w" to "و", "x" to "خ"
    )

    private val IPA_STRIP = Regex("[/\\[\\]ˈˌːˑ().]")

    fun ipaToArabic(ipa: String?): String {
        if (ipa.isNullOrBlank()) return ""
        var s = IPA_STRIP.replace(ipa, "").replace(Regex("\\s+"), " ").trim()
        // الشوا تسقط إلا في بداية الكلمة (أباوت)، وقبل الراء عولجت في الخريطة نفسها
        s = s.replaceFirst(Regex("^ə"), "ا").replace("ə", "")
        val out = StringBuilder()
        outer@ while (s.isNotEmpty()) {
            for ((k, v) in IPA_MAP) {
                if (s.startsWith(k)) { out.append(v); s = s.substring(k.length); continue@outer }
            }
            s = s.substring(1) // رمز غير معروف: تجاوز آمن
        }
        return out.toString()
    }

    // ===== الأفعال الشاذة: base -> (past, pastParticiple) =====
    private val IRREGULAR: Map<String, Pair<String, String>> = buildMap {
        val raw = "be:was:been,have:had:had,do:did:done,say:said:said,go:went:gone,get:got:gotten," +
            "make:made:made,know:knew:known,think:thought:thought,take:took:taken,see:saw:seen," +
            "come:came:come,find:found:found,give:gave:given,tell:told:told,become:became:become," +
            "show:showed:shown,leave:left:left,feel:felt:felt,put:put:put,bring:brought:brought," +
            "begin:began:begun,keep:kept:kept,hold:held:held,write:wrote:written,stand:stood:stood," +
            "hear:heard:heard,let:let:let,mean:meant:meant,set:set:set,meet:met:met,run:ran:run," +
            "pay:paid:paid,sit:sat:sat,speak:spoke:spoken,lie:lay:lain,lead:led:led,read:read:read," +
            "grow:grew:grown,lose:lost:lost,fall:fell:fallen,send:sent:sent,build:built:built," +
            "understand:understood:understood,draw:drew:drawn,break:broke:broken,spend:spent:spent," +
            "cut:cut:cut,rise:rose:risen,drive:drove:driven,buy:bought:bought,wear:wore:worn," +
            "choose:chose:chosen,seek:sought:sought,throw:threw:thrown,catch:caught:caught," +
            "deal:dealt:dealt,win:won:won,forget:forgot:forgotten,lay:laid:laid,sell:sold:sold," +
            "fight:fought:fought,teach:taught:taught,eat:ate:eaten,sing:sang:sung,drink:drank:drunk," +
            "sleep:slept:slept,fly:flew:flown,rid:rid:rid,swim:swam:swum,ride:rode:ridden," +
            "feed:fed:fed,shake:shook:shaken,hang:hung:hung,hide:hid:hidden,shoot:shot:shot," +
            "strike:struck:struck,bend:bent:bent,bite:bit:bitten,blow:blew:blown,burst:burst:burst," +
            "cost:cost:cost,dig:dug:dug,freeze:froze:frozen,hit:hit:hit,hurt:hurt:hurt," +
            "lend:lent:lent,light:lit:lit,quit:quit:quit,ring:rang:rung,shine:shone:shone," +
            "shut:shut:shut,steal:stole:stolen,stick:stuck:stuck,swear:swore:sworn,sweep:swept:swept," +
            "tear:tore:torn,wake:woke:woken,beat:beat:beaten,bear:bore:borne,spread:spread:spread," +
            "split:split:split,slide:slid:slid,spin:spun:spun,forgive:forgave:forgiven"
        raw.split(',').forEach { e ->
            val p = e.split(':')
            if (p.size == 3) put(p[0], p[1] to p[2])
        }
    }

    /** صيغ الكلمة المشتقة من جذرها الحقيقي — لا من الصيغة التي كتبها المستخدم */
    fun inflect(word: String, pos: List<String>): List<String> {
        val w = word.lowercase()
        if (w.contains(Regex("\\s"))) return emptyList() // عبارات مركبة: لا تصريف
        val out = mutableListOf(w)
        val isVerb = pos.contains("verb")
        val isNoun = pos.contains("noun")

        // مضاعفة الحرف الأخير للأفعال القصيرة ساكن-متحرك-ساكن: run -> running
        val dbl = if (w.length <= 4 && Regex("[^aeiouwxy][aeiou][^aeiouwxy]$").containsMatchIn(w))
            w + w.last() else w

        val sForm = when {
            Regex("(s|sh|ch|x|z|o)$").containsMatchIn(w) -> w + "es"
            Regex("[^aeiou]y$").containsMatchIn(w) -> w.dropLast(1) + "ies"
            else -> w + "s"
        }
        if (isVerb || isNoun) out += sForm

        if (isVerb) {
            val irr = IRREGULAR[w]
            if (irr != null) {
                out += irr.first
                if (irr.second != irr.first) out += irr.second
            } else {
                out += when {
                    w.endsWith("e") -> w + "d"
                    Regex("[^aeiou]y$").containsMatchIn(w) -> w.dropLast(1) + "ied"
                    else -> dbl + "ed"
                }
            }
            val ing = when {
                w.endsWith("ie") -> w.dropLast(2) + "y"
                w.endsWith("e") && !w.endsWith("ee") -> w.dropLast(1)
                else -> dbl
            }
            out += ing + "ing"
        }
        return out.distinct()
    }

    // ===== المتلازمات: كلمات الحشو مرفوضة كشريك =====
    private val STOP: Set<String> = (
        "and,or,the,a,an,of,in,on,at,to,for,with,by,from,as,is,are,was,were,be,been,being,that," +
        "this,these,those,it,its,he,she,they,we,you,i,his,her,their,our,your,my,but,not,no,so,if," +
        "than,then,when,which,who,whom,will,would,can,could,may,might,must,shall,should,do,does," +
        "did,have,has,had,also,such,into,about,over,under,between,among,other,more,most,some,any," +
        "each,every,all,both,few,many,much,very,too,just,only,there,here,what,how,why,where,while," +
        "because,during,after,before,against,through,per,via,one,two,new,own,same,us,them,him,me"
        ).split(',').toSet()

    private fun okPartner(s: String) =
        Regex("^[a-z][a-z'-]*$").matches(s) && s.length >= 2 && s !in STOP

    /**
     * ينقّي المتلازمات المغشوشة: يجب أن تحتوي الكلمة الأساس وكلمة محتوى حقيقية أخرى.
     * متلازمة يتيمة واحدة مشكوك فيها، فالقسم كله يُحذف بدل حشو بلا معنى.
     */
    fun sanitizeCollocations(base: String, colls: List<LangPair>): List<LangPair> {
        val b = base.lowercase()
        val kept = colls.filter { p ->
            val parts = p.en.lowercase().trim().split(Regex("\\s+"))
            parts.size in 2..4 && parts.contains(b) && parts.any { it != b && okPartner(it) }
        }
        return if (kept.size < 2) emptyList() else kept
    }

    fun isGoodCollocationPartner(s: String) =
        Regex("^[a-z][a-z'-]{2,}$").matches(s) && s !in STOP

    /**
     * المشتقات: نولّد مرشحين بالقواعد الصرفية ونقبل الموجود فعلاً في قائمة أكسفورد فقط.
     * هذا يمنع اختراع كلمات غير موجودة ("teachment") ويبقي القسم موثوقاً.
     */
    suspend fun buildDerivatives(context: Context, base: String): List<String> {
        val w = base.lowercase()
        if (!Regex("^[a-z]+$").matches(w)) return emptyList()
        val ox = ReferenceData.oxfordMap(context)
        val noE = if (w.endsWith("e")) w.dropLast(1) else w
        val noY = if (Regex("[^aeiou]y$").containsMatchIn(w)) w.dropLast(1) else w

        val candidates = linkedSetOf(
            w + "er", noE + "er", w + "or", noE + "or",
            w + "er" + "s", w + "ment", noE + "ation", noE + "ion", w + "ion",
            w + "ness", noY + "iness", w + "ful", w + "less",
            w + "able", noE + "able", w + "ible",
            w + "al", noE + "al", w + "ive", noE + "ive",
            w + "ly", noY + "ily", w + "ity", noE + "ity",
            w + "ist", w + "ism", w + "ance", w + "ence", noE + "ing", w + "y",
            "un$w", "re$w", "dis$w", "in$w", "im$w"
        )
        return candidates
            .filter { it != w && it.length > w.length - 2 && ox.containsKey(it) }
            .take(6)
    }

    /**
     * يكتشف المعاني التي هي مجرد "صيغة مصرّفة من X" ويستخرج الجذر.
     * قيمتها للمتعلّم صفر، ووجودها يمنع بناء بطاقة حقيقية.
     */
    val INFLECTED_RE = Regex(
        "^(?:the\\s+)?(?:plural|third-person singular(?:\\s+simple present(?:\\s+indicative)?)?(?:\\s+form)?" +
            "|past tense|simple past(?:\\s+tense)?|past participle|present participle" +
            "|comparative(?:\\s+form)?|superlative(?:\\s+form)?|alternative\\s+(?:form|spelling))" +
            "\\s+of\\s+([a-zA-Z][a-zA-Z'-]*)",
        RegexOption.IGNORE_CASE
    )
}
