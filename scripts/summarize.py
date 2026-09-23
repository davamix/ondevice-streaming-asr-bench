#!/usr/bin/env python3
"""Turn pulled result JSON into the results table.

    summarize.py                          # every run under results/
    summarize.py results/M2012K11AG-...   # one run
    summarize.py --markdown               # the table for the README

Repetition 0 is dropped by default. The first repetition pays for cold start
and an empty page cache, and including it would quietly penalise whichever arm
happened to run first (PLAN.md §6). Pass --keep-first to see it anyway.

Latency is reported as a median rather than a mean: one stalled utterance
should not move the number that describes the other nineteen.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score import score_corpus  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
CORPUS = ROOT / "corpus"

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass


def load_refs() -> dict:
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    refs = {}
    for clip in manifest["clips"]:
        path = CORPUS / clip["ref"]
        if path.exists():
            refs[clip["clip_id"]] = {
                "text": path.read_text(encoding="utf-8").strip(),
                "language": clip["language"],
            }
    return refs


# One frame. If the feeder fell further behind the wall clock than this, the
# audio reached the model later than the clip's own timeline says it did, and
# any latency measured against that timeline is contaminated. Such rows still
# carry valid *text* -- the audio arrived intact, just late -- so they are kept
# for WER and dropped only from the latency statistics.
SLIP_BUDGET_MS = 100


def med(values: list) -> float | None:
    vals = [v for v in values if v is not None]
    return statistics.median(vals) if vals else None


def is_timing_clean(run: dict) -> bool:
    return (run.get("max_slip_ms") or 0) <= SLIP_BUDGET_MS


def collect(paths: list[Path], keep_first: bool) -> dict:
    refs = load_refs()
    groups: dict[tuple, dict] = {}

    for path in paths:
        doc = json.loads(path.read_text(encoding="utf-8"))
        if doc.get("run_label") == "plumbing":
            continue
        device = doc.get("device", {})
        if device.get("is_emulator"):
            # Emulator timings are unrelated to the quantity of interest (D1).
            continue

        for run in doc.get("runs", []):
            if not keep_first and run.get("rep", 0) == 0:
                continue
            # Source is part of the key: FLEURS is clean read speech and
            # LibriSpeech test-other is the noisy stress case. Averaging them
            # into one WER would describe neither.
            key = (run["arm"], run["lang"], run.get("bucket", "?"),
                   run.get("source", "?"))
            g = groups.setdefault(key, {
                "arm_label": run.get("arm_label", run["arm"]),
                "runtime": run.get("runtime", "?"),
                "disk_size_mb": run.get("disk_size_mb"),
                "rows": [], "pairs": [], "errors": 0,
            })
            g["rows"].append(run)
            if run.get("error"):
                g["errors"] += 1
            ref = refs.get(run.get("clip_id"))
            if ref and run.get("hypothesis"):
                g["pairs"].append((ref["text"], run["hypothesis"], ref["language"]))

    out = {}
    for key, g in sorted(groups.items()):
        rows = g["rows"]
        # Latency comes only from rows where pacing held; WER uses everything.
        timed = [r for r in rows if is_timing_clean(r)]
        scored = score_corpus(g["pairs"]) if g["pairs"] else {}
        temps = [r.get("battery_temp_c_after") for r in rows]
        out[key] = {
            "n_timing": len(timed),
            "n_slipped": len(rows) - len(timed),
            "arm_label": g["arm_label"],
            "runtime": g["runtime"],
            "disk_size_mb": g["disk_size_mb"],
            "n": len(rows),
            "errors": g["errors"],
            "latency_final_ms": med([r.get("latency_final_ms") for r in timed]),
            "latency_first_partial_ms": med([r.get("latency_first_partial_ms") for r in timed]),
            "partial_instability": med([r.get("partial_instability") for r in timed]),
            "rtf_sustained": med([r.get("rtf_sustained") for r in timed]),
            "max_slip_ms": max([r.get("max_slip_ms") or 0 for r in rows], default=0),
            "peak_rss_mb": med([r.get("peak_rss_mb") for r in rows]),
            "temp_max_c": max([t for t in temps if t is not None], default=None),
            "wer": scored.get("wer"),
            "cer": scored.get("cer"),
            "ref_words": scored.get("ref_words"),
        }
    return out


def fmt(v, spec="", dash="—"):
    if v is None:
        return dash
    return format(v, spec)


def print_markdown(summary: dict) -> None:
    print("| Arm | Lang | Source | `latency_final_ms` | `latency_first_partial_ms` | "
          "`partial_instability` | `rtf_sustained` | `peak_rss_mb` | "
          "`disk_size_mb` | WER | CER |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for (arm, lang, bucket, source), s in summary.items():
        print(
            f"| {arm} | {lang} | {source} | {fmt(s['latency_final_ms'], '.0f')} "
            f"| {fmt(s['latency_first_partial_ms'], '.0f')} "
            f"| {fmt(s['partial_instability'], '.0f')} "
            f"| {fmt(s['rtf_sustained'], '.3f')} "
            f"| {fmt(s['peak_rss_mb'], '.0f')} "
            f"| {fmt(s['disk_size_mb'], '.1f')} "
            f"| {fmt(s['wer'] * 100 if s['wer'] is not None else None, '.1f')}% "
            f"| {fmt(s['cer'] * 100 if s['cer'] is not None else None, '.1f')}% |"
        )


def print_detail(summary: dict) -> None:
    for (arm, lang, bucket, source), s in summary.items():
        print(f"\n=== Arm {arm} / {lang} / {bucket} / {source}")
        print(f"  {s['arm_label']}  [{s['runtime']}]")
        print(f"  rows                     {s['n']}  (errors: {s['errors']})")
        print(f"  timing-clean rows        {s['n_timing']}"
              f"  (dropped {s['n_slipped']} over {SLIP_BUDGET_MS}ms slip)")
        print(f"  latency_final_ms         {fmt(s['latency_final_ms'], '.0f')}  (median)")
        print(f"  latency_first_partial_ms {fmt(s['latency_first_partial_ms'], '.0f')}  (median)")
        print(f"  partial_instability      {fmt(s['partial_instability'], '.0f')}  (median)")
        print(f"  rtf_sustained            {fmt(s['rtf_sustained'], '.3f')}")
        print(f"  max_slip_ms              {s['max_slip_ms']}")
        print(f"  peak_rss_mb              {fmt(s['peak_rss_mb'], '.0f')}")
        print(f"  disk_size_mb             {fmt(s['disk_size_mb'], '.1f')}")
        print(f"  temp_max_c               {fmt(s['temp_max_c'], '.1f')}")
        if s["wer"] is not None:
            print(f"  WER                      {s['wer'] * 100:.2f}%  "
                  f"({s['ref_words']} ref words)")
            print(f"  CER                      {s['cer'] * 100:.2f}%")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dirs", nargs="*", type=Path)
    ap.add_argument("--markdown", action="store_true")
    ap.add_argument("--keep-first", action="store_true",
                    help="include repetition 0 (cold start / empty page cache)")
    args = ap.parse_args()

    roots = args.dirs or [RESULTS]
    paths: list[Path] = []
    for r in roots:
        paths.extend(sorted(r.rglob("*.json")) if r.is_dir() else [r])
    paths = [p for p in paths if p.name not in ("device.json", "recognition-support.json")]

    if not paths:
        print("no result files found", file=sys.stderr)
        return 1

    # The device keeps every result file until it is cleaned, so consecutive
    # pulls copy earlier runs into later directories. Counting the same file
    # twice would silently double the weight of whichever run was pulled most
    # often -- dedupe by basename, which carries the run's UTC timestamp.
    seen: set[str] = set()
    unique: list[Path] = []
    for p_ in paths:
        if p_.name in seen:
            continue
        seen.add(p_.name)
        unique.append(p_)
    dropped = len(paths) - len(unique)
    if dropped:
        print(f"[summarize] ignored {dropped} duplicate result file(s)",
              file=sys.stderr)
    paths = unique

    summary = collect(paths, args.keep_first)
    if not summary:
        print("no usable rows (emulator-only results are excluded by design)",
              file=sys.stderr)
        return 1

    if args.markdown:
        print_markdown(summary)
    else:
        print_detail(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
