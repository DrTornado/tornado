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


def _export_pending(K, words_path: str, out: str) -> None:
    """
    يُصدِّر النصوص التي تنقصها ترجمة — لا القاعدة.

    الرحلة إلى Colab كانت تكلّف رفع ١٢٣ ميغابايت وتنزيلها ثانيةً لأجل
    ثلاثين نصاً. والنصوص نفسها بضعة كيلوبايتات: هي وحدها ما يحتاجه
    النموذج، وهي وحدها ما يعود.
    """
    import sqlite3
    db = sqlite3.connect(K.OUT_DB)
    heads = {r for r in (K.resolve_word(db, w)
                         for w in K.user_words(words_path)) if r}
    db.execute("DROP TABLE IF EXISTS temp.mine")
    db.execute("CREATE TEMP TABLE mine(word TEXT PRIMARY KEY)")
    db.executemany("INSERT OR IGNORE INTO mine VALUES(?)",
                   [(w,) for w in heads])

    jobs = []
    for tbl, col in (("senses", "gloss"), ("examples", "en")):
        jobs += [{"t": tbl, "id": rid, "en": txt} for rid, txt in db.execute(
            f"SELECT rowid, {col} FROM {tbl}"
            f" WHERE (ar IS NULL OR ar='') AND {col} IS NOT NULL"
            f" AND length({col}) BETWEEN 3 AND 400"
            f" AND word IN (SELECT word FROM mine)")]
    db.close()

    with open(out, "w", encoding="utf-8") as f:
        json.dump({"kb": os.path.basename(K.OUT_DB), "jobs": jobs},
                  f, ensure_ascii=False)
    size = os.path.getsize(out)
    print(f"  {len(jobs):,} نصاً ينتظر ترجمة → {out} ({size/1024:.0f} ك.ب)")
    if not jobs:
        print("  لا شيء ينتظر — كل شيء مترجَم")
        return
    print(f"\n  ارفع هذا الملف إلى Colab وشغّل خلية الترجمة،"
          f"\n  ثم:  python sync_cards.py --apply translated.json --push")


def _apply_translations(K, path: str) -> None:
    """يُدخل الترجمات العائدة — ولا يمسّ ما ترجمه إنسان."""
    import sqlite3
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    db = sqlite3.connect(K.OUT_DB)
    done = skipped = 0
    for j in data.get("jobs", []):
        ar = (j.get("ar") or "").strip()
        if not ar or not K.ARABIC_TEXT.search(ar):
            skipped += 1
            continue
        tbl = j["t"] if j.get("t") in ("senses", "examples") else None
        if not tbl:
            skipped += 1
            continue
        # الشرط يحمي البشري: لا يُكتب إلا فوق الفارغ
        done += db.execute(
            f"UPDATE {tbl} SET ar=?, ar_src='mt'"
            f" WHERE rowid=? AND (ar IS NULL OR ar='')",
            (ar, j["id"])).rowcount
    db.commit()
    db.close()
    print(f"  أُدخلت {done:,} ترجمة"
          + (f" · تُخطّيت {skipped:,} (فارغة أو ليست عربية)"
             if skipped else "") + "\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--kb")
    ap.add_argument("--words", default=WORDS)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--pending", metavar="FILE", nargs="?",
                    const=os.path.join(HERE, "pending.json"),
                    help="يُصدِّر النصوص التي تنقصها ترجمة — بضعة كيلوبايتات")
    ap.add_argument("--apply", metavar="FILE",
                    help="يُدخل الترجمات العائدة من Colab")
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

    if a.pending:
        _export_pending(K, a.words, a.pending)
        return
    if a.apply:
        _apply_translations(K, a.apply)
        # ثم يمضي إلى التصدير بالترجمات الجديدة

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
