"""اختبار منطق build_kb محلياً — بلا Colab وبلا تنزيل."""
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ["TORNADO_WORK"] = HERE
sys.path.insert(0, r"C:\Users\amugl\OneDrive\Documents\GitHub\tornado\tools\enrich")

import build_kb as K                                            # noqa: E402

fails = []


def check(name, got, want):
    ok = got == want
    if not ok:
        fails.append(name)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        print(f"        got  = {got!r}")
        print(f"        want = {want!r}")


print(f"\nVERSION = {K.VERSION}\n")

# ── ١ ── تعريب النطق على أمثلة الجدول المعتمد ──────────────────────
print("[1] ar_pron — الجدول المعتمد")
for ipa, want in [
    ("/ˈskiɪŋ/",      "سكيينق"),
    ("/buːn/",        "بون"),
    ("/əˈbaɪd/",      "أبايد"),
    ("/kɒnˈfleɪt/",   "كونفليت"),
    ("/ˈfɪzɪsɪst/",   "فيزيسيست"),
    ("/ˈɪʃuː/",       "إشو"),
]:
    check(f"ar_pron({ipa})", K.ar_pron(ipa), want)

print("\n  — قيم للمعاينة (بلا توقّع مسبق) —")
for ipa in ("/ˈɛvɪdəntli/", "/əˈzuːm/", "/ˈlɛtəs/", "/pɹəˈvɒkətɪv/",
            "/ɑːˌtɪkjəˈleɪʃən/"):
    print(f"        {ipa:<22} -> {K.ar_pron(ipa)}")

# ── ٢ ── تطبيع حقول ويكاموس بالشكلين ──────────────────────────────
print("\n[2] _strs — نصوص أم قواميس")
check("قائمة نصوص", K._strs(["English idioms"], "name"), ["English idioms"])
check("قائمة قواميس",
      K._strs([{"name": "English idioms", "kind": "topical"}], "name"),
      ["English idioms"])
check("مختلطة",
      K._strs(["a", {"name": "b"}, {"x": 1}, None, ""], "name"), ["a", "b"])
check("None", K._strs(None, "name"), [])
check("أولوية المفاتيح",
      K._strs([{"text": "T", "word": "W"}], "word", "text"), ["W"])

# ── ٣ ── إزالة التكرار ────────────────────────────────────────────
print("\n[3] _dedupe")
rows = [("a", 1), ("A", 2), ("b", 3), ("a", 4)]
check("أوّل ظهور يفوز",
      K._dedupe(rows, lambda r: r[0].lower()), [("a", 1), ("b", 3)])

# ── ٤ ── قاعدة الأفعال المركّبة ────────────────────────────────────
print("\n[4] PARTICLES — فعل + حرف فقط")
for phrase, want in [("abide by", True), ("give up", True),
                     ("assume the position", False),
                     ("assume room temperature", False),
                     ("assume the mantle", False), ("look after", True)]:
    parts = phrase.split()
    got = len(parts) == 2 and parts[1] in K.PARTICLES
    check(f"{phrase!r} فعل مركّب؟", got, want)

# ── ٥ ── audit / repair على قاعدة اصطناعية ───────────────────────
print("\n[5] audit + repair — قاعدة اصطناعية بتكرار معلوم")
db_path = os.path.join(HERE, "tornado-kb.sqlite")
if os.path.exists(db_path):
    os.remove(db_path)
db = sqlite3.connect(db_path)
db.executescript(K.SCHEMA)

# كل صفّ مُدرَج مرّتين — يحاكي جولةً جرت مرّتين
for _ in range(2):
    db.execute("INSERT INTO senses(word,pos,idx,gloss,tags,source,ar) VALUES(?,?,?,?,?,?,?)",
               ("issue", "noun", 0, "a question", "", "wiktionary", "مَسْأَلَة"))
    db.execute("INSERT INTO senses(word,pos,idx,gloss,tags,source,ar) VALUES(?,?,?,?,?,?,?)",
               ("issue", "noun", 1, "an edition", "", "wordnet", None))
    db.execute("INSERT INTO examples(word,en,ar,source) VALUES('issue','We faced the issue.','واجهنا المشكلة','tatoeba')")
    db.execute("INSERT INTO idioms VALUES('issue','side issue','A minor topic.')")
    db.execute("INSERT INTO forms VALUES('assume','assumes','')")
    db.execute("INSERT INTO forms VALUES('assume','no-table-tags','')")
