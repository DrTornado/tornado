#!/usr/bin/env python3
"""
بناء قاعدة المعرفة المعجمية — يُشغَّل مرّة واحدة على Google Colab.

لماذا على Colab لا على الجهاز؟ لأن المدخلات غيغابايتات، وحساب المتلازمات
تحليلٌ نحوي لعشرات ملايين الكلمات. جهاز المستخدم ليس فيه Python أصلاً،
وليس من حقّه أن يُحمَّل هذا كي يعرف معنى كلمة.

فالثقيل كله هنا، والمُخرَج ملفٌ واحد صغير يُنزَّل ويُستعمل إلى الأبد:
    tornado-kb.sqlite

ولماذا مرّة واحدة؟ لأن المفردات المُغطّاة (أوكسفورد ٥٠٠٠ + أشيع ١٨٠٠٠)
أوسع بكثير من أي مكتبة شخصية. الكلمة التي يضيفها المستخدم بعد سنة تكون
موجودة في القاعدة سلفاً، فلا يعود إلى هنا أبداً.

المصادر كلها مجانية ومفتوحة:
    Wiktextract (ويكاموس مُحوَّل إلى JSON)  — CC BY-SA
    Open English WordNet / WordNet          — رخصة متساهلة
    Tatoeba (جمل مترجمة بشرياً)             — CC BY
    CMUdict                                  — رخصة متساهلة
    WikiText-103 (مدوّنة المتلازمات)         — CC BY-SA
"""

import bz2
import csv
import io
import json
import math
import os
import re
import sqlite3
import sys
import tarfile
import time
import urllib.request
from collections import Counter, defaultdict

# ───────────────────────────── الإعدادات ─────────────────────────────

# يُطبع عند التشغيل. الخلية تفضّل النسخة المحلية على المستودع، فبلا بصمةٍ
# ظاهرة قد تُعاد تشغيل نسخةٍ قديمة ويُظنّ الإصلاح فاشلاً.
VERSION = "14 — ترجمة آلية مفتوحة تملأ العربية الناقصة"

WORK = os.environ.get("TORNADO_WORK", "/content/kb")
OUT_DB = os.path.join(WORK, "tornado-kb.sqlite")

# قوائم المستخدم تُقرأ من مستودعه العام مباشرةً — لا رفع يدوي ولا نسخ
REPO_RAW = "https://raw.githubusercontent.com/drtornado/tornado/main"
OXFORD_URL = f"{REPO_RAW}/android/app/src/main/assets/oxford.txt"
FREQ_URL = f"{REPO_RAW}/android/app/src/main/assets/freq.txt"

# روابط مرشَّحة: kaikki يغيّر مساراته أحياناً، فنجرّب بالترتيب بدل أن نفشل
WIKTEXTRACT_CANDIDATES = [
    "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl",
    "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.json",
]

TATOEBA_SENTENCES = "https://downloads.tatoeba.org/exports/sentences.tar.bz2"
TATOEBA_LINKS = "https://downloads.tatoeba.org/exports/links.tar.bz2"

# "dependency" أدقّ (فعل + مفعوله فعلاً) و"window" أسرع عشر مرّات
COLLOCATION_MODE = os.environ.get("TORNADO_COLLOC", "dependency")
COLLOCATION_TOKENS = int(os.environ.get("TORNADO_COLLOC_TOKENS", "10000000"))

# سقفٌ زمنيّ صارم. التحليل النحوي أبطأ كثيراً ممّا قدّرتُ على معالجَي Colab،
# ومرحلةٌ بلا سقف تعني انتظاراً مفتوحاً لا يُعرف متى ينتهي. عند البلوغ تُحسب
# المتلازمات ممّا جُمع فعلاً — نتيجةٌ أقلّ تغطيةً خيرٌ من لا نتيجة.
COLLOCATION_MAX_MIN = float(os.environ.get("TORNADO_COLLOC_MAX_MIN", "25"))

# واحدٌ افتراضاً: Colab المجاني معالجان اثنان، وn_process=2 يترك العملية الأمّ
# بلا معالج ويضيف كلفة نقل النصوص بين العمليات، فيبطئ أكثر ممّا يُسرّع.
COLLOCATION_PROCS = int(os.environ.get("TORNADO_COLLOC_PROCS", "1"))
COLLOCATION_TOP_N = 12          # كم متلازمة نحفظ لكل كلمة لكل نمط
COLLOCATION_MIN_FREQ = 5        # أقلّ من ذلك ضوضاء إحصائية لا ظاهرة لغوية

STAGES = os.environ.get("TORNADO_STAGES", "all")

# "wide"  : كل مدخل إنجليزي حقيقي في ويكاموس — الافتراضي
# "lists" : أوكسفورد + أشيَع ١٨ ألف فقط (أصغر وأسرع)
#
# القوائم وحدها لا تكفي: مكتبة المستخدم فيها articulation وrotunda
# وapothecary — لا واحدة منها في أوكسفورد ولا في أشيَع ١٨ ألف. ومن بلغ
# هذا المستوى يجمع النادر عمداً، فبناءٌ على «الشائع» يخذله بالضبط حيث
# يحتاج. القياس الفعلي: ٥٠ من ١١٢ كلمة خارج القاعدة.
VOCAB_MODE = os.environ.get("TORNADO_VOCAB", "wide")

# الترجمة الآلية — مفتوحة، محلية داخل Colab، بلا مفتاح ولا حساب.
#   mine        : مكتبة المستخدم وحدها (الافتراضي — دقائق)
#   oxford      : كل أوكسفورد ٥٠٠٠
#   freq:20000  : أشيَع ٢٠ ألف
#   all         : الكل — أيام، لا يُنصح به
TRANSLATE_SCOPE = os.environ.get("TORNADO_TR_SCOPE", "mine")
# opus-mt خفيف وسريع على المعالج ورخصته CC BY. وNLLB أجود عربيةً لكنه
# أثقل ورخصته غير تجارية — يناسب مشروعاً شخصياً لا منشوراً.
TRANSLATE_MODEL = os.environ.get("TORNADO_TR_MODEL",
                                 "Helsinki-NLP/opus-mt-en-ar")
TRANSLATE_BATCH = int(os.environ.get("TORNADO_TR_BATCH", "24"))
TRANSLATE_MAX_MIN = float(os.environ.get("TORNADO_TR_MAX_MIN", "30"))


def want(stage: str) -> bool:
    return STAGES == "all" or stage in STAGES.split(",")


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def download(url: str, dest: str) -> str:
    """ينزّل ما لم يكن موجوداً — فإعادة تشغيل خلية لا تعيد تنزيل غيغابايت."""
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        log(f"موجود مسبقاً: {os.path.basename(dest)} "
            f"({os.path.getsize(dest)/1e6:.0f} م.ب)")
        return dest
    log(f"تنزيل {url}")
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                pct = 100 * done / total
                print(f"\r  {done/1e6:.0f}/{total/1e6:.0f} م.ب ({pct:.0f}%)",
                      end="", flush=True)
    print()
    os.replace(tmp, dest)
    return dest


def resolve(candidates, dest):
    """
    يجرّب الروابط بالترتيب ويبلّغ عن الفشل بوضوح.

    الغرض ألا يموت الدفتر بسطر HTTP 404 غامض لو نقل المصدر ملفاته —
    بل يقول للمستخدم أين يبحث عن الرابط الصحيح.
    """
    last = None
    for url in candidates:
        try:
            return download(url, dest)
        except Exception as e:                                   # noqa: BLE001
            log(f"تعذّر: {url} — {e}")
            last = e
    raise SystemExit(
        "لم ينجح أي رابط. افتح https://kaikki.org/dictionary/English/ "
        f"وضع الرابط الصحيح في WIKTEXTRACT_CANDIDATES.\nآخر خطأ: {last}"
    )


# ───────────────────────────── قاعدة البيانات ─────────────────────────────

SCHEMA = """
CREATE TABLE IF NOT EXISTS vocab (
  word TEXT PRIMARY KEY, freq_rank INTEGER, oxford TEXT, cefr TEXT,
  audio_us TEXT, audio_uk TEXT);
CREATE TABLE IF NOT EXISTS ipa (
  word TEXT, accent TEXT, value TEXT, source TEXT);
CREATE TABLE IF NOT EXISTS senses (
  word TEXT, pos TEXT, idx INTEGER, gloss TEXT, tags TEXT, source TEXT,
  ar TEXT, ar_src TEXT);
CREATE TABLE IF NOT EXISTS forms (
  word TEXT, form TEXT, tags TEXT);
CREATE TABLE IF NOT EXISTS relations (
  word TEXT, rel TEXT, target TEXT, source TEXT);
CREATE TABLE IF NOT EXISTS examples (
  word TEXT, en TEXT, ar TEXT, source TEXT, ar_src TEXT);
CREATE TABLE IF NOT EXISTS collocations (
  word TEXT, pattern TEXT, collocate TEXT, score REAL, freq INTEGER);
CREATE TABLE IF NOT EXISTS phrasal_verbs (
  base TEXT, phrase TEXT, gloss TEXT);
CREATE TABLE IF NOT EXISTS idioms (
  word TEXT, phrase TEXT, gloss TEXT);
CREATE TABLE IF NOT EXISTS usage_notes (
  word TEXT, note TEXT);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
"""

INDEXES = """
CREATE INDEX IF NOT EXISTS ix_ipa       ON ipa(word);
CREATE INDEX IF NOT EXISTS ix_senses    ON senses(word);
CREATE INDEX IF NOT EXISTS ix_forms     ON forms(word);
CREATE INDEX IF NOT EXISTS ix_form_rev  ON forms(form);
CREATE INDEX IF NOT EXISTS ix_rel       ON relations(word, rel);
CREATE INDEX IF NOT EXISTS ix_ex        ON examples(word);
CREATE INDEX IF NOT EXISTS ix_col       ON collocations(word);
CREATE INDEX IF NOT EXISTS ix_pv        ON phrasal_verbs(base);
CREATE INDEX IF NOT EXISTS ix_idiom     ON idioms(word);
CREATE INDEX IF NOT EXISTS ix_usage     ON usage_notes(word);
"""


def connect() -> sqlite3.Connection:
    db = sqlite3.connect(OUT_DB)
    db.executescript(SCHEMA)
    # الكتابة بالدفعات على قرص Colab بطيئة افتراضياً — هذه تُسرّعها كثيراً
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    # ترقية قاعدةٍ بُنيت قبل عمود الترجمة — لا تُهدَم لأجل عمود
    # ترقية قاعدةٍ بُنيت قبل أعمدة الترجمة. وعمود ar_src ليس زينةً:
    # المستخدم من حقّه أن يعرف أيّ عربيةٍ كتبها إنسان وأيّها آلة.
    for table, col in (("senses", "ar"), ("senses", "ar_src"),
                       ("examples", "ar_src")):
        cols = {r[1] for r in db.execute(f"PRAGMA table_info({table})")}
        if col not in cols:
            db.execute(f"ALTER TABLE {table} ADD COLUMN {col} TEXT")
    db.commit()
    return db


