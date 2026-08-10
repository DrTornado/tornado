#!/usr/bin/env python3
"""
يفحص كل بطاقة مكتوبة بيد على المواصفة — بالقياس لا بالزعم.

كُتب بعد أن قرأ صاحب المشروع بطاقة `abide` فرأى تحت «الأضداد» كلمة
`violate` فسأل: أبايد تساوي فايوليت؟ وكان محقّاً: `violate` ضدّ
`abide by` لا ضدّ `abide` بمعنى «يطيق»، والبطاقة لم تقل لأي معنى ينتمي
الضدّ. فالخطأ ليس في المعلومة بل في عرضها بلا نسبة.

ما يُفحص:
  ١ الأقسام الاثنا عشر موجودة كلّها
  ٢ لكل قسم حدٌّ أدنى — قسمٌ بعنصرٍ واحد ليس قسماً
  ٣ لا تصريفات مهجورة (copest · apothecarie · beholdeth)
  ٤ كل زوج له إنجليزيّ وعربيّ — لا نصف زوج
  ٥ العربية عربيةٌ فعلاً — لا لاتينية تسرّبت
  ٦ الكلمة متعدّدة المعاني تنسب مرادفاتها وأضدادها إلى معناها

    python audit_cards.py            تقرير
    python audit_cards.py --strict   يخرج بخطأ إن وُجد خلل (للمهمّة المجدولة)
"""

import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def curated_dir() -> str:
    """
    البطاقات في المستودع الخاصّ لا العامّ.

    فيها كلمات صاحب المكتبة وأمثلتها، ومستودع التطبيق عامٌّ لأن صفحات
    GitHub تقتضي ذلك. والأدوات وحدها تبقى هناك: هي شيفرة لا محتوى.

    وتُقبَل الوجهةُ القديمة إن وُجدت — فمن نسخ المشروع بلا المستودع الخاصّ
    لا ينكسر عنده شيء.
    """
    env = os.environ.get("TORNADO_CURATED")
    if env:
        return env
    # tools/enrich → tools → tornado → GitHub → tornado-data/curated
    root = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
    private = os.path.join(root, "tornado-data", "curated")
    return private if os.path.isdir(private) else os.path.join(HERE, "curated")


CURATED = curated_dir()

ARABIC = re.compile(r"[؀-ۿ]")
# نقطةٌ لاتينية في آخر سطرٍ عربيّ.
#
# قيس على الجهاز: كلّ سطرٍ عربيّ ينتهي بها يأخذ سطرين — ارتفاعه ١٢٧ بكسل
# مقابل ٤٧ للإنجليزيّ — وينكسر قبل كلمته الأخيرة مهما اتّسع ما حوله. لأنها
# محايدة الاتّجاه فتصير جرياً مستقلاً في فقرةٍ من اليمين إلى اليسار.
# والأسطر العربية التي بلا نقطة تُعرض في سطرٍ واحد ولو بلغت أربعين حرفاً.
AR_TAIL_DOT = re.compile(r"[  ]*\.\s*$")
ARCHAIC = re.compile(r"(est|eth|edst|dst)$", re.I)
# حروفٌ لاتينية داخل خانة العربية — علامةُ سطرٍ مختلط
LATIN = re.compile(r"[A-Za-z]{2,}")

# الحدّ الأدنى لكل قسم. اثنان ليسا قائمة، والواحد ليس قسماً.
MINIMUM = {
    "meanings": 2, "inflections": 2, "derivatives": 3, "synonyms": 4,
    "antonyms": 3, "examples": 4, "collocations": 5, "differences": 3,
    "usageNotes": 2, "pronunciationNote": 1,
    # كانت خارج الحدود الدنيا فمرّت ستٌّ وعشرون بطاقة بلا نمطٍ واحد،
    # وهي في مواصفة صاحب المكتبة منذ أوّل بطاقة أرسلها.
    "grammarPatterns": 2,
}
SCALAR = ("ipa", "arabicPron", "pos")
PAIRED = ("derivatives", "synonyms", "antonyms", "examples",
          "collocations", "differences", "grammarPatterns",
          "usageNotes", "pronunciationNote")


