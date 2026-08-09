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


# كل قسمٍ في البطاقة إلزامي. الكلمة التي ينقصها قسمٌ ليست منتهية.
REQUIRED = ("ipa", "arabicPron", "pos", "meanings", "inflections",
            "derivatives", "synonyms", "antonyms", "examples",
            "collocations", "differences", "usageNotes")


def curated_words(path: str = CURATED) -> tuple:
    """
    يفرّق بين المكتمل والناقص.

    كانت الكلمة تُعدّ منتهيةً لمجرّد وجود اسمها في الملف، فبقيت بطاقاتٌ
    بلا أضداد ولا متلازمات ولا فروق — ثم طبع العارض تحتها «لا توجد معلومة
    موثوقة»، وهو إعلانُ كسلٍ في ثوب أمانة. فما ينقصه قسمٌ يعود إلى الطابور.
    """
    done, partial = set(), {}
    for f_path in sorted(glob.glob(os.path.join(path, "*.json"))):
        try:
            with open(f_path, encoding="utf-8") as f:
                raw = json.load(f)
        except Exception:                                        # noqa: BLE001
            continue
        for k, v in raw.items():
            if k.startswith("_") or not isinstance(v, dict):
                continue
            gaps = [r for r in REQUIRED if not v.get(r)]
            if gaps:
                partial[k.lower()] = (os.path.basename(f_path), gaps)
            else:
                done.add(k.lower())
    return done, partial


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
    ap.add_argument("--incomplete", action="store_true",
                    help="الناقصة وحدها، ومعها ما ينقصها")
    a = ap.parse_args()

    done, partial = curated_words(a.curated)
    all_words = library(a.words)

    if a.done:
        for w in sorted(done):
            print(w)
        return

    if a.incomplete:
        for w, (src, gaps) in sorted(partial.items()):
            print(f"{w}  [{src}]  ينقصها: {' · '.join(gaps)}")
        if not partial:
            print("لا ناقصة — كل المكتوب مكتمل")
        return

    # الناقصة أوّلاً: إكمالُ بطاقةٍ بدأناها أولى من بدء أخرى
    todo = ([w for w in all_words if w.lower() in partial]
            + [w for w in all_words
               if w.lower() not in done and w.lower() not in partial])

    print(f"# المكتبة {len(all_words)} · مكتملة {len(done)} "
          f"· ناقصة {len(partial)} · لم تُكتب {len(todo)-len(partial)}")
    for w in (todo[:a.limit] if a.limit else todo):
        print(w)


if __name__ == "__main__":
    main()