def wipe(db, *specs) -> None:
    """
    يمسح ما تكتبه المرحلة قبل أن تكتبه.

    كانت المراحل تُدخل بلا حذف، فكل إعادة تشغيل تضاعف الصفوف. وقد ظهر ذلك
    في أوّل مراجعة فعلية: كل معنى وكل تعبير وكل مثال مضاعفٌ تماماً — لا
    لخللٍ في المصدر بل لأن الجولة جرت مرّتين.

    والمرحلة التي لا تُعيد نفسها إلى الحالة ذاتها لا يمكن الوثوق بمخرجها،
    ولا إصلاح عطبٍ فيها بإعادة تشغيلها وحدها.
    """
    for spec in specs:
        table, _, src = spec.partition(":")
        if src:
            db.execute(f"DELETE FROM {table} WHERE source=?", (src,))
        else:
            db.execute(f"DELETE FROM {table}")
    db.commit()


# ───────────────────────────── المرحلة ١: المفردات ─────────────────────────────

def stage_vocab(db) -> set:
    """
    المفردات المُغطّاة = أوكسفورد ٥٠٠٠ ∪ أشيَع ١٨٠٠٠.

    ولماذا الاتحاد لا أحدهما؟ لأن أوكسفورد يعطي CEFR القاطع لكنه ينقصه
    الشائع غير التعليمي، والتكرار يعطي التغطية لكنه بلا مستوى. معاً
    يغطّيان أي كلمة يضيفها المستخدم عملياً.
    """
    ox = download(OXFORD_URL, os.path.join(WORK, "oxford.txt"))
    fq = download(FREQ_URL, os.path.join(WORK, "freq.txt"))

    rows, seen = {}, set()

    # word|CEFR|us_mp3|gb_mp3 — المستوى هنا رسميّ من أوكسفورد لا مُخمَّن
    with open(ox, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("|")
            if len(p) < 2 or not p[0]:
                continue
            w = p[0].strip().lower()
            seen.add(w)
            cefr = p[1].strip() or None
            base = "https://www.oxfordlearnersdictionaries.com/media/english"
            rows[w] = {
                "cefr": cefr,
                # الحقل يحوي مساراً نسبياً — نبنيه كاملاً مرّة واحدة هنا
                "audio_us": f"{base}/us_pron/{p[2]}" if len(p) > 2 and p[2] else None,
                "audio_uk": f"{base}/uk_pron/{p[3]}" if len(p) > 3 and p[3] else None,
                # نفس قاعدة التطبيقين حرفياً: ReferenceData.kt:21 و index.html:25258
                # الملف مرتّب أبجدياً لا بالأهمية، فلا يصحّ الاستدلال بالموضع
                "oxford": "5000" if cefr == "C1" else "3000",
            }

    with open(fq, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("|")
            if len(p) < 2 or not p[0]:
                continue
            w = p[0].strip().lower()
            seen.add(w)
            r = rows.setdefault(w, {"cefr": None, "audio_us": None,
                                    "audio_uk": None, "oxford": None})
            try:
                r["freq_rank"] = int(p[1])
            except ValueError:
                pass

    db.execute("DELETE FROM vocab")
    db.executemany(
        "INSERT OR REPLACE INTO vocab(word,freq_rank,oxford,cefr,audio_us,audio_uk)"
        " VALUES(?,?,?,?,?,?)",
        [(w, r.get("freq_rank"), r.get("oxford"), r.get("cefr"),
          r.get("audio_us"), r.get("audio_uk")) for w, r in rows.items()])
    db.commit()
    log(f"المفردات: {len(rows)} كلمة "
        f"(بمستوى CEFR: {sum(1 for r in rows.values() if r.get('cefr'))})")
    return seen


# ───────────────────────────── المرحلة ٢: ويكاموس ─────────────────────────────

# وسوم السجل والسياق — منظّمة في ويكاموس، فتُستخرج ولا تُخمَّن
REGISTER_TAGS = {
    "formal", "informal", "slang", "colloquial", "vulgar", "offensive",
    "dated", "archaic", "obsolete", "literary", "poetic", "humorous",
    "British", "US", "Australian", "Canadian", "Irish", "Scottish",
    "technical", "medicine", "law", "business", "computing",
}


# الحروف التي تصنع فعلاً مركّباً حقيقياً. القائمة مغلقة عمداً: كل ما عداها
# فعلٌ + جملة اسمية، أي تعبيرٌ اصطلاحي يذهب إلى حقله لا إلى الأفعال المركّبة.
PARTICLES = {
    "up", "down", "in", "out", "on", "off", "away", "back", "over",
    "through", "along", "around", "about", "across", "apart", "aside",
    "by", "for", "forth", "forward", "into", "onto", "past", "to",
    "together", "under", "upon", "with", "without", "after", "against",
    "ahead", "behind", "beyond", "from", "of", "round",
}


_LEMMA_OK = re.compile(r"^[a-z][a-z'\-]{1,29}$")


def _is_lemma(e, wl: str) -> bool:
    """
    أهذا مدخلٌ حقيقي يستحقّ بطاقة، أم صيغةٌ مصرَّفة أو رمز؟

    ويكاموس يضع «walked» مدخلاً مستقلاً معناه «صيغة الماضي من walk».
    تلك ليست كلمةً تُحفَظ بل إشارةٌ إلى غيرها، ووجودها في المفردات
    يضخّم القاعدة بلا فائدة. فنشترط معنىً قائماً بذاته لا إحالة.
    """
    if not _LEMMA_OK.match(wl):
        return False
    for s in (e.get("senses") or []):
        if not isinstance(s, dict) or s.get("form_of") or s.get("alt_of"):
            continue
        if _strs(s.get("glosses"), "text"):
            return True
    return False


def _strs(seq, *keys) -> list:
    """
    يُطبّع قوائم ويكاموس إلى نصوص خالصة.

    الحقل الواحد يأتي في الاستخراج بشكلين مختلفين: `categories` قد تكون
    ["English idioms"] وقد تكون [{"name": "English idioms", "kind": …}].
    والعكس وارد أيضاً — `examples` قد تكون قواميس وقد تكون نصوصاً.

    فأي افتراضٍ لشكلٍ واحد ينهار على سجلّ ما بعد ملايين السجلات. وهذا ما
    حدث فعلاً: انكسرت الجولة الأولى بعد تنزيل غيغابايتات على
    `" ".join(categories)` حين جاء العنصر قاموساً.
    """
    out = []
    for x in seq or []:
        if isinstance(x, str):
            if x:
                out.append(x)
        elif isinstance(x, dict):
            for k in keys:
                v = x.get(k)
                if isinstance(v, str) and v:
                    out.append(v)
                    break
    return out


def stage_wiktionary(db, vocab: set) -> None:
    """
    يستخرج من ويكاموس: النطق، المعاني، الوسوم، التصريفات، المشتقات،
    المرادفات، الأضداد، ملاحظات الاستعمال، والأفعال المركّبة والتعابير.

    الملفّ عدّة غيغابايت، فيُقرأ سطراً سطراً ولا يُحمَّل إلى الذاكرة أبداً.
    """
    path = resolve(WIKTEXTRACT_CANDIDATES,
                   os.path.join(WORK, "wiktextract-en.jsonl"))

    wipe(db, "ipa:wiktionary", "senses:wiktionary", "relations:wiktionary",
         "examples:wiktionary", "forms", "usage_notes", "phrasal_verbs",
         "idioms")

    ipa_rows, sense_rows, form_rows, rel_rows = [], [], [], []
    usage_rows, pv_rows, idiom_rows = [], [], []
    added = set()
    kept = seen_lines = skipped = 0

    def flush():
        db.executemany("INSERT INTO ipa VALUES(?,?,?,?)", ipa_rows)
        db.executemany("INSERT INTO senses(word,pos,idx,gloss,tags,source,ar) VALUES(?,?,?,?,?,?,?)", sense_rows)
        db.executemany("INSERT INTO forms VALUES(?,?,?)", form_rows)
        db.executemany("INSERT INTO relations VALUES(?,?,?,?)", rel_rows)
        db.executemany("INSERT INTO usage_notes VALUES(?,?)", usage_rows)
        db.executemany("INSERT INTO phrasal_verbs VALUES(?,?,?)", pv_rows)
        db.executemany("INSERT INTO idioms VALUES(?,?,?)", idiom_rows)
        db.commit()
        for lst in (ipa_rows, sense_rows, form_rows, rel_rows,
                    usage_rows, pv_rows, idiom_rows):
            lst.clear()

    def handle(e) -> None:
        nonlocal kept

        word = (e.get("word") or "").strip()
        wl = word.lower()
        pos = e.get("pos") or ""

        # التعابير والأفعال المركّبة مداخلُ مستقلّة تُربط بكلماتها
        if " " in wl:
            cats = " ".join(_strs(e.get("categories"), "name", "kind")).lower()
            senses = e.get("senses") or []
            gloss = ""
            for s in senses:
                g = _strs((s or {}).get("glosses"), "text")
                if g:
                    gloss = g[0]
                    break
            if not gloss:
                return
            # الفعل المركّب فعلٌ + حرف: «abide by». أمّا «assume the position»
            # ففعلٌ + جملة اسمية، أي تعبيرٌ اصطلاحي لا فعلٌ مركّب. وشرطي
            # السابق `pos == "verb"` كان يبتلعها كلها فتظهر في الحقلين معاً.
            parts = wl.split()
            is_pv = "phrasal verb" in cats or (
                pos == "verb" and len(parts) == 2 and parts[1] in PARTICLES)

            if is_pv:
                if parts[0] in vocab:
                    pv_rows.append((parts[0], wl, gloss))
                    kept += 1
                if len(pv_rows) + len(idiom_rows) > 20000:
                    flush()
                return          # حصريّ: ما كان فعلاً مركّباً لا يُعاد تعبيراً

            idiomatic = any("idiomatic" in _strs((s or {}).get("tags"), "name")
                            for s in senses)
            if "idiom" in cats or idiomatic:
                for tok in set(wl.split()):
                    tok = re.sub(r"[^a-z'-]", "", tok)
                    if tok in vocab and len(tok) > 2:
                        idiom_rows.append((tok, wl, gloss))
                        kept += 1
            if len(pv_rows) + len(idiom_rows) > 20000:
                flush()
            return

        if wl not in vocab:
            # في النمط الواسع: المدخل الحقيقي يدخل المفردات ولو لم يكن
            # في أي قائمة — بلا CEFR ولا رتبة، فتلك تُترك فارغةً بصدق
            if VOCAB_MODE != "wide" or not _is_lemma(e, wl):
                return
            vocab.add(wl)
            added.add(wl)
        kept += 1

        # النطق — ويكاموس يوسم اللهجة، فنحتفظ بالتمييز بدل طمسه
        for s in (e.get("sounds") or []):
            if not isinstance(s, dict):
                continue
            v = s.get("ipa")
            if not isinstance(v, str) or not v:
                continue
            tags = [t.lower() for t in _strs(s.get("tags"), "name")]
            accent = ("uk" if any(t in tags for t in
                                  ("uk", "british", "received-pronunciation"))
                      else "us" if any(t in tags for t in
                                       ("us", "general-american", "american"))
                      else "gen")
            ipa_rows.append((wl, accent, v, "wiktionary"))

        for i, s in enumerate(e.get("senses") or []):
            if not isinstance(s, dict):
                continue
            glosses = _strs(s.get("glosses"), "text")
            if not glosses:
                continue
            tags = [t for t in _strs(s.get("tags"), "name")
                    if t in REGISTER_TAGS]

            # ترجمة عربية مربوطة بهذا المعنى تحديداً — مُحرَّرة بشرياً
            # ومشكولة، لا آلية. «free» تعطي حُرّ لمعنىً وشَاغِر لآخر،
            # وهو ما لا تبلغه ترجمةٌ آلية تجهل المعنى المقصود.
            ar = ""
            for t in (s.get("translations") or []):
                if not isinstance(t, dict) or t.get("code") not in ("ar", "arb"):
                    continue
                cand = t.get("word")
                if isinstance(cand, str) and cand and cand not in ar:
                    ar = f"{ar} · {cand}" if ar else cand
                    if ar.count("·") >= 1:
                        break

            sense_rows.append((wl, pos, i, glosses[-1],
                               ",".join(tags), "wiktionary", ar or None))
            for txt in _strs(s.get("examples"), "text", "example", "english"):
                if 3 <= len(txt.split()) <= 30:
                    db.execute("INSERT INTO examples(word,en,ar,source) VALUES(?,?,?,?)",
                               (wl, txt, None, "wiktionary"))

        # التصريفات جاهزة وموسومة — لا حاجة لتوليدها بقواعد تُخطئ
        for fm in (e.get("forms") or []):
            val = fm.get("form") if isinstance(fm, dict) else fm
            if isinstance(val, str) and val and val != "-" and len(val) < 40:
                ftags = _strs(fm.get("tags"), "name") if isinstance(fm, dict) else []
                form_rows.append((wl, val, ",".join(ftags)))

        for key, rel in (("synonyms", "synonym"), ("antonyms", "antonym"),
                         ("derived", "derived"), ("related", "related"),
                         ("hypernyms", "hypernym")):
            for t in _strs(e.get(key), "word", "name"):
                t = t.strip().lower()
                if t and t != wl and len(t) < 40:
                    rel_rows.append((wl, rel, t, "wiktionary"))

        for note in _strs(e.get("notes"), "text", "note"):
            if 20 < len(note) < 600:
                usage_rows.append((wl, note))

        if len(sense_rows) > 20000:
            flush()

    with open(path, encoding="utf-8") as f:
        for line in f:
            seen_lines += 1
            if seen_lines % 500_000 == 0:
                log(f"  ويكاموس: {seen_lines:,} سطراً · محفوظ {kept:,}"
                    + (f" · متخطّى {skipped:,}" if skipped else ""))
            line = line.strip().rstrip(",")
            if not line or line[0] != "{":
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                skipped += 1
                continue
            if not isinstance(e, dict) or e.get("lang_code") != "en":
                continue
            # سجلٌّ معطوب يُعَدّ ويُتخطّى — لا يُسقط جولةً كلّفت غيغابايتات
            try:
                handle(e)
            except Exception:                                    # noqa: BLE001
                skipped += 1
                if skipped <= 3:
                    log(f"  تخطّيتُ سجلاً: {e.get('word')!r} — "
                        f"{sys.exc_info()[1]}")

    flush()
    if added:
        db.executemany(
            "INSERT OR IGNORE INTO vocab(word,freq_rank,oxford,cefr)"
            " VALUES(?,NULL,NULL,NULL)", [(w,) for w in added])
        db.commit()
        log(f"المفردات اتّسعت: +{len(added):,} مدخلاً من ويكاموس "
            f"(المجموع {len(vocab):,})")
    log(f"ويكاموس: {seen_lines:,} سطراً · {kept:,} سجلاً محفوظاً"
        + (f" · {skipped:,} سجلاً متخطّى" if skipped else ""))
    if skipped > seen_lines * 0.01:
        log(f"  ⚠ نسبة التخطّي مرتفعة ({100*skipped/seen_lines:.1f}%) — "
            "قد يكون شكل الاستخراج تغيّر")


# ───────────────────────────── المرحلة ٣: WordNet ─────────────────────────────

def stage_wordnet(db, vocab: set) -> None:
    """
    وردنت هو أوثق مصدر مجاني للمرادفات والأضداد — علاقات مُحرَّرة يدوياً
    لا مستخرجة من نصّ حرّ. وتعريفاته موجزة وواضحة، وهي أنسب للمتعلّم من
    تعريفات ويكاموس المعجمية الطويلة.
    """
    import nltk
    for pkg in ("wordnet", "omw-1.4"):
        try:
            nltk.download(pkg, quiet=True)
        except Exception:                                        # noqa: BLE001
            pass
    from nltk.corpus import wordnet as wn

    wipe(db, "senses:wordnet", "relations:wordnet")
    POS_MAP = {"n": "noun", "v": "verb", "a": "adj", "s": "adj", "r": "adv"}
    senses, rels, n = [], [], 0

    for word in vocab:
        try:
            syns = wn.synsets(word)
        except Exception:                                        # noqa: BLE001
            continue
        if not syns:
            continue
        n += 1
        for i, syn in enumerate(syns[:6]):
            # WordNet لا يحمل عربية — العمود يبقى فارغاً وويكاموس يملؤه
            senses.append((word, POS_MAP.get(syn.pos(), syn.pos()), i,
                           syn.definition(), "", "wordnet", None))
            for lem in syn.lemmas():
                name = lem.name().replace("_", " ").lower()
                if name != word:
                    rels.append((word, "synonym", name, "wordnet"))
                for ant in lem.antonyms():
                    rels.append((word, "antonym",
                                 ant.name().replace("_", " ").lower(), "wordnet"))
                for d in lem.derivationally_related_forms():
                    dn = d.name().replace("_", " ").lower()
                    if dn != word:
                        rels.append((word, "derived", dn, "wordnet"))
        if len(senses) > 20000:
            db.executemany("INSERT INTO senses(word,pos,idx,gloss,tags,source,ar) VALUES(?,?,?,?,?,?,?)", senses)
            db.executemany("INSERT INTO relations VALUES(?,?,?,?)", rels)
            db.commit()
            senses.clear()
            rels.clear()

    db.executemany("INSERT INTO senses(word,pos,idx,gloss,tags,source,ar) VALUES(?,?,?,?,?,?,?)", senses)
    db.executemany("INSERT INTO relations VALUES(?,?,?,?)", rels)
    db.commit()
    log(f"WordNet: {n:,} كلمة لها مدخل")


def stage_cmudict(db, vocab: set) -> None:
    """نطقٌ احتياطي أمريكي لما لم يغطّه ويكاموس — أفضل من حقل فارغ."""
    import nltk
    try:
        nltk.download("cmudict", quiet=True)
        from nltk.corpus import cmudict
        d = cmudict.dict()
    except Exception as e:                                       # noqa: BLE001
        log(f"CMUdict غير متاح، نتخطّاه: {e}")
        return

    ARPA = {
        "AA": "ɑ", "AE": "æ", "AH": "ʌ", "AO": "ɔ", "AW": "aʊ", "AY": "aɪ",
        "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "EH": "ɛ", "ER": "ɝ",
        "EY": "eɪ", "F": "f", "G": "ɡ", "HH": "h", "IH": "ɪ", "IY": "i",
        "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "ŋ",
        "OW": "oʊ", "OY": "ɔɪ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ",
        "T": "t", "TH": "θ", "UH": "ʊ", "UW": "u", "V": "v", "W": "w",
        "Y": "j", "Z": "z", "ZH": "ʒ",
    }
    wipe(db, "ipa:cmudict")
    have = {r[0] for r in db.execute(
        "SELECT DISTINCT word FROM ipa WHERE accent IN ('us','gen')")}
    rows = []
    for w in vocab:
        if w in have or w not in d:
            continue
        out = []
        for ph in d[w][0]:
            stress = ""
            if ph[-1].isdigit():
                stress, ph = ph[-1], ph[:-1]
            sym = ARPA.get(ph)
            if not sym:
                continue
            out.append(("ˈ" if stress == "1" else "ˌ" if stress == "2" else "") + sym)
        if out:
            rows.append((w, "us", "/" + "".join(out) + "/", "cmudict"))
    db.executemany("INSERT INTO ipa VALUES(?,?,?,?)", rows)
    db.commit()
    log(f"CMUdict: {len(rows):,} نطقاً احتياطياً")


# ───────────────────────────── المرحلة ٤: Tatoeba ─────────────────────────────

def stage_tatoeba(db, vocab: set) -> None:
    """
    أمثلةٌ ترجمها بشر، لا آلة.

    هذا أثمن ما في القاعدة: جملة إنجليزية طبيعية مع ترجمة عربية كتبها
    إنسان. أي ترجمة آلية — مهما تحسّنت — دونها. والتغطية ليست شاملة،
    فما لم تغطّه تبقى أمثلة ويكاموس بلا ترجمة، مُعلَّمة بذلك صراحةً.
    """
    wipe(db, "examples:tatoeba")
    s_tar = download(TATOEBA_SENTENCES, os.path.join(WORK, "sentences.tar.bz2"))
    l_tar = download(TATOEBA_LINKS, os.path.join(WORK, "links.tar.bz2"))

    def read_tsv(tar_path):
        with tarfile.open(tar_path, "r:bz2") as tf:
            member = next(m for m in tf.getmembers() if m.name.endswith(".csv"))
            data = tf.extractfile(member).read().decode("utf-8", "replace")
        return csv.reader(io.StringIO(data), delimiter="\t", quoting=csv.QUOTE_NONE)

    eng, ara = {}, {}
    for row in read_tsv(s_tar):
        if len(row) < 3:
            continue
        sid, lang, text = row[0], row[1], row[2]
        if lang == "eng" and 3 <= len(text.split()) <= 25:
            eng[sid] = text
        elif lang == "ara":
            ara[sid] = text
    log(f"Tatoeba: {len(eng):,} جملة إنجليزية · {len(ara):,} عربية")

    pairs = defaultdict(list)
    for row in read_tsv(l_tar):
        if len(row) < 2:
            continue
        a, b = row[0], row[1]
        if a in eng and b in ara:
            pairs[a].append(b)

    # فهرسة بالكلمة: أقصر جملة أوضح للمتعلّم، فنرتّب بالطول ونأخذ ثلاثاً
    by_word = defaultdict(list)
    for sid, arabs in pairs.items():
        text = eng[sid]
        for tok in set(re.findall(r"[a-z']+", text.lower())):
            if tok in vocab:
                by_word[tok].append((text, ara[arabs[0]]))

    rows = []
    for w, lst in by_word.items():
        for en, ar in sorted(lst, key=lambda p: len(p[0]))[:3]:
            rows.append((w, en, ar, "tatoeba"))
    db.executemany("INSERT INTO examples(word,en,ar,source) VALUES(?,?,?,?)", rows)
    db.commit()
    log(f"Tatoeba: {len(by_word):,} كلمة لها مثال مترجم · {len(rows):,} مثالاً")


# ───────────────────────── المرحلة ٦: الترجمة الآلية ─────────────────────────

def _translate_targets(db) -> set:
    """
    أيّ الكلمات تُترجَم؟

    القاعدة نصف مليون مدخل، وترجمتها كلها أيامٌ من الحوسبة لأجل ألفاظ
    لا يملكها أحد. فالنطاق افتراضاً مكتبة المستخدم: بضع مئات من العناصر،
    دقائق معدودة، وكل ما يراه فعلاً.
    """
    scope = TRANSLATE_SCOPE
    if scope == "all":
        return {r[0] for r in db.execute("SELECT word FROM vocab")}
    if scope == "oxford":
        return {r[0] for r in db.execute(
            "SELECT word FROM vocab WHERE oxford IS NOT NULL")}
    if scope.startswith("freq:"):
        n = int(scope.split(":", 1)[1])
        return {r[0] for r in db.execute(
            "SELECT word FROM vocab WHERE freq_rank IS NOT NULL"
            " AND freq_rank <= ?", (n,))}

    out = set()
    for w in user_words():
        r = resolve_word(db, w)
        if r:
            out.add(r)
    return out


def stage_translate(db) -> None:
    """
    يملأ العربية الناقصة بنموذجٍ مفتوح — كلّه داخل Colab.

    الترتيب مقصود: ما ترجمه إنسان (جداول ويكاموس، جمل Tatoeba) لا يُمسّ
    أبداً. الآلة تملأ الفراغ وحده، وتُعلَّم مخرجاتها بـ ar_src='mt' كي
    تظلّ التفرقة قائمة في البطاقة — لا يُخلط المُحرَّر بالمولَّد.
    """
    try:
        from transformers import pipeline
    except ImportError:
        log("أثبّت transformers…")
        os.system(f"{sys.executable} -m pip install -q transformers "
                  "sentencepiece sacremoses")
        from transformers import pipeline

    try:
        import torch
        device = 0 if torch.cuda.is_available() else -1
    except ImportError:
        device = -1
    log(f"النموذج {TRANSLATE_MODEL} · "
        f"{'كرت رسومي' if device == 0 else 'معالج'}")

    words = _translate_targets(db)
    if not words:
        log("لا كلمات في النطاق")
        return
    db.execute("DROP TABLE IF EXISTS temp.tgt")
    db.execute("CREATE TEMP TABLE tgt(word TEXT PRIMARY KEY)")
    db.executemany("INSERT OR IGNORE INTO tgt VALUES(?)",
                   [(w,) for w in words])

    jobs = []
    for tbl, col in (("senses", "gloss"), ("examples", "en")):
        rows = db.execute(
            f"SELECT rowid, {col} FROM {tbl}"
            f" WHERE (ar IS NULL OR ar='') AND {col} IS NOT NULL"
            f" AND length({col}) BETWEEN 3 AND 400"
            f" AND word IN (SELECT word FROM tgt)").fetchall()
        jobs += [(tbl, rid, txt) for rid, txt in rows]

    if not jobs:
        log("لا شيء ينقصه ترجمة في هذا النطاق")
        return
    log(f"النطاق {TRANSLATE_SCOPE}: {len(words):,} كلمة · "
        f"{len(jobs):,} نصاً للترجمة")

    tr = pipeline("translation", model=TRANSLATE_MODEL, device=device)
    t0 = time.time()
    deadline = t0 + TRANSLATE_MAX_MIN * 60
    done = 0

    for i in range(0, len(jobs), TRANSLATE_BATCH):
        chunk = jobs[i:i + TRANSLATE_BATCH]
        try:
            outs = tr([t for _, _, t in chunk], max_length=256,
                      batch_size=len(chunk))
        except Exception as e:                                   # noqa: BLE001
            log(f"  تعذّرت دفعة ({e}) — أتخطّاها")
            continue
        for (tbl, rid, _), o in zip(chunk, outs):
            ar = (o.get("translation_text") or "").strip()
            if ar:
                db.execute(f"UPDATE {tbl} SET ar=?, ar_src='mt'"
                           f" WHERE rowid=?", (ar, rid))
                done += 1
        db.commit()

        el = max(time.time() - t0, 0.1)
        pct = 100 * (i + len(chunk)) / len(jobs)
        eta = (len(jobs) - i - len(chunk)) / max((i + len(chunk)) / el, 0.1) / 60
        log(f"  {i+len(chunk):,}/{len(jobs):,} ({pct:.0f}%) · "
            f"مضى {el/60:.0f} د · بقي ~{eta:.0f} د")
        if time.time() > deadline:
            log(f"  ⏱ بلغتُ السقف ({TRANSLATE_MAX_MIN} د) — "
                f"تُرجم {done:,}، والباقي في تشغيلةٍ لاحقة")
            break

    log(f"الترجمة: {done:,} نصاً في {(time.time()-t0)/60:.0f} د")


# ───────────────────────────── المرحلة ٥: المتلازمات ─────────────────────────────

# مجموعة wikitext نُقلت إلى نطاق Salesforce، والاسم المجرّد لم يعد يُحلّ.
# والنطاقات على Hugging Face تتبدّل، فنُبقي مرشَّحين بدل اسمٍ واحد يسقط.
CORPUS_CANDIDATES = [
    ("Salesforce/wikitext", "wikitext-103-raw-v1"),
    ("wikitext", "wikitext-103-raw-v1"),        # الاسم القديم لبيئاتٍ أقدم
    ("Salesforce/wikitext", "wikitext-2-raw-v1"),   # أصغر بكثير — احتياط
]


def _tatoeba_english():
    """
    احتياطٌ محلي: الجمل الإنجليزية من أرشيف Tatoeba المنزَّل سلفاً.

    أصغر من ويكيتكست وأقرب إلى اللغة اليومية، فالمتلازمات منه أنسب للمتعلّم
    وأقلّ تغطيةً للمفردات الأكاديمية. والمكسب الحاسم أنه على القرص أصلاً:
    لا تنزيل جديد، ولا اعتماد على خدمةٍ خارجية قد تتبدّل تحتنا.
    """
    tar = os.path.join(WORK, "sentences.tar.bz2")
    if not os.path.exists(tar):
        return None
    with tarfile.open(tar, "r:bz2") as tf:
        member = next(m for m in tf.getmembers() if m.name.endswith(".csv"))
        data = tf.extractfile(member).read().decode("utf-8", "replace")
    rows = csv.reader(io.StringIO(data), delimiter="\t", quoting=csv.QUOTE_NONE)
    return (r[2] for r in rows if len(r) >= 3 and r[1] == "eng")


def _open_corpus():
    """يعيد (الاسم، مُكرِّر نصوص) من أوّل مصدرٍ يعمل فعلاً."""
    try:
        from datasets import load_dataset
    except ImportError as e:                                     # noqa: BLE001
        log(f"مكتبة datasets غير متاحة ({e})")
        load_dataset = None

    if load_dataset:
        for repo, cfg in CORPUS_CANDIDATES:
            try:
                ds = load_dataset(repo, cfg, split="train", streaming=True)
                next(iter(ds))          # تحقّق فعليّ — التحميل مؤجَّل بطبعه
                return f"{repo}/{cfg}", (r.get("text") for r in ds)
            except Exception as e:                               # noqa: BLE001
                log(f"  تعذّرت {repo}/{cfg}: {str(e)[:110]}")

    fallback = _tatoeba_english()
    if fallback is None:
        raise SystemExit(
            "لا مدوّنة متاحة. إمّا أن تُصلح الوصول إلى Hugging Face، أو "
            "تشغّل مرحلة tatoeba أوّلاً لينزل أرشيف الجمل ويُستعمل احتياطاً:\n"
            "  os.environ['TORNADO_STAGES'] = 'tatoeba,collocations'")
    log("  الاحتياط: جمل Tatoeba المحلية (لا تنزيل جديد)")
    return "Tatoeba-EN (احتياطي)", fallback


def stage_collocations(db, vocab: set) -> None:
    """
    تُحسب ولا تُنسخ — إذ لا يوجد مصدر مجاني منظّم لها.

    والمقياس logDice لا التكرار الخام: «the» تجاور كل شيء ولا تلازم شيئاً.
    logDice يقيس قوّة الارتباط بين الكلمتين، وهو المقياس نفسه الذي تستعمله
    الأدوات التجارية.
    """
    import spacy

    try:
        nlp = spacy.load("en_core_web_sm", exclude=["ner", "lemmatizer"])
    except OSError:
        os.system(f"{sys.executable} -m spacy download en_core_web_sm")
        nlp = spacy.load("en_core_web_sm", exclude=["ner", "lemmatizer"])
    if COLLOCATION_MODE != "dependency":
        nlp.disable_pipe("parser")

    wipe(db, "collocations")
    name, source = _open_corpus()
    log(f"المدوّنة: {name} · نمط {COLLOCATION_MODE} · "
        f"هدف {COLLOCATION_TOKENS:,} كلمة")

    def texts():
        n = 0
        for t in source:
            t = (t or "").strip()
            if len(t) < 40 or t.startswith("="):
                continue
            n += len(t.split())
            yield t
            if n >= COLLOCATION_TOKENS:
                return

    pair_f, word_f, col_f = Counter(), Counter(), Counter()
    total = docs = 0
    t0 = time.time()
    deadline = t0 + COLLOCATION_MAX_MIN * 60
    log(f"  السقف الزمني {COLLOCATION_MAX_MIN:.0f} دقيقة · "
        f"{COLLOCATION_PROCS} معالج")

    for doc in nlp.pipe(texts(), batch_size=128,
                        n_process=COLLOCATION_PROCS):
        total += len(doc)
        docs += 1

        # تقريرٌ دوريّ: مرحلةٌ طويلة صامتة لا يُعرف أهي تعمل أم تجمّدت
        if docs % 1000 == 0:
            el = max(time.time() - t0, 0.1)
            rate = total / el
            pct = 100 * total / COLLOCATION_TOKENS
            eta = (COLLOCATION_TOKENS - total) / rate / 60 if rate else 0
            log(f"  {total:,} رمزاً ({pct:.0f}%) · {rate:,.0f} رمز/ث · "
                f"مضى {el/60:.0f} د · بقي ~{eta:.0f} د")
            if time.time() > deadline:
                log(f"  ⏱ بلغتُ السقف — أُكمل الحساب بما جُمع "
                    f"({total:,} رمزاً)")
                break

        if COLLOCATION_MODE == "dependency":
            for tok in doc:
                if tok.is_stop or tok.is_punct or not tok.text.isalpha():
                    continue
                head = tok.head
                if head is tok or head.is_stop or not head.text.isalpha():
                    continue
                a, b = tok.text.lower(), head.text.lower()
                if a == b:      # «issue ← issue» ليست متلازمة بل تكراراً
                    continue
                dep = tok.dep_
                if dep in ("dobj", "nsubj", "amod", "advmod", "compound",
                           "pobj", "acomp"):
                    if a in vocab or b in vocab:
                        pair_f[(b, dep, a)] += 1
                        word_f[b] += 1
                        col_f[a] += 1
        else:
            toks = [t.text.lower() for t in doc
                    if t.text.isalpha() and not t.is_stop]
            for i, a in enumerate(toks):
                if a not in vocab:
                    continue
                for b in toks[max(0, i - 3):i] + toks[i + 1:i + 4]:
                    if b == a:
                        continue
                    pair_f[(a, "near", b)] += 1
                    word_f[a] += 1
                    col_f[b] += 1
        if total > COLLOCATION_TOKENS:
            break

    log(f"حُلِّل {total:,} رمزاً في {(time.time()-t0)/60:.0f} د · "
        f"{len(pair_f):,} زوجاً")

    best = defaultdict(list)
    for (w, pat, c), f in pair_f.items():
        if f < COLLOCATION_MIN_FREQ or w not in vocab:
            continue
        # logDice = 14 + log2( 2·f(a,b) / (f(a)+f(b)) )
        score = 14 + math.log2(2 * f / (word_f[w] + col_f[c]))
        best[(w, pat)].append((score, c, f))

    rows = []
    for (w, pat), lst in best.items():
        for score, c, f in sorted(lst, reverse=True)[:COLLOCATION_TOP_N]:
            rows.append((w, pat, c, round(score, 3), f))
    db.executemany("INSERT INTO collocations VALUES(?,?,?,?,?)", rows)
    db.commit()
    log(f"المتلازمات: {len(rows):,} سجلاً لـ "
        f"{len({r[0] for r in rows}):,} كلمة")


# ───────────────────────────── التقرير ─────────────────────────────

def stage_report(db) -> None:
    db.executescript(INDEXES)
    db.execute("INSERT OR REPLACE INTO meta VALUES('built_at',?)",
               (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),))
    db.execute("INSERT OR REPLACE INTO meta VALUES('schema','1')")
    db.commit()
    # VACUUM لا يعمل داخل معاملة، وواجهة sqlite3 تفتح واحدة تلقائياً
    db.isolation_level = None
    db.execute("VACUUM")

    n_vocab = db.execute("SELECT COUNT(*) FROM vocab").fetchone()[0]
    print("\n" + "═" * 58)
    print("  تغطية قاعدة المعرفة")
    print("═" * 58)
    checks = [
        ("نطق IPA",           "SELECT COUNT(DISTINCT word) FROM ipa"),
        ("معانٍ",             "SELECT COUNT(DISTINCT word) FROM senses"),
        ("تصريفات",           "SELECT COUNT(DISTINCT word) FROM forms"),
        ("مرادفات",           "SELECT COUNT(DISTINCT word) FROM relations WHERE rel='synonym'"),
        ("أضداد",             "SELECT COUNT(DISTINCT word) FROM relations WHERE rel='antonym'"),
        ("مشتقات",            "SELECT COUNT(DISTINCT word) FROM relations WHERE rel='derived'"),
        ("أمثلة مترجمة",      "SELECT COUNT(DISTINCT word) FROM examples WHERE ar IS NOT NULL"),
        ("متلازمات",          "SELECT COUNT(DISTINCT word) FROM collocations"),
        ("أفعال مركّبة",       "SELECT COUNT(DISTINCT base) FROM phrasal_verbs"),
        ("تعابير",            "SELECT COUNT(DISTINCT word) FROM idioms"),
        ("ملاحظات استعمال",   "SELECT COUNT(DISTINCT word) FROM usage_notes"),
    ]
    for label, q in checks:
        n = db.execute(q).fetchone()[0]
        pct = 100 * n / n_vocab if n_vocab else 0
        bar = "█" * int(pct / 4) + "░" * (25 - int(pct / 4))
        print(f"  {label:<18} {bar} {n:>6,}  {pct:5.1f}%")
    print("═" * 58)
    print(f"  المفردات: {n_vocab:,} · الحجم: "
          f"{os.path.getsize(OUT_DB)/1e6:.0f} م.ب")
    print("═" * 58 + "\n")


# ───────────────────────── تعريب النطق ─────────────────────────
#
# الجدول المعتمد في arabic_phonemes.md — عُرف التطبيق القائم مع إصلاح
# العيوب الثلاثة: الشوا كانت تسقط، و/ɛ/ الابتدائية كانت «ي»، و/ɒ/ كانت تسقط.
#
# الترتيب حاسم: الرموز المركّبة أوّلاً، وإلا فُسِّر «tʃ» حرفين منفصلين.

_MEDIAL = [
    ("tʃ", "تش"), ("dʒ", "ج"),
    ("aɪ", "اي"), ("aʊ", "او"), ("eɪ", "ي"), ("ɔɪ", "وي"),
    ("oʊ", "و"), ("əʊ", "و"), ("ɪə", "ير"), ("eə", "ير"), ("ʊə", "ور"),
    ("iː", "ي"), ("ɑː", "ا"), ("ɔː", "و"), ("uː", "و"), ("ɜː", "ير"),
    ("p", "ب"), ("b", "ب"), ("t", "ت"), ("d", "د"), ("k", "ك"),
    ("g", "ق"), ("ɡ", "ق"), ("f", "ف"), ("v", "ڤ"), ("θ", "ث"),
    ("ð", "ذ"), ("s", "س"), ("z", "ز"), ("ʃ", "ش"), ("ʒ", "ج"),
    ("h", "هـ"), ("m", "م"), ("n", "ن"), ("ŋ", "نق"), ("l", "ل"),
    ("ɹ", "ر"), ("r", "ر"), ("j", "ي"), ("w", "و"), ("x", "خ"),
    ("ɝ", "ير"), ("ɚ", "ر"),
    ("ɪ", "ي"), ("i", "ي"), ("e", "ي"), ("ɛ", "ي"), ("æ", "َ"),
    ("ɒ", "و"), ("ɑ", "ا"), ("ɔ", "و"), ("ʊ", "ُ"), ("u", "و"),
    ("ʌ", "َ"), ("ə", "َ"), ("ɐ", "َ"), ("ʉ", "و"), ("ɜ", "ير"),
]

# الصائت في أوّل الكلمة يحتاج حاملاً — وهذا موضع العيبين المُصلَحين
_INITIAL = {
    "iː": "إي", "ɪ": "إ", "i": "إي", "e": "إ", "ɛ": "إ", "æ": "أ",
    "ɑː": "آ", "ɑ": "آ", "ɒ": "أو", "ɔː": "أو", "ɔ": "أو",
    "ʊ": "أُ", "uː": "أو", "u": "أو", "ʌ": "أ", "ə": "أ",
    "ɜː": "إير", "ɝ": "إير", "eɪ": "إي", "aɪ": "آي", "ɔɪ": "أوي",
    "aʊ": "آو", "oʊ": "أو", "əʊ": "أو", "ɪə": "إير", "eə": "إير",
    "ʊə": "أور",
}

# ما ليس صوتاً: الأقواس والنبر والفواصل المقطعية وعلامات التشكيل المركّبة.
# كُتبت بالرموز الهروبية عمداً: محارف النبر والتشكيل الحرفية تتعطّب في
# النسخ والنقل بين المحرّرات، فينكسر التحويل بلا أثرٍ ظاهر.
#   U+02B0–U+02FF  حروف التعديل: النبر الأوّلي والثانوي وعلامة الطول
#   U+0300–U+036F  التشكيل المركّب: علامتا المقطعية وغيرهما
# ورموز IPA نفسها تقع في U+0250–U+02AF فلا يمسّها شيء من هذين النطاقين.
_NOISE = re.compile(
    "[/\\[\\]()\\s|.]"          # أقواس وفواصل مقطعية
    "|[\\u203f\\u2040]"         # الرابط بين الكلمات
    "|[\\u02b0-\\u02ff]"        # حروف التعديل: النبر والطول
    "|[\\u0300-\\u036f]"        # التشكيل المركّب: المقطعية وغيرها
)


def ar_pron(ipa: str) -> str:
    """يحوّل IPA إلى نطق عربي بالجدول المعتمد. سلسلة فارغة إن تعذّر."""
    if not ipa:
        return ""
    s = _NOISE.sub("", ipa.split(",")[0])
    out, i, first = [], 0, True
    while i < len(s):
        for sym, ar in _MEDIAL:
            if s.startswith(sym, i):
                out.append(_INITIAL.get(sym, ar) if first else ar)
                i += len(sym)
                first = False
                break
        else:
            i += 1          # رمز لا نعرفه — نتخطّاه ولا نُفسد الكلمة
    return "".join(out)


# ───────────────────────── تدقيق وإصلاح ─────────────────────────

# مفتاح الهوية لكل جدول: صفّان يتطابقان فيه صفٌّ واحد مكرّر لا معلومتان
DEDUPE_KEYS = {
    "ipa":           ("word", "accent", "value", "source"),
    "senses":        ("word", "pos", "idx", "gloss", "source"),
    "forms":         ("word", "form", "tags"),
    "relations":     ("word", "rel", "target", "source"),
    "examples":      ("word", "en", "ar", "source"),
    "collocations":  ("word", "pattern", "collocate"),
    "phrasal_verbs": ("base", "phrase", "gloss"),
    "idioms":        ("word", "phrase", "gloss"),
    "usage_notes":   ("word", "note"),
}


def audit(db=None) -> dict:
    """
    يثبت التكرار داخل القاعدة أو ينفيه — قبل أي إصلاح.

    ادّعيتُ أن التكرار في القاعدة استناداً إلى مضاعفةٍ رأيتُها في بطاقة
    معروضة. لكن ما يظهر في العرض قد يكون من العرض. فالفرق بين
    COUNT(*) وCOUNT(DISTINCT مفتاح) يحسم المسألة في ثانية، ولا يحتاج
    إعادة بناء ولا ظنّاً.
    """
    close = db is None
    db = db or sqlite3.connect(OUT_DB)

    print("\n" + "═" * 68)
    print("  تدقيق التكرار داخل القاعدة")
    print("═" * 68)
    print(f"  {'الجدول':<16}{'صفوف':>10}{'فريدة':>10}{'مكرّرة':>10}"
          f"{'النسبة':>9}")
    print("─" * 68)

    out, duped = {}, 0
    for table, cols in DEDUPE_KEYS.items():
        key = "||'\x1f'||".join(f"COALESCE({c},'')" for c in cols)
        total = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        uniq = db.execute(
            f"SELECT COUNT(*) FROM (SELECT DISTINCT {key} FROM {table})"
        ).fetchone()[0]
        extra = total - uniq
        ratio = total / uniq if uniq else 0
        duped += extra
        flag = "  ◀ مضاعَف" if ratio >= 1.95 else ("  ◀" if extra else "")
        print(f"  {table:<16}{total:>10,}{uniq:>10,}{extra:>10,}"
              f"{ratio:>8.2f}×{flag}")
        out[table] = {"total": total, "unique": uniq, "extra": extra,
                      "ratio": round(ratio, 2)}

    print("─" * 68)
    if duped == 0:
        print("  ✅ لا تكرار داخل القاعدة — العيب في العرض/التصدير وحده")
    else:
        print(f"  ⚠ {duped:,} صفاً مكرّراً داخل القاعدة")
        print("     نسبة ٢.٠٠× تعني أن المرحلة جرت مرّتين")

    # الأفعال المركّبة: هل تسرّبت إليها تعابير ليست أفعالاً مركّبة؟
    bad = db.execute(f"""
        SELECT COUNT(DISTINCT phrase) FROM phrasal_verbs
        WHERE INSTR(phrase,' ')=0
           OR INSTR(TRIM(SUBSTR(phrase, INSTR(phrase,' ')+1)),' ')>0
           OR LOWER(TRIM(SUBSTR(phrase, INSTR(phrase,' ')+1)))
              NOT IN ({','.join(repr(p) for p in sorted(PARTICLES))})
    """).fetchone()[0]
    both = db.execute("SELECT COUNT(*) FROM phrasal_verbs p"
                      " JOIN idioms i ON i.phrase = p.phrase").fetchone()[0]
    print(f"\n  أفعال مركّبة ليست كذلك (فعل + جملة اسمية): {bad:,}")
    print(f"  عبارات تظهر في الحقلين معاً: {both:,}")
    print("═" * 68 + "\n")
    out["_bad_pv"] = bad
    out["_in_both"] = both
    if close:
        db.close()
    return out


def repair(apply: bool = False) -> None:
    """
    إصلاحٌ داخل القاعدة بلا إعادة بناء — ثوانٍ بدل أربعين دقيقة.

    إعادة تشغيل المراحل تعيد تنزيلاً ومعالجةً لتصل إلى ما يصل إليه
    DELETE في ثانية. والأصل ألّا يُطلب من المستخدم وقتٌ يمكن ألّا يُدفع.

    @param apply عرضٌ فقط ما لم تُطلب الكتابة صراحةً.
    """
    db = sqlite3.connect(OUT_DB)
    db.isolation_level = None
    mode = "تنفيذ" if apply else "عرض فقط (apply=True للتنفيذ)"
    print(f"\n{'═'*68}\n  إصلاح — {mode}\n{'═'*68}")

    plan = []
    for table, cols in DEDUPE_KEYS.items():
        grp = ", ".join(f"COALESCE({c},'')" for c in cols)
        sql = (f"DELETE FROM {table} WHERE rowid NOT IN "
               f"(SELECT MIN(rowid) FROM {table} GROUP BY {grp})")
        n = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] - \
            db.execute(f"SELECT COUNT(*) FROM (SELECT DISTINCT {grp}"
                       f" FROM {table})").fetchone()[0]
        if n > 0:
            plan.append((f"حذف {n:,} صفاً مكرّراً من {table}", sql))

    # فعلٌ + جملة اسمية ليس فعلاً مركّباً — «assume the position» تعبير
    particles = ",".join(repr(p) for p in sorted(PARTICLES))
    pv_sql = f"""DELETE FROM phrasal_verbs
        WHERE INSTR(phrase,' ')=0
           OR INSTR(TRIM(SUBSTR(phrase, INSTR(phrase,' ')+1)),' ')>0
           OR LOWER(TRIM(SUBSTR(phrase, INSTR(phrase,' ')+1)))
              NOT IN ({particles})"""
    n = db.execute(f"SELECT COUNT(*) FROM phrasal_verbs WHERE"
                   f" INSTR(phrase,' ')=0"
                   f" OR INSTR(TRIM(SUBSTR(phrase, INSTR(phrase,' ')+1)),' ')>0"
                   f" OR LOWER(TRIM(SUBSTR(phrase, INSTR(phrase,' ')+1)))"
                   f" NOT IN ({particles})").fetchone()[0]
    if n:
        plan.append((f"حذف {n:,} عبارة من phrasal_verbs (ليست أفعالاً مركّبة)",
                     pv_sql))

    # «give up» فعلٌ مركّب واصطلاحيّ معاً، فيظهر في الحقلين. والقاعدة أن
    # ما كان فعلاً مركّباً يُعرض فعلاً مركّباً وحده — الشرح واحد في الحالين.
    n = db.execute("SELECT COUNT(*) FROM idioms WHERE phrase IN"
                   " (SELECT phrase FROM phrasal_verbs)").fetchone()[0]
    if n:
        plan.append((f"حذف {n:,} تعبيراً هو أصلاً فعل مركّب",
                     "DELETE FROM idioms WHERE phrase IN"
                     " (SELECT phrase FROM phrasal_verbs)"))

    junk = ",".join(repr(j) for j in sorted(FORM_JUNK))
    n = db.execute(f"SELECT COUNT(*) FROM forms WHERE LOWER(form)"
                   f" IN ({junk})").fetchone()[0]
    if n:
        plan.append((f"حذف {n:,} صيغة زائفة من forms (بقايا جداول)",
                     f"DELETE FROM forms WHERE LOWER(form) IN ({junk})"))

    if not plan:
        print("  لا شيء يُصلَح — القاعدة نظيفة\n" + "═" * 68)
        db.close()
        return

    for desc, _ in plan:
        print(f"  • {desc}")

    if apply:
        print("─" * 68)
        for desc, sql in plan:
            db.execute(sql)
            print(f"  ✓ {desc}")
        db.execute("VACUUM")
        print(f"  ✓ ضغط الملف — "
              f"{os.path.getsize(OUT_DB)/1e6:.0f} م.ب")
    print("═" * 68 + "\n")
    db.close()


