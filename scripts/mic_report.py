#!/usr/bin/env python3
"""The real-microphone check, live against file-fed (PLAN.md §7, §10 step 20).

    mic_report.py                                   # every take in corpus/self-recorded/
    mic_report.py corpus/self-recorded/<dir>/mic-*.json

Each take is one recording of the owner reading the sentences the phone
showed, transcribed twice by the same arm: live from the microphone (rep 0),
then file-fed from the saved recording through the paced feeder every
measured run uses (rep 1). Same samples, same arm, two feeds. So:

  - **Live against file** is the check of the method. If simulating a
    microphone by pacing a file is sound, the two agree on the text and
    roughly on the timing. Any disagreement is the method's error.
  - **Against the script** is qualitative only (§8): the owner's voice, room
    and microphone, scored against what they were asked to read. Words they
    did not get to within the recording count as missed, so a low number is
    informative and a high one may not be.

Reads only local, gitignored files, and prints the owner's transcripts to
this terminal only. Nothing here belongs in the repo (§11.7); the README
gets the comparison, not the text.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

import jiwer

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score import normalise, score_pair  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
SELF_RECORDED = ROOT / "corpus" / "self-recorded"

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass


def fmt(v, spec=".0f", dash="—"):
    return dash if v is None else format(v, spec)


def med(v):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None


def describe(run: dict, prompt: str, lang: str) -> dict:
    extra = run.get("extra") or {}
    events = extra.get("final_events") or []
    windows = extra.get("timeline") or []
    sc = score_pair(prompt, run.get("hypothesis") or "", lang) if prompt else None
    return {
        "feed": extra.get("feed"),
        "wer": sc["wer"] if sc else None,
        "first_ms": run.get("latency_first_partial_ms"),
        "final_ms": run.get("latency_final_ms"),
        "rtf": run.get("rtf_sustained"),
        "max_slip_ms": run.get("max_slip_ms"),
        "events": len(events),
        "commit_lag_ms": med([e["commit_ms"] - e["end_ms"] for e in events]),
        "window_rtf": [w.get("rtf") for w in windows],
        "error": run.get("error"),
        "text": run.get("hypothesis") or "",
    }


def report(path: Path, show_text: bool) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    notes = doc.get("notes", {})
    runs = sorted(doc.get("runs", []), key=lambda r: r.get("rep", 0))
    if len(runs) < 2:
        print(f"\n{path.name}: {len(runs)} row(s), expected a live and a file-fed one")
        return
    live_row, file_row = runs[0], runs[1]
    lang = live_row["lang"]
    prompt = notes.get("prompt") or ""
    emulator = (doc.get("device") or {}).get("is_emulator")
    variant = (live_row.get("extra") or {}).get("variant") or live_row.get("arm_label")
    print(f"\n=== {live_row['clip_id']}  Arm {live_row['arm']} / {variant}  "
          f"[{path.parent.name}/{path.name}]"
          + ("  [EMULATOR -- plumbing only]" if emulator else ""))
    print(f"  microphone               {notes.get('mic_source')}, level "
          f"{fmt(notes.get('mic_rms_dbfs'), '.1f')} dBFS RMS over the take "
          f"(corpus: -23 level-matched)")
    print(f"  battery temp             {fmt(live_row.get('battery_temp_c_before'), '.1f')} -> "
          f"{fmt(file_row.get('battery_temp_c_after'), '.1f')} C")

    a, b = describe(live_row, prompt, lang), describe(file_row, prompt, lang)
    h_live = normalise(a["text"], lang)
    h_file = normalise(b["text"], lang)
    if h_live or h_file:
        o = jiwer.process_words(h_file or "-", h_live or "-")
        diff = o.substitutions + o.deletions + o.insertions
        n = len(h_file.split())
        agree = "identical" if h_live == h_file else \
            f"{diff} word edit(s) apart ({100 * diff / max(n, 1):.1f}% of {n} words)"
    else:
        agree = "both empty"
    print(f"  live vs file-fed text    {agree}")

    print(f"  {'':24} {'live (mic)':>14} {'file-fed':>14}")
    for label, key, spec in [
        ("WER vs script (qual.)", "wer", ".1%"),
        ("first text, ms", "first_ms", ".0f"),
        ("final text, ms", "final_ms", ".0f"),
        ("rtf_sustained", "rtf", ".3f"),
        ("max slip, ms", "max_slip_ms", ".0f"),
        ("final events", "events", "d"),
        ("commit after seg end", "commit_lag_ms", ".0f"),
    ]:
        print(f"  {label:24} {fmt(a[key], spec):>14} {fmt(b[key], spec):>14}")
    for side in (a, b):
        if side["error"]:
            print(f"  ERROR ({side['feed']}): {side['error']}")
    if show_text:
        print(f"  script:  {prompt.replace(chr(10), ' ')[:600]}")
        print(f"  live:    {a['text'][:600]}")
        print(f"  file:    {b['text'][:600]}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", type=Path)
    ap.add_argument("--text", action="store_true",
                    help="also print the script and both transcripts (local only)")
    args = ap.parse_args()
    paths = args.paths or sorted(SELF_RECORDED.rglob("mic-*.json"))
    if not paths:
        print("no microphone takes found under corpus/self-recorded/", file=sys.stderr)
        return 1
    for p in paths:
        report(p, args.text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
