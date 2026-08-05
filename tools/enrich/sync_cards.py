#!/usr/bin/env python3
"""
تحديث بطاقات الإثراء — أمرٌ واحد على جهازك، بلا Colab.

كان تحديث كلمةٍ واحدة يكلّف ثماني خطوات: فتح Colab، ورفع القاعدة، ورفع
قائمة الكلمات، وتشغيل الترجمة، والتصدير، والتنزيل، وفكّ الضغط، والدفع.
وثمانِ خطواتٍ لإضافة كلمة تعني أن أحداً لن يضيف كلمة.

فالمصنع كلّه على القرص أصلاً: القاعدة ملفٌّ واحد، والكلمات في المستودع
المستنسَخ. لا يبقى إلا أن يُوصَل بينهما.

    python sync_cards.py            عرضٌ فقط — ماذا سيتغيّر
    python sync_cards.py --write    يكتب الشرائح
    python sync_cards.py --push     يكتب ويدفع إلى المستودع

الترجمة الآلية وحدها تبقى في Colab: نموذجها بالغيغابايتات وجهازك ضعيف.
والكلمة الجديدة تخرج بترجمة ويكاموس البشرية إن وُجدت، وتنتظر جولةً
دفعيّةً إن لم توجد — وهذا مذكورٌ في التقرير لا مخفيّ.
"""

import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DATA = os.path.abspath(os.path.join(REPO, "..", "tornado-data"))

DEFAULT_KB = os.path.join(REPO, "..", "tornado-kb.sqlite")
WORDS = os.path.join(DATA, "tornado-words.json")
ENRICH = os.path.join(DATA, "enrich")

sys.path.insert(0, HERE)


def find_kb(explicit=None) -> str:
    """يبحث عن القاعدة في المواضع المعتادة قبل أن يشكو."""
    for p in filter(None, [explicit, os.environ.get("TORNADO_KB"),
                           DEFAULT_KB,
                           os.path.join(DATA, "..", "tornado-kb.sqlite"),
                           os.path.expanduser("~/Downloads/tornado-kb.sqlite")]):
        if os.path.exists(p):
            return os.path.abspath(p)
    raise SystemExit(
        "لم أجد tornado-kb.sqlite.\n"
        f"ضعها في: {os.path.abspath(DEFAULT_KB)}\n"
        "أو مرّر --kb <مسار>، أو اضبط TORNADO_KB.")


def git(*args, cwd=DATA):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True,
                          text=True, encoding="utf-8").stdout.strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--kb")
    ap.add_argument("--words", default=WORDS)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--push", action="store_true")
    a = ap.parse_args()
    write = a.write or a.push

    kb = find_kb(a.kb)
    if not os.path.exists(a.words):
        raise SystemExit(f"لم أجد قائمة الكلمات: {a.words}\n"
                         "استنسخ tornado-data بجوار هذا المستودع.")

    os.environ["TORNADO_WORK"] = os.path.dirname(kb)
    import build_kb as K
    K.OUT_DB = kb

    print(f"القاعدة  : {kb} ({os.path.getsize(kb)/1e6:.0f} م.ب)")
    print(f"الكلمات  : {a.words}")
    print(f"الوجهة   : {ENRICH}\n")

    # البصمات قبل الكتابة — الفرق هو ما سينزّله التطبيق فعلاً
    old = {}
    idx_path = os.path.join(ENRICH, "index.json")
    if os.path.exists(idx_path):
        with open(idx_path, encoding="utf-8") as f:
            old = {k: v["hash"] for k, v in json.load(f)["shards"].items()}

    target = ENRICH if write else os.path.join(HERE, "out", "enrich")
    meta = K.export_shards(target, path=a.words)
    new = {k: v["hash"] for k, v in meta["shards"].items()}

    added = sorted(set(new) - set(old))
    changed = sorted(k for k in set(new) & set(old) if new[k] != old[k])
    gone = sorted(set(old) - set(new))

    print("─" * 58)
    print(f"  شرائح جديدة : {len(added)}  {' '.join(added[:12])}")
    print(f"  متغيّرة     : {len(changed)}  {' '.join(changed[:12])}")
    print(f"  محذوفة      : {len(gone)}  {' '.join(gone[:12])}")
    if not (added or changed or gone):
        print("  لا تغيير — التطبيق لن ينزّل شيئاً")
    print("─" * 58)

    # الكلمات التي لم تنل ترجمةً بعد — تُذكر ولا تُخفى
    db = __import__("sqlite3").connect(kb)
    pending = []
    for w in K.user_words(a.words):
        r = K.resolve_word(db, w)
        if r and not db.execute(
                "SELECT 1 FROM senses WHERE word=? AND ar IS NOT NULL"
                " AND ar<>'' LIMIT 1", (r,)).fetchone():
            pending.append(w)
    db.close()
    if pending:
        print(f"\n  ⏳ بلا ترجمة عربية ({len(pending)}): "
              f"{', '.join(pending[:12])}{' …' if len(pending) > 12 else ''}")
        print("     تُملأ بجولة translate في Colab متى شئت — "
              "البطاقات تعمل بدونها")

    if not write:
        print(f"\nعرضٌ فقط. كُتبت نسخة في {target}")
        print("للكتابة الحقيقية:  python sync_cards.py --write")
        return

    status = git("status", "--short", "enrich")
    if not status:
        print("\nلا جديد في المستودع — الشرائح مطابقة لما هو مدفوع")
        return
    print(f"\nتغييرات المستودع:\n{status[:600]}")

    if not a.push:
        print("\nللدفع:  python sync_cards.py --push")
        return

    git("add", "enrich")
    git("commit", "-m", f"enrich: {meta['words']} card shards")
    out = subprocess.run(["git", "push"], cwd=DATA, capture_output=True,
                         text=True, encoding="utf-8")
    print("✅ دُفع" if out.returncode == 0
          else f"❌ فشل الدفع:\n{out.stderr[:400]}")


if __name__ == "__main__":
    main()
