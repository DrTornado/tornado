#!/usr/bin/env python3
"""
البطاقة النموذج في المواصفة يجب أن تطابق البطاقة الحقيقية في المكتبة.

المواصفة تحمل `punctuality` كاملةً ليقتدي بها الكاتب بلا أن يفتح
`curated/`. فهي نسخةٌ ثانية من بطاقةٍ قائمة — ونسختان تتدرّجان.

وقد تدرّجتا فعلاً: صُحّح النبر في إحداهما وبقي الخطأ في الأخرى، فكانت
البطاقة التي يُقتدى بها تُعلّم الخطأ الذي أُصلح. وكلُّ بطاقةٍ قادمة تنسخه.

    python3 spec_sync.py --spec .github/card-spec.md --curated curated

يخرج بصفرٍ إن تطابقتا، وبواحدٍ ومعه أوّل فرقٍ إن اختلفتا.
"""
import argparse
import glob
import io
import json
import re
import sys

FENCE = re.compile(r"```json\s*\n(.*?)\n```", re.S)


def spec_card(path: str):
    """أوّل كتلة JSON في المواصفة — وهي البطاقة النموذج."""
    text = io.open(path, encoding="utf-8").read()
    hit = FENCE.search(text)
    if not hit:
        raise SystemExit("لا كتلة JSON في المواصفة — أين البطاقة النموذج؟")
    block = json.loads(hit.group(1))
    if len(block) != 1:
        raise SystemExit(f"البطاقة النموذج فيها {len(block)} مدخلاً لا واحداً")
    word, card = next(iter(block.items()))
    return word, card


def library_card(curated: str, word: str):
    for f in sorted(glob.glob(f"{curated}/*.json")):
        data = json.load(io.open(f, encoding="utf-8"))
        if word in data:
            return f, data[word]
    return None, None


def first_difference(a, b, path="") -> str:
    """أوّل موضعٍ تختلف فيه النسختان، مكتوباً بمسارٍ يُقرأ."""
    if type(a) is not type(b):
        return f"{path or '.'}: نوعان مختلفان"
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a:
                return f"{path}.{k}: في المكتبة ولا وجود له في المواصفة"
            if k not in b:
                return f"{path}.{k}: في المواصفة ولا وجود له في المكتبة"
            d = first_difference(a[k], b[k], f"{path}.{k}")
            if d:
                return d
        return ""
    if isinstance(a, list):
        if len(a) != len(b):
            return f"{path}: المواصفة {len(a)} والمكتبة {len(b)}"
        for i, (x, y) in enumerate(zip(a, b)):
            d = first_difference(x, y, f"{path}[{i}]")
            if d:
                return d
        return ""
    if a != b:
        return f"{path}:\n    المواصفة: {a}\n    المكتبة:  {b}"
    return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", default=".github/card-spec.md")
    ap.add_argument("--curated", default="curated")
    a = ap.parse_args()

    word, model = spec_card(a.spec)
    path, real = library_card(a.curated, word)
    if real is None:
        print(f"البطاقة النموذج «{word}» ليست في المكتبة — إحداهما تغيّر اسمها")
        return 1

    diff = first_difference(model, real)
    if diff:
        print(f"البطاقة النموذج «{word}» تخالف {path}:\n  {diff}")
        print("\nأصلح إحداهما لتطابق الأخرى — النسخة التي يُقتدى بها لا تُترك خاطئة.")
        return 1

    print(f"البطاقة النموذج «{word}» تطابق {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