# ───────────────────────── فحص شكل الاستخراج ─────────────────────────

def inspect_wiktextract(sample=("issue", "assume", "abide", "affect",
                                "make", "since", "few"), limit=3_000_000):
    """
    يكشف أسماء الحقول الفعلية في ملف ويكاموس.

    خرجت «ملاحظات الاستعمال» صفراً، ولا أعلم يقيناً أهو شحّ في المصدر أم
    اسم حقلٍ خاطئ عندي — وويكاموس فيه أقسام Usage notes لكلماتٍ كثيرة.
    والتخمين هنا رخيص والخطأ مكلف: جولةُ بناءٍ كاملة ثمناً لاسمٍ مظنون.

    فنقرأ من البيانات: أيّ مفاتيح موجودة فعلاً، وأين يقع ما يشبه الملاحظة —
    في جذر المدخل أم داخل المعاني.
    """
    path = os.path.join(WORK, "wiktextract-en.jsonl")
    if not os.path.exists(path):
        print("ملف ويكاموس غير موجود — شغّل مرحلة wiktionary أوّلاً")
        return

    want_words = {w.lower() for w in sample}
    top_keys, sense_keys = Counter(), Counter()
    note_like, hits = [], 0

    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= limit:
                break
            line = line.strip().rstrip(",")
            if not line or line[0] != "{":
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(e, dict) or e.get("lang_code") != "en":
                continue

            top_keys.update(e.keys())
            for s in (e.get("senses") or [])[:3]:
                if isinstance(s, dict):
                    sense_keys.update(s.keys())

            if (e.get("word") or "").lower() in want_words:
                hits += 1
                for k, v in e.items():
                    if "note" in k.lower() or "usage" in k.lower():
                        note_like.append((e["word"], f"جذر:{k}", repr(v)[:220]))
                for s in (e.get("senses") or []):
                    if not isinstance(s, dict):
                        continue
                    for k, v in s.items():
                        if "note" in k.lower() or "usage" in k.lower():
                            note_like.append(
                                (e["word"], f"معنى:{k}", repr(v)[:220]))

    print("\n" + "═" * 64)
    print(f"  فحص شكل الاستخراج — {i:,} سطراً · {hits} مدخلاً من العيّنة")
    print("═" * 64)
    print("\n▸ مفاتيح جذر المدخل (الأشيع):")
    for k, n in top_keys.most_common(28):
        mark = " ◀ ملاحظة؟" if ("note" in k.lower() or "usage" in k.lower()) else ""
        print(f"   {k:<24} {n:>9,}{mark}")
    print("\n▸ مفاتيح المعاني (الأشيع):")
    for k, n in sense_keys.most_common(20):
        mark = " ◀ ملاحظة؟" if ("note" in k.lower() or "usage" in k.lower()) else ""
        print(f"   {k:<24} {n:>9,}{mark}")

    print("\n▸ ما وُجد فعلاً في كلمات العيّنة:")
    if not note_like:
        print("   لا شيء — الملاحظات ليست في هذا الاستخراج أصلاً")
    for w, where, v in note_like[:12]:
        print(f"   [{w}] {where}\n      {v}")
    print("═" * 64 + "\n")
    return {"top": dict(top_keys.most_common(40)),
            "sense": dict(sense_keys.most_common(30)),
            "found": note_like[:20]}