# أفعال مركّبة: صحيحة وزائفة
db.execute("INSERT INTO phrasal_verbs VALUES('abide','abide by','To accept.')")
db.execute("INSERT INTO phrasal_verbs VALUES('assume','assume the position','An idiom.')")
db.execute("INSERT INTO vocab VALUES('issue',1673,'3000','B1',NULL,NULL)")
db.commit()
db.close()

K.OUT_DB = db_path
before = K.audit()
check("senses مضاعَف 2.00x", before["senses"]["ratio"], 2.0)
check("forms مضاعَف 2.00x", before["forms"]["ratio"], 2.0)
check("فعل مركّب زائف مرصود", before["_bad_pv"], 1)

K.repair(apply=True)
after = K.audit()
check("senses بعد الإصلاح 1.00x", after["senses"]["ratio"], 1.0)
check("examples بعد الإصلاح 1.00x", after["examples"]["ratio"], 1.0)
check("idioms بعد الإصلاح 1.00x", after["idioms"]["ratio"], 1.0)
check("لا فعل مركّب زائف", after["_bad_pv"], 0)

db = sqlite3.connect(db_path)
check("abide by باقٍ",
      db.execute("SELECT COUNT(*) FROM phrasal_verbs WHERE phrase='abide by'"
                 ).fetchone()[0], 1)
check("assume the position محذوف",
      db.execute("SELECT COUNT(*) FROM phrasal_verbs"
                 " WHERE phrase='assume the position'").fetchone()[0], 0)
check("no-table-tags محذوف",
      db.execute("SELECT COUNT(*) FROM forms WHERE form='no-table-tags'"
                 ).fetchone()[0], 0)
check("assumes باقٍ",
      db.execute("SELECT COUNT(*) FROM forms WHERE form='assumes'"
                 ).fetchone()[0], 1)

# ── ٦ ── بناء بطاقة ───────────────────────────────────────────────
print("\n[6] build_card")
card = K.build_card(db, "issue")
check("لا معنى مكرّر",
      len({m["en"] for m in card["meanings"]}), len(card["meanings"]))
check("cefr", card["cefr"], "B1")
check("النطق العربي مشتقّ", bool(card["arabicPron"]) or card["ipa"] == {}, True)
check("الترجمة العربية للمعنى وصلت",
      next((m["ar"] for m in card["meanings"] if m["en"] == "a question"), None),
      "مَسْأَلَة")
check("معنى WordNet بلا عربية",
      next((m["ar"] for m in card["meanings"] if m["en"] == "an edition"), "X"),
      None)
check("المعنى المترجَم يسبق", card["meanings"][0]["en"], "a question")
db.close()

# ── ٦٫٥ ── ردّ الصيغة المصرَّفة إلى مدخلها ─────────────────────────
print("\n[6.5] resolve_word")
db = sqlite3.connect(db_path)
db.execute("INSERT INTO vocab VALUES('glacier',7000,NULL,NULL,NULL,NULL)")
db.execute("INSERT INTO vocab VALUES('forage',9000,NULL,NULL,NULL,NULL)")
db.execute("INSERT INTO forms VALUES('glacier','glaciers','plural')")
db.commit()
check("مطابقة مباشرة", K.resolve_word(db, "issue"), "issue")
check("من جدول الصيغ", K.resolve_word(db, "glaciers"), "glacier")
check("قاعدة ing", K.resolve_word(db, "foraging"), "forage")
check("غير موجودة", K.resolve_word(db, "zzzqqq"), None)
check("فارغة", K.resolve_word(db, ""), None)
db.close()

# ── ٦٫٧ ── تمييز المدخل الحقيقي من الصيغة ─────────────────────────
print("\n[6.7] _is_lemma")
real = {"senses": [{"glosses": ["A person who studies physics."]}]}
form = {"senses": [{"glosses": ["plural of glacier"],
                    "form_of": [{"word": "glacier"}]}]}
