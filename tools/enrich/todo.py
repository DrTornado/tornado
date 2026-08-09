#!/usr/bin/env python3
"""
يقول ما بقي: أي كلماتٍ في المكتبة لم تُكتب بطاقتها بيد بعد.

غرضه أن تعرف المهمّة المجدولة من أين تبدأ بلا أن تقرأ مئةً وستّين بطاقة،
وألّا تضيّع وقتاً في كلمةٍ فُرغ منها. ولذلك يطبع قائمةً نظيفة فقط.

    python todo.py                 ما بقي
    python todo.py --limit 12      أوّل اثنتي عشرة
    python todo.py --done          ما اكتمل
"""

import argparse
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
CURATED = os.path.join(HERE, "curated")
# tools/enrich → tools → tornado → GitHub → tornado-data
_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
WORDS = os.path.join(_ROOT, "tornado-data", "tornado-words.json")


def curated_words(path: str = CURATED) -> set:
    done = set()
    for f_path in sorted(glob.glob(os.path.join(path, "*.json"))):
        try:
            with open(f_path, encoding="utf-8") as f:
                raw = json.load(f)
        except Exception:                                        # noqa: BLE001
            continue
        done |= {k.lower() for k in raw
                 if not k.startswith("_") and isinstance(raw[k], dict)}
    return done


def library(path: str = WORDS) -> list:
    """
    كلمات المستخدم بترتيب إضافتها.

    الملف قد يحمل حقلاً فاسداً — `"last": 0null` — من نسخةٍ قديمة من
    التطبيق، فيُرفض التحليل كلّه. نصلحه في الذاكرة ولا نكتب فوق ملفه.
    """
    with open(path, encoding="utf-8") as f:
        raw = f.read()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        import re
        data = json.loads(re.sub(r'("last"\s*:\s*)0(?=(null|"))', r"\1", raw))
    out, seen = [], set()
    for w in data.get("words") or []:
        t = (w.get("word") or "").strip()
        if t and t.lower() not in seen:
            seen.add(t.lower())
            out.append(t)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--words", default=WORDS)
    ap.add_argument("--curated", default=CURATED)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--done", action="store_true")
    a = ap.parse_args()

    done = curated_words(a.curated)
    all_words = library(a.words)
    todo = [w for w in all_words if w.lower() not in done]

    if a.done:
        for w in sorted(done):
            print(w)
        return

    print(f"# المكتبة {len(all_words)} · مراجَعة {len(all_words)-len(todo)} "
          f"· باقٍ {len(todo)}")
    for w in (todo[:a.limit] if a.limit else todo):
        print(w)


if __name__ == "__main__":
    main()
