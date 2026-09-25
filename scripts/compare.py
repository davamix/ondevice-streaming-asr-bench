#!/usr/bin/env python3
"""Is arm X really more accurate than arm Y? A paired bootstrap over clips.

    compare.py --source fleurs_es A D:parakeet-tdt-v3-int8
    compare.py --source librispeech_other B:moonshine-small-en D:parakeet-tdt-v3-int8
    compare.py --standard            # the comparisons the README quotes

An arm is `A`, `B:<variant>`, `C:<variant>`, `D:<variant>` or
`E:<variant>`, as for run_bench.py. Only clips both arms were measured on are compared, and each
clip counts once however many rows it has (see score.score_clips).

**Why clips, not rows, are the sample.** The offline arms produce identical
text on every repetition, and the others nearly so. Four repetitions of 20
clips are still 20 clips of evidence, not 80. The uncertainty that matters is
which sentences happened to be in the corpus, so the bootstrap resamples
clips, with replacement, and pairs them: both arms are scored on the same
resampled set each time. The interval printed is for the difference in WER.

The same rows are used as by summarize.py: superseded runs, emulator runs,
duplicate files and repetition 0 are all excluded.
"""

from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score import normalise  # noqa: E402
from summarize import RESULTS, iter_rows, load_refs, result_paths  # noqa: E402

import jiwer  # noqa: E402

# Pairs the README and summaries rely on. Kept here so a claim like "A beats
# B" is one command away from being checked again.
STANDARD = [
    ("fleurs_es", "A", "D:parakeet-tdt-v3-int8"),
    ("fleurs_es", "E:whisper-small-int8", "D:parakeet-tdt-v3-int8"),
    ("fleurs_es", "E:whisper-base-int8", "A"),
    # Phase 4: the cheap Spanish option against the free one and the big one.
    # Whisper was measured on the original 20 Spanish clips only.
    ("fleurs_es", "A", "C:moonshine-base-es"),
    ("fleurs_es", "C:moonshine-base-es", "D:parakeet-tdt-v3-int8"),
    ("fleurs_es", "E:whisper-small-int8", "C:moonshine-base-es"),
    # Phase 4: Moonshine's Spanish streaming model, found on its CDN.
    ("fleurs_es", "A", "B:moonshine-small-es"),
    ("fleurs_es", "B:moonshine-small-es", "D:parakeet-tdt-v3-int8"),
    ("fleurs_es", "C:moonshine-base-es", "B:moonshine-small-es"),
    ("librispeech_other", "A", "B:moonshine-small-en"),
    ("librispeech_other", "B:moonshine-small-en", "D:parakeet-tdt-v3-int8"),
    ("librispeech_other", "B:moonshine-medium-en", "D:parakeet-tdt-v3-int8"),
    ("librispeech_other", "B:moonshine-small-en", "B:moonshine-medium-en"),
    ("fleurs_en_norm", "B:moonshine-small-en", "D:parakeet-tdt-v3-int8"),
    ("fleurs_en_norm", "B:moonshine-medium-en", "D:parakeet-tdt-v3-int8"),
    ("fleurs_en_norm", "B:moonshine-small-en", "B:moonshine-medium-en"),
    ("fleurs_en_norm", "E:whisper-small-int8", "D:parakeet-tdt-v3-int8"),
    ("fleurs_en_norm", "A", "D:parakeet-tdt-v3-int8"),
    ("fleurs_en_norm", "A", "B:moonshine-small-en"),
]


def arm_key(row: dict) -> str:
    variant = (row.get("extra") or {}).get("variant") or ""
    return f"{row['arm']}:{variant}" if variant else row["arm"]


def per_clip(paths: list[Path]) -> dict:
    """{(arm, source): {clip_id: (mean edits, ref words)}}"""
    refs = load_refs()
    acc: dict[tuple, dict[str, list[int]]] = {}
    words: dict[str, int] = {}
    for row, _ in iter_rows(paths):
        ref = refs.get(row.get("clip_id"))
        if not ref:
            continue
        r = normalise(ref["text"], ref["language"])
        h = normalise(row.get("hypothesis") or "", ref["language"])
        if not r:
            continue
        o = jiwer.process_words(r, h)
        key = (arm_key(row), row.get("source", "?"))
        acc.setdefault(key, {}).setdefault(row["clip_id"], []).append(
            o.substitutions + o.deletions + o.insertions)
        words[row["clip_id"]] = len(r.split())
    return {k: {c: (sum(e) / len(e), words[c]) for c, e in v.items()}
            for k, v in acc.items()}


def compare(data: dict, source: str, a: str, b: str,
            resamples: int, seed: int) -> dict | None:
    ca, cb = data.get((a, source)), data.get((b, source))
    if not ca or not cb:
        return None
    clips = sorted(set(ca) & set(cb))
    if not clips:
        return None
    ea = [ca[c][0] for c in clips]
    eb = [cb[c][0] for c in clips]
    w = [ca[c][1] for c in clips]
    total = sum(w)
    rng = random.Random(seed)
    n = len(clips)
    diffs = []
    for _ in range(resamples):
        idx = [rng.randrange(n) for _ in range(n)]
        ws = sum(w[i] for i in idx)
        diffs.append((sum(ea[i] for i in idx) - sum(eb[i] for i in idx)) / ws)
    diffs.sort()
    return {
        "source": source, "a": a, "b": b, "clips": n, "words": total,
        "wer_a": sum(ea) / total, "wer_b": sum(eb) / total,
        "lo": diffs[int(0.025 * resamples)],
        "hi": diffs[int(0.975 * resamples) - 1],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("arms", nargs="*", help="two arms, e.g. A D:parakeet-tdt-v3-int8")
    ap.add_argument("--source", help="corpus source, e.g. fleurs_es")
    ap.add_argument("--standard", action="store_true",
                    help="run the comparisons the README quotes")
    ap.add_argument("--resamples", type=int, default=20000)
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()

    if args.standard:
        pairs = STANDARD
    elif len(args.arms) == 2 and args.source:
        pairs = [(args.source, args.arms[0], args.arms[1])]
    else:
        ap.error("give --source and two arms, or --standard")

    data = per_clip(result_paths([RESULTS]))
    print(f"{'source':18s} {'A':24s} {'B':24s} {'WER A':>7s} {'WER B':>7s} "
          f"{'A-B':>6s}  {'95% CI of A-B':>17s}  clips  words  verdict")
    for source, a, b in pairs:
        r = compare(data, source, a, b, args.resamples, args.seed)
        if r is None:
            print(f"{source:18s} {a:24s} {b:24s}  (no shared clips)")
            continue
        d = r["wer_a"] - r["wer_b"]
        if r["lo"] > 0:
            verdict = "B better"
        elif r["hi"] < 0:
            verdict = "A better"
        else:
            verdict = "not resolved"
        print(f"{source:18s} {a:24s} {b:24s} {100 * r['wer_a']:6.2f}% {100 * r['wer_b']:6.2f}% "
              f"{100 * d:+6.2f}  [{100 * r['lo']:+6.2f}, {100 * r['hi']:+6.2f}]  "
              f"{r['clips']:5d}  {r['words']:5d}  {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
