#!/usr/bin/env python3
"""
يختار البطاقات التي طال عهدها بالمراجعة.

المدقّق يفحص البنية: الحدود الدنيا، ولغةَ كل خانة، ونبرَ النطق، وتصنيفَ
أوكسفورد. ولا يفحص **المعنى**: أترجمةُ السطر صحيحة؟ أالمثال طبيعيّ؟
أالفرق المذكور بين كلمتين فرقٌ حقيقيّ؟ ذلك لا يكشفه إلا قارئ.

وقراءةُ المكتبة مرّةً واحدة حلٌّ يشيخ في اليوم التالي — والبطاقة التالية
تُكتب بلا مراجعة. فالمراجعة دورة: كلَّ يومٍ حفنةٌ من أقدمها عهداً، فتُمسح
المكتبة كاملةً في شهر، ثم تبدأ الدورة من جديد. والبطاقة الجديدة تدخل
الدور تلقائياً لأنها بلا تاريخ، فتسبق كلَّ ما رُوجع.

    python3 review_pick.py --curated curated --state review-state.json --limit 6

يطبع الكلمات المختارة، سطراً لكل كلمة. ولا يكتب الحالة — يكتبها
`--commit` بعد نجاح المراجعة، فلا تُحسب مراجعةً جولةٌ سقطت.
"""
import argparse
import datetime
import glob
import io
import json
import os
import sys


def load_state(path: str) -> dict:
    if not os.path.exists(path):
        return {"reviewed": {}}
    try:
        with io.open(path, encoding="utf-8") as f:
            s = json.load(f)
    except Exception:                                        # noqa: BLE001
        return {"reviewed": {}}
    s.setdefault("reviewed", {})
    return s


def all_words(curated: str) -> list:
    out = []
    for path in sorted(glob.glob(os.path.join(curated, "*.json"))):
        try:
            with io.open(path, encoding="utf-8") as f:
                data = json.load(f)
        except Exception:                                    # noqa: BLE001
            continue
        for k in data:
            if not k.startswith("_"):
                out.append(k)
    return out


def pick(curated: str, state: dict, limit: int) -> list:
    """الأقدم عهداً أوّلاً، وما لم يُراجَع قطّ يسبق الجميع."""
    seen = state.get("reviewed", {})
    words = all_words(curated)
    # المفتاح: (تاريخ المراجعة أو الفراغ، الكلمة) — والفراغ يسبق أي تاريخ
    words.sort(key=lambda w: (seen.get(w.lower(), ""), w.lower()))
    return words[:limit] if limit else words


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--curated", default="curated")
    ap.add_argument("--state", default="review-state.json")
    ap.add_argument("--limit", type=int, default=6)
    ap.add_argument("--commit", nargs="*",
                    help="سجّل هذه الكلمات مراجَعةً اليوم واخرج")
    ap.add_argument("--stats", action="store_true")
    a = ap.parse_args()

    state = load_state(a.state)

    if a.commit is not None:
        today = datetime.date.today().isoformat()
        for w in a.commit:
            state["reviewed"][w.lower()] = today
        state["updated"] = today
        with io.open(a.state, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write("\n")
        print(f"سُجّلت {len(a.commit)} كلمةً مراجَعةً في {today}")
        return 0

    words = all_words(a.curated)
    seen = state.get("reviewed", {})
    done = sum(1 for w in words if w.lower() in seen)

    if a.stats:
        print(f"البطاقات {len(words)} · رُوجعت {done} · تنتظر {len(words)-done}")
        return 0

    chosen = pick(a.curated, state, a.limit)
    print(f"# البطاقات {len(words)} · رُوجعت {done} · "
          f"تنتظر {len(words)-done} · هذه الجولة {len(chosen)}")
    for w in chosen:
        print(w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