def _same(a: str, b: str) -> bool:
    """تسويةٌ خفيفة: الفروق في التشكيل والفواصل لا تجعل السطرين مختلفين."""
    strip = re.compile(r"[\s‏…·,،.()«»]+")
    return bool(a) and bool(b) and strip.sub(" ", a).strip() == strip.sub(" ", b).strip()


def line_faults(c: dict) -> list:
    """
    قواعد السطر الواحد — تُفحص في كل حقلٍ من كل قسم، لا في أقسامٍ منتقاة.

    كلُّ خللٍ منها ظهر فعلاً على الشاشة ثم صار قاعدة:
      • نقطةٌ لاتينية في آخر سطرٍ عربيّ  ⇦ ينكسر السطر سطرين قبل كلمته الأخيرة
      • عربيّ في `ex`، أو إنجليزيّ في `exAr` ⇦ سطرٌ يقفز بين اتّجاهين
      • `ar` تساوي `exAr` ⇦ يُقرأ السطر مرّتين بلا فائدة
      • «» فارغة ⇦ بقيّةُ فصلٍ آليّ أتلف النصّ
    """
    bad = []

    def check(path, o):
        ar, ex, exar = o.get("ar"), o.get("ex"), o.get("exAr")
        note = o.get("note")
        for k, v in (("ar", ar), ("exAr", exar)):
            if isinstance(v, str) and ARABIC.search(v) and AR_TAIL_DOT.search(v):
                bad.append(f"{path}.{k} ينتهي بنقطة لاتينية (ينكسر سطرين): «{v[-22:]}»")
        if isinstance(ex, str) and ARABIC.search(ex):
            bad.append(f"{path}.ex فيه عربيّ — ضع الإنجليزيّ وحده: «{ex[:34]}»")
        if isinstance(exar, str) and LATIN.search(exar):
            bad.append(f"{path}.exAr فيه إنجليزيّ: «{exar[:34]}»")
        # المصطلح الإنجليزيّ داخل الملاحظة العربية مقصود ولا يُمنع: نسبةُ
        # المرادف إلى معناه تحتاجه — «ضدّ abide by، لا ضدّ يطيق». والممنوع
        # جملةٌ إنجليزية كاملة تسكن ملاحظةً عربية، فتلك سطرٌ يقفز بين اتّجاهين.
        if isinstance(note, str) and ARABIC.search(note):
            # المقتبَس بين «» صيغةٌ يُشار إليها لا جملةٌ تُقرأ — «لا تقل
            # «discourage him to go»» ملاحظةٌ عربية سليمة. فيُنزع المقتبَس
            # قبل الفحص، ويبقى الممنوع: إنجليزيّةٌ سائبةٌ داخل نصٍّ عربيّ.
            loose = re.sub(r"«[^»]*»", " ", note)
            if re.search(r"(?:[A-Za-z][\w'-]*\s+){3,}[A-Za-z]", loose):
                bad.append(
                    f"{path}.note فيه جملة إنجليزية سائبة — اقتبسها أو انقلها إلى ex: "
                    f"«{note[:34]}»"
                )
        if _same(ar or "", exar or ""):
            bad.append(f"{path}: ar و exAr سطرٌ واحد مكرَّر: «{(ar or '')[:30]}»")
        for k, v in o.items():
            if isinstance(v, str) and "«»" in v:
                bad.append(f"{path}.{k} فيه اقتباس فارغ — نصٌّ أتلفه فصلٌ آليّ")

    for section, val in c.items():
        if isinstance(val, list):
            for i, o in enumerate(val):
                if isinstance(o, dict):
                    check(f"{section}[{i}]", o)
        elif isinstance(val, dict):
            check(section, val)

    # الأفعال المركّبة والتعابير بشكلٍ آخر: {phrase, en, gloss}.
    #
    # وكان الشرح فيها حقلاً واحداً للغتين — «to follow a rule — يلتزم بقاعدة»
    # — فسقطت من الفحص كلّه لأن مفاتيحها ليست `ar`/`en`. فيُفحص `gloss`:
    # عربيٌّ خالص، بلا نقطةٍ لاتينية في آخره.
    for sec in ("phrasalVerbs", "idioms"):
        for i, p in enumerate(c.get(sec) or []):
            if not isinstance(p, dict):
                continue
            g = p.get("gloss") or ""
            if not g:
                bad.append(f"{sec}[{i}] بلا شرح عربيّ")
            elif not ARABIC.search(g):
                bad.append(f"{sec}[{i}] الشرح ليس عربياً: «{g[:34]}»")
            elif LATIN.search(g):
                bad.append(f"{sec}[{i}] الشرح يخلط اللغتين — الإنجليزيّ إلى en: "
                           f"«{g[:34]}»")
            elif AR_TAIL_DOT.search(g):
                bad.append(f"{sec}[{i}] الشرح ينتهي بنقطة لاتينية: «{g[-22:]}»")
            if ARABIC.search(p.get("phrase") or ""):
                bad.append(f"{sec}[{i}] العبارة فيها عربيّ: «{p['phrase'][:30]}»")
    return bad