# ───────────────────────── التقرير الفعلي ─────────────────────────

def full_report(db=None) -> dict:
    """أرقام مقيسة من القاعدة — لا تقديرات."""
    close = db is None
    db = db or sqlite3.connect(OUT_DB)
    total = db.execute("SELECT COUNT(*) FROM vocab").fetchone()[0]

    # الحقول السبعة المطلوبة صراحةً، ثم البقية
    fields = [
        ("IPA",              "SELECT COUNT(DISTINCT word) FROM ipa", True),
        ("CEFR",             "SELECT COUNT(*) FROM vocab WHERE cefr IS NOT NULL", True),
        ("Derivatives",      "SELECT COUNT(DISTINCT word) FROM relations WHERE rel='derived'", True),
        ("Examples",         "SELECT COUNT(DISTINCT word) FROM examples", True),
        ("Collocations",     "SELECT COUNT(DISTINCT word) FROM collocations", True),
        ("Idioms",           "SELECT COUNT(DISTINCT word) FROM idioms", True),
        ("Usage Notes",      "SELECT COUNT(DISTINCT word) FROM usage_notes", True),
        ("Examples (بترجمة)", "SELECT COUNT(DISTINCT word) FROM examples WHERE ar IS NOT NULL AND ar<>''", False),
        ("Meanings",         "SELECT COUNT(DISTINCT word) FROM senses", False),
        ("Meanings (بعربية)", "SELECT COUNT(DISTINCT word) FROM senses WHERE ar IS NOT NULL AND ar<>''", False),
        ("Inflections",      "SELECT COUNT(DISTINCT word) FROM forms", False),
        ("Synonyms",         "SELECT COUNT(DISTINCT word) FROM relations WHERE rel='synonym'", False),
        ("Antonyms",         "SELECT COUNT(DISTINCT word) FROM relations WHERE rel='antonym'", False),
        ("Phrasal verbs",    "SELECT COUNT(DISTINCT base) FROM phrasal_verbs", False),
        ("Oxford",           "SELECT COUNT(*) FROM vocab WHERE oxford IS NOT NULL", False),
        ("Register tags",    "SELECT COUNT(DISTINCT word) FROM senses WHERE tags<>''", False),
    ]

    print("\n" + "═" * 64)
    print(f"  تقرير فعلي — {time.strftime('%Y-%m-%d %H:%M')}")
    print("═" * 64)
    print(f"  الكلمات المستخرجة: {total:,}")
    print("─" * 64)
    print(f"  {'الحقل':<20}{'كلمات':>10}{'التغطية':>12}")
    print("─" * 64)

    out = {"total": total, "fields": {}}
    for label, q, requested in fields:
        if not requested and label == "Examples (بترجمة)":
            print("─" * 64)
        n = db.execute(q).fetchone()[0]
        pct = 100 * n / total if total else 0
        bar = "█" * int(pct / 5) + "░" * (20 - int(pct / 5))
        mark = "▸" if requested else " "
        print(f" {mark}{label:<20}{n:>9,}  {pct:5.1f}%  {bar}")
        out["fields"][label] = {"words": n, "pct": round(pct, 1)}

    rows = {t: db.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            for t in ("senses", "examples", "collocations", "relations",
                      "forms", "idioms", "phrasal_verbs")}
    print("─" * 64)
    print("  إجمالي السجلات: " +
          " · ".join(f"{k}={v:,}" for k, v in rows.items()))
    print(f"  حجم القاعدة: {os.path.getsize(OUT_DB)/1e6:.0f} م.ب")
    print("═" * 64 + "\n")
    out["rows"] = rows
    if close:
        db.close()
    return out


