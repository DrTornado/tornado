#!/usr/bin/env python3
"""
يرفع رقم البناء كلّما تغيّر `index.html` — تلقائياً، لا بالتذكّر.

الصفحة تتحقّق من نفسها: تسأل `version.json` عن آخر بناء، فإن خالف ما
تشغّله مسحت قوقعتها وأعادت التحميل. آليةٌ سليمة، وقد عُطّلت بأتفه سبب:
دُفعت ستّةُ تغييرات في `index.html` والرقم ثابتٌ على ١١٥. فبقي المتصفّح
على شيفرةٍ قديمة، وظهرت أعطالٌ كثيرة سببها واحد — والموقع الحيّ سليم.

ولذلك لا يُترك الرقم لانتباه أحد. هذا السكربت يوصله بمحتوى الملف: بصمةٌ
تتغيّر بتغيّره، فلا رفعَ بلا داعٍ ولا نسيانَ حين يجب.

يُستدعى من خطّاف `pre-commit`، فيجري بلا أن يُطلب.

    python tools/bump_build.py           يرفع إن لزم
    python tools/bump_build.py --check   يخبر فقط (يخرج بخطأ إن لزم الرفع)
"""

import argparse
import hashlib
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = os.path.join(HERE, "index.html")
VERSION = os.path.join(HERE, "version.json")
SW = os.path.join(HERE, "sw.js")

BUILD_RE = re.compile(r"^(const BUILD = )(\d+)(;)", re.M)
CACHE_RE = re.compile(r"^(const CACHE = 'tornado-v)(\d+)(';)", re.M)


def page_fingerprint(text: str) -> str:
    """
    بصمة الصفحة بلا سطر الرقم نفسه.

    لولا استثناؤه لَغيّر رفعُ الرقم البصمةَ فاستدعى رفعاً آخر — دورةٌ لا
    تنتهي، كلُّ التزامٍ يُحدث التزاماً.
    """
    body = BUILD_RE.sub(r"\g<1>0\g<3>", text)
    return hashlib.sha1(body.encode("utf-8")).hexdigest()[:16]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()

    page = io.open(PAGE, encoding="utf-8").read()
    m = BUILD_RE.search(page)
    if not m:
        print("لم أجد «const BUILD» في index.html", file=sys.stderr)
        sys.exit(2)
    build = int(m.group(2))

    stamp_path = os.path.join(HERE, "tools", ".build-fingerprint")
    fresh = page_fingerprint(page)
    old = ""
    if os.path.exists(stamp_path):
        old = io.open(stamp_path, encoding="utf-8").read().strip()

    if fresh == old:
        print(f"البناء {build} — لا تغيير في الصفحة")
        return

    if a.check:
        print(f"index.html تغيّر والبناء ما زال {build} — يلزم الرفع",
              file=sys.stderr)
        sys.exit(1)

    nxt = build + 1
    io.open(PAGE, "w", encoding="utf-8").write(
        BUILD_RE.sub(rf"\g<1>{nxt}\g<3>", page, count=1))
    io.open(VERSION, "w", encoding="utf-8").write(
        json.dumps({"build": nxt}) + "\n")

    # اسم قوقعة عامل الخدمة يحمل الرقم: تغييره يُسقط القديمة عند التفعيل
    if os.path.exists(SW):
        sw = io.open(SW, encoding="utf-8").read()
        io.open(SW, "w", encoding="utf-8").write(
            CACHE_RE.sub(rf"\g<1>{nxt}\g<3>", sw, count=1))

    io.open(stamp_path, "w", encoding="utf-8").write(
        page_fingerprint(BUILD_RE.sub(rf"\g<1>{nxt}\g<3>", page, count=1)))
    print(f"البناء {build} ← {nxt}  ·  version.json و sw.js معه")


if __name__ == "__main__":
    main()
