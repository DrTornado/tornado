#!/usr/bin/env python3
"""
لا تُمَسّ بطاقةٌ خارج ما أُذن به.

المراجع يعدّل بطاقاتٍ قائمة — وهي العملية التي محت ثماني عشرة بطاقةً
مكتوبةً بيد في جولةٍ سابقة. والمدقّق لا يمنعها: بطاقةٌ حُذفت لا تخالف
قاعدةً، وبطاقةٌ أُعيدت كتابتها كاملةً قد تجتاز البنود السبعة عشر كلَّها.

فهذا حارسٌ من نوعٍ آخر: يقارن المكتبة قبل وبعد، ويسقط إن مُسّ ما لم
يُؤذن بمسّه — كلمةٌ اختفت، أو كلمةٌ ظهرت، أو بطاقةٌ تغيّرت وليست في
قائمة المأذون.

    git stash ... أو نسخة قبلية في مجلد
    python3 scope_guard.py --before /tmp/before --after curated \\
        --allow abide tenacity

يخرج بصفرٍ إن كان كلُّ تغييرٍ مأذوناً، وبواحدٍ ومعه ما وقع.
"""
import argparse
import glob
import io
import json
import os
import sys


def read_library(path: str) -> dict:
    """كلُّ البطاقات من كل الملفات في قاموسٍ واحد: الكلمة ⇦ نصُّها المعياريّ."""
    out = {}
    for f in sorted(glob.glob(os.path.join(path, "*.json"))):
        try:
            with io.open(f, encoding="utf-8") as fh:
                data = json.load(fh)
        except Exception as e:                               # noqa: BLE001
            raise SystemExit(f"تعذّر قراءة {f}: {e}")
        for k, v in data.items():
            if k.startswith("_"):
                continue
            # نصٌّ معياريّ: ترتيب المفاتيح لا يُعدّ تغييراً، والمحتوى يُعدّ
            out[k.lower()] = json.dumps(v, ensure_ascii=False, sort_keys=True)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True, help="مجلد البطاقات قبل العمل")
    ap.add_argument("--after", required=True, help="مجلد البطاقات بعده")
    ap.add_argument("--allow", nargs="*", default=[],
                    help="الكلمات المأذون بتغييرها")
    ap.add_argument("--allow-new", action="store_true",
                    help="اسمح بظهور كلماتٍ جديدة (لكاتب البطاقات لا للمراجع)")
    a = ap.parse_args()

    before = read_library(a.before)
    after = read_library(a.after)
    allow = {w.lower() for w in a.allow}

    faults = []

    for w in sorted(set(before) - set(after)):
        faults.append(f"اختفت بطاقة: {w}")

    for w in sorted(set(after) - set(before)):
        if not a.allow_new and w not in allow:
            faults.append(f"ظهرت بطاقة لم تُطلب: {w}")

    for w in sorted(set(before) & set(after)):
        if before[w] != after[w] and w not in allow:
            faults.append(f"تغيّرت بطاقة خارج الإذن: {w}")

    touched = sorted(w for w in (set(before) & set(after))
                     if before[w] != after[w])
    print(f"البطاقات قبل {len(before)} · بعد {len(after)} · "
          f"مُسّت {len(touched)}")
    if touched:
        print("المُسّت: " + " · ".join(touched))

    if faults:
        print("\nخارج الإذن:")
        for f in faults:
            print(f"  ✗ {f}")
        print("\nلا يُدفع شيء — المراجعة تصحّح ما أُذن به وحده.")
        return 1

    print("كلُّ تغييرٍ داخل الإذن")
    return 0


if __name__ == "__main__":
    sys.exit(main())