def my_coverage(words=None) -> dict:
    """
    التغطية على كلمات المستخدم وحدها.

    النسبة العامّة على ١٨ ألف كلمة تخدع: أكثرها نادرٌ لا يملكه أحد.
    والرقم الذي يهمّ صاحب المكتبة هو تغطية كلماته هو.
    """
    db = sqlite3.connect(OUT_DB)
    if words is None:
        words = user_words()

    resolved, missing = {}, []
    for w in words:
        r = resolve_word(db, w)
        (resolved.setdefault(r, w) if r else missing.append(w))

    n = len(resolved)
    direct = sum(1 for r, w in resolved.items() if r == w)
    print("\n" + "═" * 60)
    print(f"  تغطية كلماتك — {len(words)} كلمة")
    print("═" * 60)
    print(f"  في القاعدة مباشرةً : {direct}")
    print(f"  رُدّت إلى مدخلها   : {n - direct}")
    print(f"  غير مغطّاة         : {len(missing)}")
    print("─" * 60)
    if not n:
        db.close()
        return {}

    db.execute("DROP TABLE IF EXISTS temp.mine")
    db.execute("CREATE TEMP TABLE mine(word TEXT PRIMARY KEY)")
    db.executemany("INSERT OR IGNORE INTO mine VALUES(?)",
                   [(w,) for w in resolved])

    inq = " word IN (SELECT word FROM mine)"
    checks = [
        ("IPA",            f"SELECT COUNT(DISTINCT word) FROM ipa WHERE{inq}"),
        ("معانٍ",          f"SELECT COUNT(DISTINCT word) FROM senses WHERE{inq}"),
        ("معانٍ بعربية",   f"SELECT COUNT(DISTINCT word) FROM senses WHERE ar IS NOT NULL AND ar<>'' AND{inq}"),
        ("CEFR",           f"SELECT COUNT(*) FROM vocab WHERE cefr IS NOT NULL AND{inq}"),
        ("تصريفات",        f"SELECT COUNT(DISTINCT word) FROM forms WHERE{inq}"),
        ("مرادفات",        f"SELECT COUNT(DISTINCT word) FROM relations WHERE rel='synonym' AND{inq}"),
        ("أضداد",          f"SELECT COUNT(DISTINCT word) FROM relations WHERE rel='antonym' AND{inq}"),
        ("مشتقات",         f"SELECT COUNT(DISTINCT word) FROM relations WHERE rel='derived' AND{inq}"),
        ("أمثلة",          f"SELECT COUNT(DISTINCT word) FROM examples WHERE{inq}"),
        ("أمثلة بعربية",   f"SELECT COUNT(DISTINCT word) FROM examples WHERE ar IS NOT NULL AND ar<>'' AND{inq}"),
        ("متلازمات",       f"SELECT COUNT(DISTINCT word) FROM collocations WHERE{inq}"),
        ("أفعال مركّبة",    f"SELECT COUNT(DISTINCT base) FROM phrasal_verbs WHERE base IN (SELECT word FROM mine)"),
        ("تعابير",         f"SELECT COUNT(DISTINCT word) FROM idioms WHERE{inq}"),
        ("سجل/سياق",       f"SELECT COUNT(DISTINCT word) FROM senses WHERE tags<>'' AND{inq}"),
    ]
    out = {}
    for label, q in checks:
        c = db.execute(q).fetchone()[0]
        pct = 100 * c / n
        bar = "█" * round(pct / 5) + "░" * (20 - round(pct / 5))
        print(f"  {label:<14} {bar} {c:>3}/{n}  {pct:5.1f}%")
        out[label] = round(pct, 1)

    if missing:
        print("─" * 60)
        print(f"  غير مغطّاة ({len(missing)}): "
              f"{', '.join(missing[:14])}{' …' if len(missing) > 14 else ''}")
    print("═" * 60 + "\n")
    db.close()
    return out