check("مدخل حقيقي", K._is_lemma(real, "physicist"), True)
check("صيغة مصرَّفة", K._is_lemma(form, "glaciers"), False)
check("رمز غير أبجدي", K._is_lemma(real, "a1b2!"), False)
check("حرف واحد", K._is_lemma(real, "x"), False)
check("بشرطة", K._is_lemma(real, "well-known"), True)
check("بلا معنى", K._is_lemma({"senses": []}, "zzz"), False)

# ── ٦٫٨ ── الترجمة الآلية: تملأ الفراغ ولا تمسّ البشري ────────────
print("\n[6.8] stage_translate")
db = sqlite3.connect(db_path)
db.execute("INSERT INTO senses(word,pos,idx,gloss,tags,source,ar,ar_src) VALUES(?,?,?,?,?,?,?,?)",
           ("issue", "noun", 9, "a thing to translate", "", "wiktionary",
            None, None))
db.execute("INSERT INTO examples(word,en,ar,source,ar_src) VALUES(?,?,?,?,?)",
           ("issue", "An untranslated line.", None, "wiktionary", None))
db.commit()

before_human = db.execute(
    "SELECT ar FROM senses WHERE gloss='a question'").fetchone()[0]


def fake_translate(texts):
    """مترجمٌ محقون — الاختبار لا يُنزّل ٣٠٠ م.ب من الأوزان."""
    return ["[ترجمة] " + t[:20] for t in texts]


K.TRANSLATE_SCOPE = "all"
K.stage_translate(db, translate=fake_translate)

check("المعنى الناقص تُرجم",
      (db.execute("SELECT ar FROM senses WHERE gloss='a thing to translate'"
                  ).fetchone()[0] or "").startswith("[ترجمة]"), True)
check("موسوم آلياً",
      db.execute("SELECT ar_src FROM senses WHERE gloss='a thing to translate'"
                 ).fetchone()[0], "mt")
check("الترجمة البشرية لم تُمسّ",
      db.execute("SELECT ar FROM senses WHERE gloss='a question'"
                 ).fetchone()[0], before_human)
check("مصدرها ليس mt",
      db.execute("SELECT ar_src FROM senses WHERE gloss='a question'"
                 ).fetchone()[0], None)
check("المثال الناقص تُرجم",
      (db.execute("SELECT ar FROM examples WHERE en='An untranslated line.'"
                  ).fetchone()[0] or "").startswith("[ترجمة]"), True)
db.close()

# ── ٦٫٩ ── ترشيح الاقتباسات المهجورة ──────────────────────────────
print("\n[6.9] ARCHAIC — الاقتباس القديم يُرفض والحديث يُقبل")
for txt, want_reject in [
    ("VVe will be Kings within our ſelues", True),
    ("Neuer neuer: shee would alwayes say", True),
    ("thou dost not know the faith", True),
    ("said M[aster] Shallow", True),
    # الحديثة تُقبل — و«ſ» تطابق «s» تحت re.I لولا (?-i:)
    ("The old oak tree abides the wind.", False),
    ("Sami should assume responsibility.", False),
    ("Under the best test conditions.", False),
    ("It suddenly became a national issue.", False),
]:
    check(f"{'رفض' if want_reject else 'قبول'}: {txt[:34]}",
          bool(K.ARCHAIC.search(txt)), want_reject)

# ── ٦٫٩٥ ── تقدير CEFR من الرتبة ──────────────────────────────────
print("\n[6.95] est_cefr — نفس سلّم التطبيق")
for rank, want in [(1, "A1"), (1000, "A1"), (1001, "A2"), (2500, "A2"),
                   (2501, "B1"), (5000, "B1"), (5001, "B2"),
                   (10000, "B2"), (10001, "C1"), (99999, "C1"),
                   (None, ""), (0, "")]:
    check(f"rank {rank}", K.est_cefr(rank), want)

# ── ٧ ── repair عديم الأثر عند التكرار ────────────────────────────
print("\n[7] repair مرّتين لا يغيّر شيئاً")
K.repair(apply=True)
again = K.audit()
check("ما زال 1.00x", again["senses"]["ratio"], 1.0)

print("\n" + "=" * 56)
print(f"  النتيجة: {'كل الاختبارات نجحت' if not fails else str(len(fails)) + ' فشل'}")
for f in fails:
    print(f"    - {f}")
print("=" * 56)
sys.exit(1 if fails else 0)
