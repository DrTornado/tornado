#!/usr/bin/env python3
"""
يبني شرائح البطاقات من التنقيح المكتوب بيد — بلا قاعدة معرفة وبلا ترجمة آلية.

لماذا استُبدل المسار القديم؟ لأنه بنى بطاقةً من نصف مليون مدخل ثم ترجمها
بنموذجٍ صغير، والتعريف المعجمي أسوأ نصٍّ يُعطى لمترجم آلي: مقتضبٌ ناقص
الجملة غريب السجلّ. فأنتج «تعبير عن غضب شخص عانى من عكس» و«فاضل فاضل
فاضل» — قِسته: ٩١٪ من المعاني ترجمتها آلية، وصاحب المشروع فقد ثقته
بالبطاقات كلّها. وثقةٌ ضائعة لا تُستعاد بأنبوبٍ أنظف، بل بمحتوىً صحيح.

فصار المصدر `curated.json`: بطاقاتٌ مكتوبة بيدٍ بصيغةٍ واحدة متّفق عليها.
والكلمة التي لم تُكتب بعد تبقى على بطاقتها القديمة موسومةً بأنها آلية،
فيعرف القارئ ما يثق به وما ينتظره.

    python curate.py            عرضٌ فقط
    python curate.py --write    يكتب الشرائح ويعيد بناء الفهرس
"""

import argparse
import glob
import hashlib
import json
import os
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CURATED = os.path.join(HERE, "curated")   # مجلّد دفعات

CARD_SCHEMA = 1

# الحقول التي تنتقل من البطاقة المنقّحة إلى الشريحة، وأشكالها.
# نصوصٌ مفردة، أو قوائم نصوص، أو قوائم أزواج {en, ar}.
TEXT = ("word", "arabicPron", "oxford", "cefr", "cefrEst")
LISTS = ("pos", "inflections", "register")
# ملاحظاتُ الاستعمال والنطق أزواجٌ أيضاً: كانتا نصّاً مفرداً يجمع
# اللغتين في سطرٍ واحد — «الصفة تأخذ with مع الشخص وabout مع الموقف»
# — فيقفز البصر بين اتّجاهين مرّتين في السطر الواحد.
PAIRS = ("derivatives", "synonyms", "antonyms", "examples",
         "collocations", "differences", "grammarPatterns",
         "usageNotes", "pronunciationNote")


def load_curated(path: str = CURATED) -> dict:
    """
    يقبل ملفاً واحداً أو مجلّد دفعات.

    والدفعات أصحّ: مئةٌ وواحدٌ وستّون بطاقة في ملفٍ واحد تعني إعادة كتابته
    كلّه في كل مرّة، وخطأً واحداً في الصياغة يُسقط الجميع. فكلُّ دفعةٍ ملفٌّ
    مستقلّ، وما يسقط منها يسقط وحده.
    """
    files = []
    if os.path.isdir(path):
        files = sorted(glob.glob(os.path.join(path, "*.json")))
    elif os.path.exists(path):
        files = [path]

    out = {}
    for f_path in files:
        try:
            with open(f_path, encoding="utf-8") as f:
                raw = json.load(f)
        except Exception as e:                                   # noqa: BLE001
            print(f"  ⚠ {os.path.basename(f_path)} معطوب ({e}) — أتخطّاه",
                  flush=True)
            continue
        for k, v in raw.items():
            if not k.startswith("_") and isinstance(v, dict):
                out[k.lower()] = v
    return out


# حقول الزوج الواحد، وكلٌّ منها سطرٌ مستقلّ عند العرض.
#
# كان السطر يجمع اللغتين: «يخالف — ضدّ abide by، لا ضدّ يطيق». فيقفز
# البصر بين اتّجاهين في السطر الواحد، وتصير القراءة مُتعِبة. والفصل يجعل
# كلَّ سطرٍ بلغةٍ واحدة واتّجاهٍ واحد:
#
#     violate            ← en   إنجليزيّ خالص
#     يخالف              ← ar   عربيّ خالص
#     ضدّ «abide by»      ← note توضيحٌ صغير
#     He violated the rule.   ← ex   مثالٌ إن لزم
#     خالف القاعدة.           ← exAr
PAIR_FIELDS = ("en", "ar", "note", "ex", "exAr")