# ───────────────────────── بطاقات حقيقية ─────────────────────────

def user_words() -> list:
    """
    كلمات المستخدم الفعلية من مستودعه العام.

    الملف JSON غير صالح: حقل «last» يُكتب بقيمة فاسدة في كل سجلّ —
    رأيتُ «0null» و«0"right"»، وقد تظهر أشكال أخرى. فلا نطارد الأشكال
    واحداً واحداً بل نُصفّر الحقل كله: قيمته طابعٌ زمني لا نستعمله.

    والإصلاح في الذاكرة فقط. لا يُكتب شيء على القرص ولا على المستودع —
    البيانات بياناته، وإصلاحها قرارٌ منفصل يخصّه هو.
    """
    url = f"{REPO_RAW}/android/app/src/main/assets/words.json"
    raw = urllib.request.urlopen(url, timeout=60).read().decode("utf-8")

    fixed, n = re.subn(r'"last"\s*:\s*[^,\n}\]]*', '"last": 0', raw)
    try:
        data = json.loads(fixed)
    except json.JSONDecodeError as e:
        raise SystemExit(
            f"تعذّر تحليل words.json حتى بعد إصلاح {n} حقلاً: {e}\n"
            f"السياق: ...{fixed[max(0, e.pos-90):e.pos+90]}...")
    if n:
        log(f"words.json: صُحّح {n} حقل «last» فاسد (في الذاكرة فقط)")
    return [w["word"].strip().lower()
            for w in data.get("words", [])
            if w.get("word") and not w.get("deletedAt")]