def _oxford_list() -> dict:
    """
    قائمة أوكسفورد ٥٠٠٠ الرسمية التي تُشحن داخل التطبيق — هي الحكم.

    كتبتُ «Oxford 3000» و«5000» في أربعٍ وثلاثين بطاقة من تقديري، والقائمة
    لا تسندها. والقائمة هنا منذ البداية ولم أرجع إليها.
    """
    global _OX
    if _OX is not None:
        return _OX
    path = os.path.join(
        os.path.dirname(os.path.dirname(HERE)),
        "android", "app", "src", "main", "assets", "oxford.txt"
    )
    m = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                p = line.rstrip("\n").split("|")
                if len(p) >= 2 and p[0].strip():
                    m[p[0].strip().lower()] = p[1].strip()
    _OX = m
    return m


_OX = None


def faults(word: str, c: dict) -> list:
    out = []

    out.extend(line_faults(c))

    # التصنيف والمستوى من القائمة لا من التقدير
    ox = _oxford_list()
    if ox:
        lvl = ox.get(word.lower())
        got = (c.get("oxford") or "").strip()
        if not got:
            out.append("ينقص oxford — لكل كلمةٍ تصنيف، ولو كان «none»")
        elif lvl and got != "3000/5000":
            out.append(f"oxford «{got}» والكلمة في القائمة — الصواب 3000/5000")
        elif not lvl and got != "none":
            out.append(f"oxford «{got}» بلا سندٍ من القائمة — الصواب none")
        if lvl:
            if (c.get("cefr") or "").strip() != lvl:
                out.append(f"cefr «{c.get('cefr')}» وأوكسفورد تقول {lvl}")
            if (c.get("cefrEst") or "").strip():
                out.append("cefrEst مع تصنيفٍ رسميّ — التقدير للخارج عن القائمة")
        elif (c.get("cefr") or "").strip():
            out.append("cefr رسميّ لكلمةٍ خارج القائمة — اجعله cefrEst")

    for k in SCALAR:
        if not c.get(k):
            out.append(f"ينقص {k}")

    for k, least in MINIMUM.items():
        v = c.get(k) or []
        if not v:
            out.append(f"ينقص {k}")
        elif len(v) < least:
            out.append(f"{k}: {len(v)} والمطلوب {least} فأكثر")

    for f in c.get("inflections") or []:
        if ARCHAIC.search(f) and f.lower() != word.lower():
            out.append(f"تصريف مهجور: {f}")

    for k in PAIRED:
        for i, x in enumerate(c.get(k) or []):
            if not isinstance(x, dict):
                out.append(f"{k}[{i}] ليس زوجاً")
                continue
            if not x.get("en") and k not in ("usageNotes", "pronunciationNote"):
                out.append(f"{k}[{i}] بلا إنجليزي")
            if not x.get("ar"):
                out.append(f"{k}[{i}] بلا عربي: {x.get('en')}")
            elif not ARABIC.search(x["ar"]):
                out.append(f"{k}[{i}] «العربي» ليس عربياً: {x['ar'][:30]}")
            elif LATIN.search(x["ar"]):
                # سطرٌ يجمع اللغتين يُتعب البصر: يقفز بين اتّجاهين في سطرٍ
                # واحد. والتوضيح يذهب إلى `note`، والمثال إلى `ex`/`exAr`.
                out.append(f"{k}[{i}] العربيّ فيه إنجليزيّ — افصله في note: "
                           f"{x['ar'][:40]}")
            if ARABIC.search(x.get("en") or ""):
                out.append(f"{k}[{i}] الإنجليزيّ فيه عربيّ: {x['en'][:40]}")

    for i, m in enumerate(c.get("meanings") or []):
        if not m.get("en"):
            out.append(f"meanings[{i}] بلا إنجليزي")
        if not m.get("ar") or not ARABIC.search(m.get("ar", "")):
            out.append(f"meanings[{i}] بلا عربي صحيح")
        # المعاني كانت خارج فحص الخلط، وهي أوّل ما يُقرأ في البطاقة.
        # ومرّت منها واحدة: «يطيق · يحتمل — تُستعمل بالنفي غالباً: can't abide»
        # فانكسر السطر بين اتّجاهين وتدلّت «abide» وحدها في سطرٍ تالٍ. ولا
        # موضع هنا لـ `note`، فالإنجليزيّ يعود إلى خانته: `en`.
        elif LATIN.search(m["ar"]):
            out.append(f"meanings[{i}] العربيّ فيه إنجليزيّ — أعِده إلى en: "
                       f"{m['ar'][:40]}")
        if ARABIC.search(m.get("en") or ""):
            out.append(f"meanings[{i}] الإنجليزيّ فيه عربيّ: {m['en'][:40]}")
        if not m.get("pos"):
            out.append(f"meanings[{i}] بلا قسم كلام")

    # الكلمة ذات المعنيين فأكثر: مرادفها وضدّها يجب أن يُنسبا إلى معناهما،
    # وإلا قرأ المتعلّم «abide → violate» فظنّهما مترادفين. النسبة تُكتب في
    # العربية بعد شرطة: «يخالف — ضدّ abide by».
    if len({(m.get("pos") or "") + (m.get("en") or "")[:12]
            for m in c.get("meanings") or []}) > 1:
        for k in ("synonyms", "antonyms"):
            items = c.get(k) or []
            # النسبة موضعها `note` بعد فصل اللغتين، وكانت تُكتب داخل العربية
            # بشرطة. فنقبل الموضعين: القديم لم يُهجر بعد في كل البطاقات.
            if items and not any(
                    x.get("note") or "—" in (x.get("ar") or "") for x in items):
                out.append(f"{k}: بلا نسبة إلى المعنى (الكلمة متعدّدة المعاني)")

    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--curated", default=CURATED)
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--word")
    a = ap.parse_args()

    cards, seen = {}, {}
    dupes = []
    for path in sorted(glob.glob(os.path.join(a.curated, "*.json"))):
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        for k, v in raw.items():
            if not k.startswith("_") and isinstance(v, dict):
                # الكلمة في ملفّين: الأخيرة تُلغي الأولى صامتةً عند التحميل،
                # فتُكتب بطاقةٌ ولا تُعرض أبداً — ولا يُنبّه أحدٌ إلى ذلك.
                base = os.path.basename(path)
                if k in seen:
                    dupes.append((k, seen[k], base))
                seen[k] = base
                cards[k] = (base, v)

    if a.word:
        cards = {k: v for k, v in cards.items() if k.lower() == a.word.lower()}

    bad, clean = {}, 0
    for w, (src, c) in sorted(cards.items()):
        f = faults(w, c)
        if f:
            bad[w] = (src, f)
        else:
            clean += 1

    print(f"فُحصت {len(cards)} بطاقة · سليمة {clean} · فيها خلل {len(bad)}")
    if dupes:
        print(f"وكلماتٌ مكرّرة في ملفّين — الأخيرة تُلغي الأولى صامتةً: {len(dupes)}")
        for w, first, second in dupes:
            print(f"   {w}   [{first}] · [{second}]")
    print("=" * 62)
    for w, (src, f) in bad.items():
        print(f"\n  {w}   [{src}]")
        for x in f:
            print(f"      ✗ {x}")

    if a.strict and (bad or dupes):
        sys.exit(1)


if __name__ == "__main__":
    main()