def _pairs(val) -> list:
    """يقبل النصّ المفرد والزوج — ويخرج الكلّ بالشكل نفسه."""
    out = []
    for x in val or []:
        if isinstance(x, str):
            out.append({"en": x, "ar": ""})
        elif isinstance(x, dict) and (x.get("en") or x.get("ar")):
            out.append({k: x[k] for k in PAIR_FIELDS if x.get(k)})
    return out


def build_card(word: str, c: dict) -> dict:
    """بطاقةٌ واحدة بالصيغة التي يقرأها التطبيقان."""
    card = {"word": c.get("word", word), "v": CARD_SCHEMA, "curated": True}

    ipa = c.get("ipa")
    if isinstance(ipa, str):
        card["ipa"] = {"gen": ipa}
    elif isinstance(ipa, dict):
        card["ipa"] = ipa

    for k in TEXT:
        if k != "word" and c.get(k):
            card[k] = c[k]
    for k in LISTS:
        if c.get(k):
            card[k] = [str(x) for x in c[k] if str(x).strip()]

    card["meanings"] = [
        {"en": m.get("en", ""), "ar": m.get("ar", ""),
         "pos": m.get("pos"), "src": "curated", "arSrc": None}
        for m in c.get("meanings") or []
        if m.get("en") or m.get("ar")
    ]
    for k in PAIRS:
        v = _pairs(c.get(k))
        if v:
            card[k] = v

    # العبارات: الفعل المركّب والتعبير — عبارةٌ وشرحها
    for k in ("phrasalVerbs", "idioms"):
        v = [{"phrase": p.get("phrase", ""), "gloss": p.get("gloss", "")}
             for p in c.get(k) or [] if p.get("phrase")]
        if v:
            card[k] = v

    return card


# الأقسام التي يجب أن تحملها كل بطاقة مكتوبة بيد. لا استثناء.
REQUIRED = ("ipa", "arabicPron", "pos", "meanings", "inflections",
            "derivatives", "synonyms", "antonyms", "examples",
            "collocations", "differences", "usageNotes")


def missing_of(card: dict) -> list:
    """أي الأقسام المطلوبة لم تُكتب بعد."""
    return [k for k in REQUIRED if not card.get(k)]


def absent_of(card: dict) -> list:
    """
    «لا توجد معلومة» تُقال عن البطاقة الآلية وحدها.

    كانت تُقال عن المكتوبة بيدٍ أيضاً، فقرأها صاحب المشروع فقال: أنت ذكاء
    اصطناعي وتقول لا يوجد؟ وهو محقّ. `cope` لها أضداد وأفعال مركّبة،
    وغيابُها كان كسلي لا نقصاً في المعلومة — وإعلانُ كسلٍ في ثوب أمانة
    أسوأ من الكسل وحده.

    فالبطاقة المكتوبة بيدٍ لا تُعلن نقصاً: إمّا تكتمل، وإمّا تُعاد كتابتها.
    """
    if card.get("curated"):
        return []
    return [k for k in ("antonyms", "collocations", "phrasalVerbs",
                        "idioms", "usageNotes", "differences", "examples",
                        "grammarPatterns")
            if not card.get(k)]


