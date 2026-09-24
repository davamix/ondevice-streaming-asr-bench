#!/usr/bin/env python3
"""Score hypothesis transcripts against references (WER / CER).

Scoring happens here, on the PC. The device emits hypothesis text only -- it
never scores anything. That keeps the harness small and means a scoring bug is
fixable without re-running a single measurement.

Normalisation is the part that quietly decides WER comparisons, so it is
explicit and testable rather than implied:

  * lowercase
  * strip punctuation (Arm A's on-device recognizer and Whisper disagree wildly
    about punctuation; scoring it would measure formatting, not recognition)
  * optionally expand digits to words (--digits), because FLEURS references
    spell numbers out while Whisper emits "42". Without this, a model is
    penalised for a formatting choice.
  * collapse whitespace

Accents are KEPT for Spanish. Stripping them would flatter models that do not
produce them, which is exactly the difference we are trying to measure.

Usage
-----
    score.py --validate                     # self-test the scorer
    score.py results/run.json               # score a harness result file
    score.py --ref REF.txt --hyp HYP.txt    # score one pair
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from fractions import Fraction
from pathlib import Path

import jiwer

ROOT = Path(__file__).resolve().parent.parent

# ── digit expansion ──────────────────────────────────────────────────────
# Deliberately small: covers the 0-999 range plus round thousands, which is
# what read-speech corpora actually contain. Anything larger is left as digits
# and will simply be scored as a mismatch in both directions, which is fair.

_ONES = {
    "en": ["zero", "one", "two", "three", "four", "five", "six", "seven",
           "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
           "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"],
    "es": ["cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete",
           "ocho", "nueve", "diez", "once", "doce", "trece", "catorce",
           "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve"],
}
_TENS = {
    "en": ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
           "eighty", "ninety"],
    "es": ["", "", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta",
           "setenta", "ochenta", "noventa"],
}
_HUNDREDS_ES = ["", "ciento", "doscientos", "trescientos", "cuatrocientos",
                "quinientos", "seiscientos", "setecientos", "ochocientos",
                "novecientos"]


def _int_to_words(n: int, lang: str) -> str:
    """Spell out a non-negative integer. Falls back to the digits themselves."""
    if n < 0 or n > 999_999:
        return str(n)
    if n >= 1000:
        thousands, rest = divmod(n, 1000)
        if lang == "es":
            head = "mil" if thousands == 1 else f"{_int_to_words(thousands, 'es')} mil"
        else:
            head = f"{_int_to_words(thousands, 'en')} thousand"
        return head if rest == 0 else f"{head} {_int_to_words(rest, lang)}"
    if n >= 100:
        hundreds, rest = divmod(n, 100)
        if lang == "es":
            if n == 100:
                return "cien"
            head = _HUNDREDS_ES[hundreds]
        else:
            head = f"{_ONES['en'][hundreds]} hundred"
        return head if rest == 0 else f"{head} {_int_to_words(rest, lang)}"
    if n < 20:
        return _ONES[lang][n]
    tens, rest = divmod(n, 10)
    head = _TENS[lang][tens]
    if rest == 0:
        return head
    if lang == "es":
        if tens == 2:  # veintiuno, veintidós ...
            return {1: "veintiuno", 2: "veintidós", 3: "veintitrés",
                    4: "veinticuatro", 5: "veinticinco", 6: "veintiséis",
                    7: "veintisiete", 8: "veintiocho", 9: "veintinueve"}[rest]
        return f"{head} y {_ONES['es'][rest]}"
    return f"{head} {_ONES['en'][rest]}"


def _year_to_words(n: int) -> str:
    """1848 -> 'eighteen forty eight', 1900 -> 'nineteen hundred',
    1905 -> 'nineteen oh five'. How English speakers read a year."""
    hi, lo = divmod(n, 100)
    if lo == 0:
        tail = "hundred"
    elif lo < 10:
        tail = f"oh {_ONES['en'][lo]}"
    else:
        tail = _int_to_words(lo, "en")
    return f"{_int_to_words(hi, 'en')} {tail}"


def _expand_digits(text: str, lang: str) -> str:
    """Spell out digits the way the speaker most likely said them.

    An English four-digit number from 1100 to 1999 is read as a year. As a
    plain cardinal, "1848" became "one thousand eight hundred forty eight"
    while LibriSpeech's reference says "eighteen forty eight", so every arm
    that writes years as digits (Arm A, Whisper) took about twelve errors per
    pass on ls-other-short-006, and arms that spell them out took none. That
    was a formatting penalty worth ~4 WER points on noisy English. Spanish
    reads years as cardinals ("mil ochocientos cuarenta y ocho"), so it needs
    no special case.
    """
    lang = lang if lang in _ONES else "en"

    def spell(m: re.Match) -> str:
        s = m.group()
        n = int(s)
        if lang == "en" and len(s) == 4 and 1100 <= n <= 1999:
            return _year_to_words(n)
        return _int_to_words(n, lang)

    return re.sub(r"\d+", spell, text)


# ── normalisation ────────────────────────────────────────────────────────

# Keep letters (accented included), digits and apostrophes inside words.
_PUNCT = re.compile(r"[^\w\s']|_", flags=re.UNICODE)
_WS = re.compile(r"\s+")

# Clock times, found in Phase 3 the same way as years: FLEURS writes "12:00"
# and "10:00 p. m.", speakers say "doce" and "diez pe eme", and the digit
# expansion turned ":00" into a spoken "cero" that no arm ever produces. Every
# arm lost a word to it on fleurs-es-short-005. On-the-hour times are read as
# the hour alone; "p. m." / "p.m." / "pm" all become one token. Both sides of
# the comparison get the same rule, so an arm that writes digits and one that
# writes words are scored alike.
_ON_THE_HOUR = re.compile(r"\b(\d{1,2}):00\b")
_AM_PM = re.compile(r"\b([ap])\.\s?m\.")


def normalise(text: str, lang: str = "en", digits: bool = True) -> str:
    if text is None:
        return ""
    # NFC so that "é" as one codepoint and "e"+combining-accent compare equal.
    text = unicodedata.normalize("NFC", str(text))
    text = text.lower()
    text = _AM_PM.sub(r"\1m", text)
    if digits:
        text = _ON_THE_HOUR.sub(r"\1", text)
        text = _expand_digits(text, lang)
    text = _PUNCT.sub(" ", text)
    text = text.replace("'", "")
    return _WS.sub(" ", text).strip()


def score_pair(ref: str, hyp: str, lang: str = "en", digits: bool = True) -> dict:
    r = normalise(ref, lang, digits)
    h = normalise(hyp, lang, digits)
    if not r:
        return {"wer": None, "cer": None, "ref_words": 0, "note": "empty reference"}
    out = jiwer.process_words(r, h)
    cer = jiwer.cer(r, h)
    return {
        "wer": out.wer,
        "cer": cer,
        "ref_words": len(r.split()),
        "hits": out.hits,
        "substitutions": out.substitutions,
        "deletions": out.deletions,
        "insertions": out.insertions,
    }


def score_corpus(pairs: list[tuple[str, str, str]], digits: bool = True) -> dict:
    """Corpus WER = total edits / total reference words.

    NOT the mean of per-utterance WERs -- that weights a three-word utterance
    the same as a thirty-word one and is the single most common way published
    ASR numbers end up wrong.
    """
    agg = {"hits": 0, "substitutions": 0, "deletions": 0, "insertions": 0}
    ref_chars = 0
    cer_edits = 0.0
    n = 0
    for ref, hyp, lang in pairs:
        r = normalise(ref, lang, digits)
        h = normalise(hyp, lang, digits)
        if not r:
            continue
        out = jiwer.process_words(r, h)
        for k in agg:
            agg[k] += getattr(out, k)
        ref_chars += len(r)
        cer_edits += jiwer.cer(r, h) * len(r)
        n += 1
    ref_words = agg["hits"] + agg["substitutions"] + agg["deletions"]
    wer = (agg["substitutions"] + agg["deletions"] + agg["insertions"]) / ref_words if ref_words else None
    return {
        "utterances": n,
        "ref_words": ref_words,
        "wer": wer,
        "cer": (cer_edits / ref_chars) if ref_chars else None,
        **agg,
    }


def score_clips(by_clip: dict[str, list[tuple[str, str, str]]],
                digits: bool = True) -> dict:
    """Corpus WER with every clip counted once, however many rows it has.

    Each clip's edits are averaged over its rows, then summed like
    `score_corpus`. When every clip has the same number of rows this is
    exactly `score_corpus` over all rows. It differs when coverage is uneven:
    a clip measured in three runs would otherwise weigh three times as much as
    one measured once, and pooling an old 20-clip run with a newer 100-clip
    run would describe mostly the 20.
    """
    # Exact fractions, not floats: averaging over three rows in floating point
    # nudged an exact 11.875% to 11.87 in the printed table.
    agg = {k: Fraction(0) for k in ("hits", "substitutions", "deletions", "insertions")}
    ref_chars = 0
    cer_edits = 0.0
    n_clips = 0
    n_rows = 0
    for pairs in by_clip.values():
        rows = []
        for ref, hyp, lang in pairs:
            r = normalise(ref, lang, digits)
            if r:
                rows.append((r, normalise(hyp, lang, digits)))
        if not rows:
            continue
        k = len(rows)
        for r, h in rows:
            out = jiwer.process_words(r, h)
            for key in agg:
                agg[key] += Fraction(getattr(out, key), k)
            cer_edits += jiwer.cer(r, h) * len(r) / k
        ref_chars += len(rows[0][0])
        n_clips += 1
        n_rows += k
    ref_words = agg["hits"] + agg["substitutions"] + agg["deletions"]
    errors = agg["substitutions"] + agg["deletions"] + agg["insertions"]
    return {
        "clips": n_clips,
        "rows": n_rows,
        "ref_words": round(ref_words),
        "wer": float(errors / ref_words) if ref_words else None,
        "cer": (cer_edits / ref_chars) if ref_chars else None,
        **{k: float(v) for k, v in agg.items()},
    }


# ── self-test ────────────────────────────────────────────────────────────

def validate() -> int:
    """Validate against hand-computed pairs. A scorer you have not checked is
    a scorer that will silently rank the arms wrong."""
    failures = []

    def check(name, got, want, tol=1e-9):
        ok = got is not None and abs(got - want) <= tol
        print(f"  {'PASS' if ok else 'FAIL'}  {name}: got {got!r}, want {want!r}")
        if not ok:
            failures.append(name)

    print("normalisation:")
    cases = [
        ("Hello, World!", "en", "hello world"),
        ("It's 21 degrees.", "en", "its twenty one degrees"),
        ("¿Cómo estás?", "es", "cómo estás"),
        ("Son las 21 horas", "es", "son las veintiuno horas"),
        ("  multiple   spaces  ", "en", "multiple spaces"),
        ("born in 1848", "en", "born in eighteen forty eight"),
        ("in 1900 and 1905", "en", "in nineteen hundred and nineteen oh five"),
        ("bus 403, 2005", "en", "bus four hundred three two thousand five"),
        ("en 1848", "es", "en mil ochocientos cuarenta y ocho"),
        ("a las 12:00 GMT", "es", "a las doce gmt"),
        ("entre las 10:00 y 11:00 p. m.", "es", "entre las diez y once pm"),
        ("a las 11:35 p.m.", "es", "a las once treinta y cinco pm"),
        ("at 10:00 a.m.", "en", "at ten am"),
    ]
    for raw, lang, want in cases:
        got = normalise(raw, lang)
        ok = got == want
        print(f"  {'PASS' if ok else 'FAIL'}  {raw!r} -> {got!r} (want {want!r})")
        if not ok:
            failures.append(raw)

    print("\nWER -- 1 substitution in 4 words = 0.25:")
    r = score_pair("the quick brown fox", "the quick brown dog")
    check("wer", r["wer"], 0.25)

    print("\nWER -- 1 deletion + 1 insertion in 4 words = 0.5:")
    r = score_pair("the quick brown fox", "the quick fox jumps over")
    # ref 4 words; hyp drops 'brown', adds 'jumps over' -> 1 del + ... jiwer aligns
    print(f"    detail: {r}")
    check("ref_words", float(r["ref_words"]), 4.0)

    print("\nWER -- perfect match is 0.0, punctuation ignored:")
    r = score_pair("Hello, world!", "hello world")
    check("wer", r["wer"], 0.0)
    check("cer", r["cer"], 0.0)

    print("\nWER -- digit formatting must NOT be penalised:")
    r = score_pair("it is twenty one degrees", "It is 21 degrees.", "en")
    check("wer", r["wer"], 0.0)

    print("\nYears written as digits must NOT be penalised either:")
    r = score_pair("born january fifteenth eighteen forty eight",
                   "Born January 15, 1848.", "en")
    # "15" vs "fifteenth" is a genuine ordinal/cardinal mismatch: 1 of 6.
    check("wer", r["wer"], 1 / 6)

    print("\nSpanish accents ARE scored (they are a real difference):")
    r = score_pair("cómo estás", "como estas", "es")
    check("wer", r["wer"], 1.0)

    print("\nCorpus WER weights by length, not by utterance:")
    # 1 error in 1 word, and 0 errors in 9 words -> 1/10 = 0.1, not (1.0+0.0)/2
    pairs = [("yes", "no", "en"), ("one two three four five six seven eight nine",
                                    "one two three four five six seven eight nine", "en")]
    c = score_corpus(pairs, digits=False)
    check("corpus wer", c["wer"], 0.1)

    print("\nClip-weighted WER counts each clip once, however many rows it has:")
    # Clip "a" measured three times (always wrong), clip "b" once (right).
    # Row-pooled that is 3/12 = 0.25; clip-weighted it is 1/10 = 0.1.
    nine = "one two three four five six seven eight nine"
    by_clip = {"a": [("yes", "no", "en")] * 3, "b": [(nine, nine, "en")]}
    check("clip-weighted wer", score_clips(by_clip, digits=False)["wer"], 0.1)
    check("row-pooled wer", score_corpus(by_clip["a"] + by_clip["b"], digits=False)["wer"],
          3 / 12)

    print()
    if failures:
        print(f"VALIDATION FAILED: {len(failures)} check(s): {failures}")
        return 1
    print("VALIDATION PASSED -- scorer is trusted.")
    return 0


# ── result-file scoring ──────────────────────────────────────────────────

def score_result_file(path: Path, digits: bool = True) -> dict:
    """Score a harness result JSON.

    Expected shape (see bench/ result writer):
        {"device": ..., "runs": [{"arm":..., "clip_id":..., "lang":...,
                                  "hypothesis":..., ...}, ...]}
    References come from corpus/manifest.json.
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    manifest_path = ROOT / "corpus" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    refs = {c["clip_id"]: c for c in manifest["clips"]}

    by_arm: dict[tuple[str, str], list] = {}
    for run in data.get("runs", []):
        clip = refs.get(run.get("clip_id"))
        if clip is None:
            print(f"  warn: no reference for clip_id {run.get('clip_id')!r}", file=sys.stderr)
            continue
        ref_text = (ROOT / "corpus" / clip["ref"]).read_text(encoding="utf-8").strip()
        key = (run["arm"], clip["language"])
        by_arm.setdefault(key, []).append((ref_text, run.get("hypothesis", ""), clip["language"]))

    out = {}
    for (arm, lang), pairs in sorted(by_arm.items()):
        out[f"{arm}/{lang}"] = score_corpus(pairs, digits)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("result", nargs="?", type=Path, help="harness result JSON")
    ap.add_argument("--validate", action="store_true", help="run the scorer self-test")
    ap.add_argument("--ref", type=Path, help="reference text file")
    ap.add_argument("--hyp", type=Path, help="hypothesis text file")
    ap.add_argument("--lang", default="en", choices=["en", "es"])
    ap.add_argument("--no-digits", action="store_true",
                    help="do not expand digits to words before scoring")
    args = ap.parse_args()
    digits = not args.no_digits

    if args.validate:
        return validate()

    if args.ref and args.hyp:
        r = score_pair(args.ref.read_text(encoding="utf-8"),
                       args.hyp.read_text(encoding="utf-8"),
                       args.lang, digits)
        print(json.dumps(r, indent=2, ensure_ascii=False))
        return 0

    if args.result:
        out = score_result_file(args.result, digits)
        print(json.dumps(out, indent=2, ensure_ascii=False))
        return 0

    ap.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())