# بقايا جداول ويكاموس تتسرّب إلى التصريفات — ليست صيغاً بل وسوم بنية
FORM_JUNK = {"no-table-tags", "glossary", "table-tags", "inflection-template",
             "class", "-", "—"}

# اقتباسات ويكاموس أدبية وقديمة غالباً: «She used by way of Provocative, to
# read the wanton Verses of her Paramour». صحيحةٌ لغوياً وعديمة النفع لمتعلّم.
ARCHAIC = re.compile(r"\[…\]|\[\.\.\.\]|\bthou\b|\bthee\b|\bthy\b|"
                     r"\bhath\b|\bdoth\b|\bunto\b|\bwhilst\b|\bshalt\b")

# أسطر الإحالة في ويكاموس ليست أمثلة: «Near-synonyms: gift; blessing…»
# تتسرّب إلى حقل الأمثلة فتُعرض جملةً وهي فهرس.
NOT_EXAMPLE = re.compile(
    r"^\s*(near-?synonyms?|synonyms?|antonyms?|coordinate terms?|"
    r"hypernyms?|hyponyms?|see also|compare|thesaurus|usage|meronyms?)\s*:",
    re.I)


def _dedupe(rows, key):
    """يحذف المكرّر مع حفظ الترتيب — أوّل ظهورٍ يفوز."""
    seen, out = set(), []
    for r in rows:
        k = key(r)
        if k and k not in seen:
            seen.add(k)
            out.append(r)
    return out


def resolve_word(db, word: str):
    """
    يردّ الصيغة المصرَّفة إلى مدخلها.

    مكتبة المستخدم فيها ما يُقرأ لا ما يُفهرس: «glaciers» و«foraging»
    و«pathologies». والقاعدة مفهرسة بالمداخل، فثلثا كلماته كانت تخرج
    فارغةً وهي مغطّاة تماماً تحت «glacier» و«forage» و«pathology».

    وجدول `forms` يحمل الربط أصلاً — ٥٩ ألف صيغة موسومة من ويكاموس،
    فيها الشاذّ الذي لا تبلغه قاعدة اشتقاق. نقرؤه معكوساً، والقواعد
    البسيطة احتياطٌ لما لا يغطّيه.

    @return المدخل، أو None إن لم يوجد.
    """
    w = (word or "").strip().lower()
    if not w:
        return None
    if db.execute("SELECT 1 FROM vocab WHERE word=?", (w,)).fetchone():
        return w

    # الأشيع يفوز عند تعدّد المداخل لصيغةٍ واحدة
    row = db.execute(
        "SELECT f.word FROM forms f JOIN vocab v ON v.word = f.word"
        " WHERE f.form = ? ORDER BY COALESCE(v.freq_rank, 999999) LIMIT 1",
        (w,)).fetchone()
    if row:
        return row[0]

    for suf, rep in (("ies", "y"), ("ves", "f"), ("es", ""), ("s", ""),
                     ("ing", ""), ("ing", "e"), ("ed", ""), ("ed", "e"),
                     ("ly", ""), ("er", ""), ("est", "")):
        if w.endswith(suf) and len(w) - len(suf) >= 3:
            cand = w[: -len(suf)] + rep
            if db.execute("SELECT 1 FROM vocab WHERE word=?",
                          (cand,)).fetchone():
                return cand
    return None


def build_card(db, word: str) -> dict:
    """بطاقة واحدة من القاعدة — كل حقل بحالته الصريحة، ومنقّى من التكرار."""
    def q(sql, n=None):
        rows = db.execute(sql, (word,)).fetchall()
        return rows[:n] if n else rows

    v = db.execute("SELECT freq_rank,oxford,cefr,audio_us,audio_uk"
                   " FROM vocab WHERE word=?", (word,)).fetchone()
    freq, oxford, cefr = (v[0], v[1], v[2]) if v else (None, None, None)

    ipa_rows = q("SELECT accent,value FROM ipa WHERE word=? ORDER BY accent")
    ipa = {a: val for a, val in ipa_rows}
    # الأمريكي أوّلاً للتعريب: البريطاني غير راثيّ فتسقط منه الراء، فتخرج
    # «articulation» بلا راء — «آتيكيَليشَن». كشفه التشغيل لا القراءة.
    best = ipa.get("us") or ipa.get("gen") or ipa.get("uk") or ""

    # ويكاموس أوّلاً: تعريفاته أوفى، وWordNet يكمل ما نقص بلا تكرار معناه
    # ترتيب الأولوية للمتعلّم، لا للمعجمي:
    #   ١ ما له ترجمة عربية      — أثمن ما في البطاقة
    #   ٢ غير المهجور            — «archaic/obsolete» يُؤخَّر لا يُحذف
    #   ٣ WordNet قبل ويكاموس    — تعريفاته موجزة، وويكاموس يسهب في
    #     المعاني الحرفية النادرة («القسم الخشبي من الكتّان بعد التعطين»)
    raw = q("""SELECT DISTINCT pos,gloss,tags,source,ar,ar_src FROM senses
               WHERE word=? AND pos <> 'name'
               ORDER BY (ar IS NULL OR ar=''),
                        (tags LIKE '%archaic%' OR tags LIKE '%obsolete%'
                         OR tags LIKE '%dated%'),
                        source DESC, idx""")
    senses = _dedupe(raw, lambda r: re.sub(r"\W+", " ",
                                           (r[1] or "").lower()).strip())[:6]
    tags = sorted({t for _, _, tg, _, _, _ in senses
                   for t in (tg or "").split(",") if t})

    def rel(kind, n=8):
        return [r[0] for r in db.execute(
            "SELECT DISTINCT target FROM relations WHERE word=? AND rel=?"
            " LIMIT ?", (word, kind, n))]

    # المترجَم أوّلاً ثم الأقصر — والاقتباس القديم يُستبعد ما دام يوجد بديل
    ex_all = [r for r in _dedupe(
        q("SELECT DISTINCT en,ar,source,ar_src FROM examples WHERE word=?"
          " ORDER BY (ar IS NULL OR ar=''), length(en)"),
        lambda r: (r[0] or "").lower().strip())
        if not NOT_EXAMPLE.match(r[0] or "")]
    ex = [r for r in ex_all if not ARCHAIC.search(r[0] or "")][:3] \
        or ex_all[:3]

    return {
        "word": word,
        "freq_rank": freq, "oxford": oxford, "cefr": cefr,
        "ipa": ipa, "arabicPron": ar_pron(best),
        "pos": sorted({p for p, _, _, _, _, _ in senses if p}),
        "meanings": [{"pos": p, "en": g, "ar": a, "arSrc": asrc, "src": s}
                     for p, g, _, s, a, asrc in senses],
        "inflections": [r[0] for r in _dedupe(
            [r for r in q("SELECT DISTINCT form FROM forms WHERE word=?")
             if r[0] and r[0].lower() not in FORM_JUNK
             and r[0].lower() != word],
            lambda r: r[0].lower())][:8],
        "derivatives": rel("derived"),
        "synonyms": rel("synonym"),
        "antonyms": rel("antonym"),
        "examples": [{"en": e, "ar": a, "src": s, "arSrc": asrc} for e, a, s, asrc in ex],
        "collocations": [{"pat": p, "col": c, "score": sc} for p, c, sc in q(
            "SELECT pattern,collocate,score FROM collocations WHERE word=?"
            " ORDER BY score DESC", 8)],
        "phrasalVerbs": [{"phrase": p, "gloss": g} for p, g in _dedupe(
            db.execute("SELECT DISTINCT phrase,gloss FROM phrasal_verbs"
                       " WHERE base=?", (word,)).fetchall(),
            lambda r: r[0].lower())[:5]],
        "idioms": [{"phrase": p, "gloss": g} for p, g in _dedupe(
            q("SELECT DISTINCT phrase,gloss FROM idioms WHERE word=?"),
            lambda r: r[0].lower())[:5]],
        "usageNotes": [r[0] for r in q(
            "SELECT note FROM usage_notes WHERE word=? LIMIT 2")],
        "register": tags,
    }