def merge(enrich_dir: str, cards: dict, write: bool) -> dict:
    """
    يضع البطاقات المنقّحة في شرائحها، ويترك ما لم يُنقَّح كما هو موسوماً.

    لا يُمحى المجلد: البطاقة الآلية تبقى حتى تُكتب بديلتُها، فلا تختفي
    كلمةٌ من التطبيق لأننا لم نصل إليها بعد.
    """
    touched, kept = {}, 0
    for path in sorted(glob.glob(os.path.join(enrich_dir, "*.json"))):
        if os.path.basename(path) == "index.json":
            continue
        with open(path, encoding="utf-8") as f:
            shard = json.load(f)
        changed = False
        for w in list(shard):
            key = w.lower()
            if key in cards:
                shard[w] = dict(cards[key])
                shard[w]["absent"] = absent_of(shard[w])
                changed = True
            elif not shard[w].get("curated"):
                shard[w]["curated"] = False      # وسمٌ ظاهر: هذه آلية
                kept += 1
                changed = True
        if changed:
            touched[path] = shard

    # كلمةٌ منقّحة ليست في أي شريحة بعد — تُضاف إلى شريحتها
    placed = {w.lower() for s in touched.values() for w in s}
    for key, card in cards.items():
        if key in placed:
            continue
        name = (key[:2] or "_").ljust(2, "_") + ".json"
        path = os.path.join(enrich_dir, name)
        shard = touched.get(path)
        if shard is None:
            shard = {}
            if os.path.exists(path):
                with open(path, encoding="utf-8") as f:
                    shard = json.load(f)
        shard[key] = dict(card)
        shard[key]["absent"] = absent_of(shard[key])
        touched[path] = shard

    if write:
        for path, shard in touched.items():
            with open(path, "w", encoding="utf-8") as f:
                json.dump(shard, f, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":"))
    return {"curated": len(cards), "machine": kept,
            "shards": len(touched)}


def reindex(enrich_dir: str) -> dict:
    """يعيد حساب البصمات — بدونها لا ينزّل التطبيق ما كتبناه."""
    index, total, curated = {}, 0, 0
    for path in sorted(glob.glob(os.path.join(enrich_dir, "*.json"))):
        key = os.path.splitext(os.path.basename(path))[0]
        if key == "index":
            continue
        with open(path, encoding="utf-8") as f:
            shard = json.load(f)
        body = json.dumps(shard, ensure_ascii=False, sort_keys=True,
                          separators=(",", ":"))
        with open(path, "w", encoding="utf-8") as f:
            f.write(body)
        raw = body.encode("utf-8")
        index[key] = {"hash": hashlib.sha1(raw).hexdigest()[:16],
                      "words": len(shard), "bytes": len(raw)}
        total += len(raw)
        curated += sum(1 for c in shard.values() if c.get("curated"))

    meta = {"schema": CARD_SCHEMA,
            "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "words": sum(v["words"] for v in index.values()),
            "curated": curated, "shards": index}
    with open(os.path.join(enrich_dir, "index.json"), "w",
              encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1, sort_keys=True)
    return {"words": meta["words"], "curated": curated,
            "kb": len(index), "kb_bytes": total}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enrich", required=True, help="مجلد enrich في المستودع")
    ap.add_argument("--curated", default=CURATED)
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    cards = {w: build_card(w, c) for w, c in load_curated(a.curated).items()}
    print(f"منقَّحٌ بيد: {len(cards)} كلمة")
    if not a.write:
        for w in list(cards)[:10]:
            m = cards[w].get("meanings") or []
            print(f"   {w:16} {len(m)} معنى · "
                  f"{len(cards[w].get('examples') or [])} مثال")
        print("\nعرضٌ فقط. للتنفيذ: --write")
        return

    r = merge(a.enrich, cards, write=True)
    idx = reindex(a.enrich)
    print(f"  الشرائح المكتوبة: {r['shards']}")
    print(f"  البطاقات: {idx['words']} · مراجَعة {idx['curated']} · "
          f"آلية {idx['words'] - idx['curated']}")
    print(f"  الحجم: {idx['kb_bytes']/1024:.0f} ك.ب")


if __name__ == "__main__":
    main()
