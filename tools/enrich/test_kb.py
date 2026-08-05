"""اختبار منطق build_kb محلياً — بلا Colab وبلا تنزيل."""
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ["TORNADO_WORK"] = os.environ.get("TORNADO_WORK", HERE)
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
    db.execute("INSERT INTO senses VALUES('issue','noun',0,'a question','','wordnet')")
    db.execute("INSERT INTO senses VALUES('issue','noun',1,'an edition','','wordnet')")
    db.execute("INSERT INTO examples VALUES('issue','We faced the issue.','واجهنا المشكلة','tatoeba')")
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
db.close()

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