def _pct_filled(c: dict) -> float:
    """نسبة الحقول الثمانية عشر التي فيها محتوى فعلي."""
    checks = [
        bool(c["ipa"]), bool(c["arabicPron"]), bool(c["oxford"]),
        bool(c["cefr"]), bool(c["pos"]), bool(c["meanings"]),
        bool(c["inflections"]), bool(c["derivatives"]), bool(c["synonyms"]),
        bool(c["antonyms"]), bool(c["examples"]), bool(c["collocations"]),
        bool(c["phrasalVerbs"]), bool(c["idioms"]), bool(c["usageNotes"]),
        False,                      # ١٦ أخطاء المتعلّم — لا مصدر مجاني
        bool(c["synonyms"] and c["collocations"]),   # ١٧ الفروق: قابلة للاشتقاق
        bool(c["register"]),
    ]
    return 100 * sum(checks) / len(checks)


def sample_cards(n: int = 10, words=None, html: str = "/content/cards.html"):
    """
    عشر بطاقات حقيقية من كلمات المستخدم، موزّعة على مدى الشيوع.

    التوزيع مقصود: بطاقة لكلمة شائعة وأخرى لنادرة تكشفان مدى التفاوت،
    وهو ما لا يظهر لو انتقينا الأسهل.
    """
    db = sqlite3.connect(OUT_DB)
    if words is None:
        try:
            words = user_words()
            src = "مكتبتك"
        except Exception as e:                                   # noqa: BLE001
            log(f"تعذّر جلب كلماتك ({e}) — أستعمل عيّنة مرجعية")
            words = ["issue", "assume", "abide", "articulation", "lettuce",
                     "provocative", "boon", "rotunda", "physicist", "conflate"]
            src = "عيّنة مرجعية"
    else:
        src = "قائمتك"

    # الصيغة المصرَّفة تُردّ إلى مدخلها، فلا تسقط كلمةٌ مغطّاة لأن صاحبها
    # كتبها كما قرأها لا كما تُفهرس
    resolved, direct = {}, 0
    for w in words:
        r = resolve_word(db, w)
        if r:
            resolved.setdefault(r, w)
            direct += (r == w)
    known = set(resolved)
    if len(known) > direct:
        log(f"رُدّت {len(known) - direct} صيغة مصرَّفة إلى مدخلها")
    ranked = sorted(known, key=lambda w: db.execute(
        "SELECT COALESCE(freq_rank, 99999) FROM vocab WHERE word=?",
        (w,)).fetchone()[0])
    if not ranked:
        print("لا كلمة من مصدرك موجودة في القاعدة")
        return []
    step = max(1, len(ranked) // n)
    picked = ranked[::step][:n]

    print(f"\nالمصدر: {src} · {len(words)} كلمة · "
          f"{len(known)} منها في القاعدة · عيّنة موزّعة على الشيوع\n")

    cards = []
    for w in picked:
        c = build_card(db, w)
        cards.append(c)
        pct = _pct_filled(c)
        print("═" * 64)
        print(f"  {w.upper()}   ({pct:.0f}% ممتلئ · rank "
              f"{c['freq_rank'] or '—'} · {c['oxford'] or '—'} "
              f"{c['cefr'] or ''})")
        print("═" * 64)
        print(f"  IPA        : {c['ipa'] or '—'}")
        print(f"  نطق عربي   : {c['arabicPron'] or '—'}")
        print(f"  نوع        : {', '.join(c['pos']) or '—'}")
        for m in c["meanings"][:3]:
            print(f"  معنى ({m['src'][:4]}): {m['en'][:70]}")
        for k, label in (("inflections", "تصريفات"), ("derivatives", "مشتقات"),
                         ("synonyms", "مرادفات"), ("antonyms", "أضداد"),
                         ("register", "سجل")):
            print(f"  {label:<10} : {', '.join(c[k][:6]) if c[k] else '— فارغ'}")
        print(f"  متلازمات   : "
              f"{', '.join(x['col'] for x in c['collocations'][:6]) or '— فارغ'}")
        for e in c["examples"][:2]:
            print(f"  مثال       : {e['en'][:60]}")
            print(f"               {e['ar'][:60] if e['ar'] else '— بلا ترجمة'}")
        print(f"  تعابير     : "
              f"{', '.join(x['phrase'] for x in c['idioms'][:3]) or '— فارغ'}")
        print(f"  أفعال مركّبة: "
              f"{', '.join(x['phrase'] for x in c['phrasalVerbs'][:3]) or '— فارغ'}")
        print(f"  استعمال    : "
              f"{(c['usageNotes'][0][:70] + '…') if c['usageNotes'] else '— فارغ'}")
        print()

    avg = sum(_pct_filled(c) for c in cards) / len(cards)
    print("═" * 64)
    print(f"  متوسط الاكتمال عبر {len(cards)} بطاقة: {avg:.0f}%")
    print("═" * 64)

    if html:
        _cards_html(cards, html)
        print(f"  وHTML للعرض: {html}")
    with open(os.path.join(WORK, "sample_cards.json"), "w",
              encoding="utf-8") as f:
        json.dump(cards, f, ensure_ascii=False, indent=1)
    db.close()
    return cards


def _cards_html(cards, path: str) -> None:
    """صفحة تُقرأ بالعين — الحكم على الجودة لا يكون من JSON."""
    def esc(s):
        return (str(s).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;"))

    parts = ["""<!doctype html><meta charset="utf-8">
<title>Tornado — بطاقات حقيقية</title><style>
body{font:15px/1.7 system-ui,sans-serif;max-width:900px;margin:24px auto;
padding:0 16px;background:#faf9f7;color:#1a1a1a}
.card{background:#fff;border:1px solid #e3e0da;border-radius:12px;
padding:18px 22px;margin:18px 0}
h2{margin:0 0 4px;font-size:24px}
.meta{color:#6b6b6b;font-size:13px;margin-bottom:14px}
.row{display:flex;gap:12px;padding:6px 0;border-top:1px solid #f0eee9}
.k{flex:0 0 130px;color:#6b6b6b;font-size:13px}
.v{flex:1}
.empty{color:#b9b4ab;font-style:italic}
.ar{direction:rtl;text-align:right}
.pill{display:inline-block;background:#f0ede6;border-radius:20px;
padding:2px 10px;margin:2px 3px 2px 0;font-size:13px}
.pct{float:left;font-weight:600}
@media(prefers-color-scheme:dark){body{background:#16150f;color:#eae7df}
.card{background:#211f18;border-color:#37342a}.k,.meta{color:#a09a8c}
.pill{background:#2e2b22}.row{border-color:#2b2820}}
</style><h1>بطاقات حقيقية — مولَّدة من القاعدة</h1>"""]

    for c in cards:
        pct = _pct_filled(c)
        parts.append(f'<div class="card"><span class="pct">{pct:.0f}%</span>'
                     f'<h2>{esc(c["word"])}</h2>'
                     f'<div class="meta">{esc(c["ipa"])} · '
                     f'<b class="ar">{esc(c["arabicPron"]) or "—"}</b> · '
                     f'{esc(c["oxford"] or "—")} · {esc(c["cefr"] or "—")} · '
                     f'rank {esc(c["freq_rank"] or "—")}</div>')

        def row(label, html_val, empty=False):
            v = (f'<span class="empty">— فارغ</span>' if empty else html_val)
            parts.append(f'<div class="row"><div class="k">{label}</div>'
                         f'<div class="v">{v}</div></div>')

        def pills(key, label):
            row(label, " ".join(f'<span class="pill">{esc(x)}</span>'
                                for x in c[key][:10]), not c[key])

        def _ar(o):
            """الترجمة الآلية تُعلَّم — البشرية لا تُخلط بالمولَّدة."""
            if not o.get("ar"):
                return ''
            mark = ' <small class="empty">آلية</small>' \
                if o.get("arSrc") == "mt" else ''
            return (f'<br><span class="ar"><b>{esc(o["ar"])}</b>'
                    f'{mark}</span>')

        row("المعاني", "<br>".join(
            f'<b>{esc(m["pos"])}</b> — {esc(m["en"])}' + _ar(m)
            + f' <span class="empty">[{esc(m["src"])}]</span>'
            for m in c["meanings"]), not c["meanings"])
        for k, lab in (("pos", "نوع الكلمة"), ("inflections", "التصريفات"),
                       ("derivatives", "المشتقات"), ("synonyms", "المرادفات"),
                       ("antonyms", "الأضداد"), ("register", "السجل/السياق")):
            pills(k, lab)
        row("المتلازمات", " ".join(
            f'<span class="pill">{esc(x["col"])} '
            f'<small>{x["score"]:.1f}</small></span>'
            for x in c["collocations"]), not c["collocations"])
        row("الأمثلة", "<br>".join(
            esc(e["en"]) + (_ar(e) or
                            '<br><span class="empty"><i>بلا ترجمة</i></span>')
            for e in c["examples"]), not c["examples"])
        row("التعابير", "<br>".join(
            f'<b>{esc(x["phrase"])}</b> — {esc(x["gloss"])[:90]}'
            for x in c["idioms"]), not c["idioms"])
        row("أفعال مركّبة", "<br>".join(
            f'<b>{esc(x["phrase"])}</b> — {esc(x["gloss"])[:90]}'
            for x in c["phrasalVerbs"]), not c["phrasalVerbs"])
        row("ملاحظات استعمال", "<br>".join(esc(u)[:400]
                                           for u in c["usageNotes"]),
            not c["usageNotes"])
        row("أخطاء المتعلّم",
            '<span class="empty">— لا مصدر مجاني (المرحلة التالية)</span>')
        parts.append("</div>")

    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(parts))


def main() -> None:
    log(f"build_kb النسخة {VERSION}")
    os.makedirs(WORK, exist_ok=True)
    db = connect()
    vocab = ({r[0] for r in db.execute("SELECT word FROM vocab")}
             if not want("vocab") else stage_vocab(db))
    if not vocab:
        vocab = stage_vocab(db)
    if want("wiktionary"):
        stage_wiktionary(db, vocab)
    if want("wordnet"):
        stage_wordnet(db, vocab)
        stage_cmudict(db, vocab)
    if want("tatoeba"):
        stage_tatoeba(db, vocab)
    if want("collocations"):
        stage_collocations(db, vocab)
    if want("translate"):
        stage_translate(db)

    # التنقية جزءٌ من البناء لا خطوة تُنسى. الجولة الواحدة تُنتج تكراراً
    # طبيعياً — الصيغة نفسها تُذكر تحت الاسم والفعل معاً — فبناءٌ بلا تنقية
    # يترك عشرات الآلاف من الصفوف المكرّرة بلا أن يُنبّه أحد.
    db.close()
    repair(apply=True)
    db = connect()

    stage_report(db)
    db.close()
    log(f"تمّ: {OUT_DB}")


if __name__ == "__main__":
    main()
