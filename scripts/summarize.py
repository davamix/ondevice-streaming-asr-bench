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


# One frame of pacing. Slip beyond this means the feeder could not hand over
# audio on time, because the consumer stopped draining fast enough.
#
# Headline latency is reported over ALL rows, not slip-filtered ones. Filtering
# looked right at first and is wrong: slip is not harness noise, it is the
# recognizer stalling, and it stalls hardest on the audio it finds hardest.
# Dropping slipped rows therefore drops the difficult clips and flatters the
# arm -- on LibriSpeech test-other it moved median final latency from 315 ms to
# 114 ms by keeping only the easy two-thirds.
#
# So slip is reported as its own metric instead. For Arm A that earns its keep
# twice over: recognition happens inside Google's process, so rtf_sustained is
# unmeasurable, and slip is the only signal we have for "the recognizer is not
# keeping up with real-time input".
SLIP_BUDGET_MS = 100


def superseded_names(root: Path) -> set[str]:
    """Result files that `root`'s run directories were superseded *for*.

    A pulled directory is named `<model>-<utc>-<label>`, and the run it was
    pulled for carries that label as `run_label`.
    """
    names: set[str] = set()
    if not root.is_dir():
        return names
    for d in root.iterdir():
        if not d.is_dir():
            continue
        for f in d.glob("*.json"):
            try:
                label = json.loads(f.read_text(encoding="utf-8")).get("run_label")
            except (json.JSONDecodeError, AttributeError):
                continue
            if label and d.name.endswith(f"-{label}"):
                names.add(f.name)
    return names


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

        # Dropping repetition 0 is right when there are others to fall back
        # on. On a single-rep run -- which is how the 6-minute session clips
        # are measured, since four repetitions would be 48 minutes of phone
        # time -- it silently deletes the entire group. Keep it, and mark it.
        doc_reps = doc.get("notes", {}).get("reps", 0)
        single_rep = doc_reps == 1

        # A run checkpoints its results during each mandatory break. If it
        # then died, the checkpoint is what survives: real rows, but not the
        # run that was asked for.
        if doc.get("notes", {}).get("complete") is False:
            print(f"[summarize] WARNING {path.name} is a checkpoint of a run "
                  f"that did not finish -- rows included, run incomplete",
                  file=sys.stderr)

        for run in doc.get("runs", []):
            if not keep_first and not single_rep and run.get("rep", 0) == 0:
                continue
            # Source is part of the key: FLEURS is clean read speech and
            # LibriSpeech test-other is the noisy stress case. Averaging them
            # into one WER would describe neither.
            #
            # So is the variant. Every Moonshine size reports arm "B", and
            # without it tiny, small and medium would pool into one row -- the
            # accuracy-vs-size curve Phase 2 exists to draw, averaged flat.
            variant = (run.get("extra") or {}).get("variant") or ""
            key = (run["arm"], variant, run["lang"], run.get("bucket", "?"),
                   run.get("source", "?"))
            g = groups.setdefault(key, {
                "arm_label": run.get("arm_label", run["arm"]),
                "runtime": run.get("runtime", "?"),
                "disk_size_mb": run.get("disk_size_mb"),
                "rows": [], "pairs": [], "errors": 0, "empty": 0,
                "single_rep": single_rep,
            })
            g["rows"].append(run)
            if run.get("error"):
                g["errors"] += 1
            # An empty hypothesis is scored, not skipped: it is every reference
            # word deleted. Skipping it removed those words from the
            # denominator instead, so an arm that silently heard nothing
            # scored *better* for it -- Moonshine tiny returned no text at all
            # on 2 of 20 clean FLEURS clips in its pilot, and dropping them
            # moved its WER from 26.9% down to 19.3%.
            ref = refs.get(run.get("clip_id"))
            if ref:
                hyp = run.get("hypothesis") or ""
                if not hyp.strip():
                    g["empty"] += 1
                g["pairs"].append((ref["text"], hyp, ref["language"]))

    out = {}
    for key, g in sorted(groups.items()):
        rows = g["rows"]
        # Latency comes only from rows where pacing held; WER uses everything.
        timed = [r for r in rows if is_timing_clean(r)]
        scored = score_corpus(g["pairs"]) if g["pairs"] else {}
        temps = [r.get("battery_temp_c_after") for r in rows]
        # Final latency against when the audio actually ended, not when the
        # feeder returned. Only rows from the Phase 2 harness onward carry it.
        extras = [r.get("extra") or {} for r in rows]
        after_end = [e["final_after_audio_end_ms"] for e in extras
                     if e.get("final_after_audio_end_ms") is not None]
        out[key] = {
            "n_timing": len(timed),
            "n_slipped": len(rows) - len(timed),
            "arm_label": g["arm_label"],
            "runtime": g["runtime"],
            "disk_size_mb": g["disk_size_mb"],
            "n": len(rows),
            "errors": g["errors"],
            "empty": g["empty"],
            "single_rep": g.get("single_rep", False),
            "latency_final_ms": med([r.get("latency_final_ms") for r in rows]),
            # Clamped like latency_final_ms: text already final when the
            # speaker stopped is 0 ms of waiting, not negative waiting.
            "latency_final_audio_end_ms": med([max(0, v) for v in after_end]),
            "final_after_audio_end_signed_ms": med(after_end),
            "speech_end_lag_ms": med([e.get("speech_end_lag_ms") for e in extras]),
            "n_audio_end": len(after_end),
            "latency_first_partial_ms": med([r.get("latency_first_partial_ms") for r in rows]),
            "partial_instability": med([r.get("partial_instability") for r in rows]),
            "rtf_sustained": med([r.get("rtf_sustained") for r in rows]),
            "latency_final_ms_lowslip": med([r.get("latency_final_ms") for r in timed]),
            "latency_first_partial_ms_lowslip": med([r.get("latency_first_partial_ms") for r in timed]),
            "slip_median_ms": med([r.get("max_slip_ms") for r in rows]),
            "slip_p90_ms": (sorted([r.get("max_slip_ms") or 0 for r in rows])[int(0.9 * len(rows))]
                            if rows else None),
            "slip_over_budget_pct": (100.0 * (len(rows) - len(timed)) / len(rows)) if rows else None,
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
    print("| Arm | Lang | Bucket | Source | `latency_final_ms` | final, from audio end | "
          "`latency_first_partial_ms` | "
          "`partial_instability` | `rtf_sustained` | `peak_rss_mb` | "
          "`disk_size_mb` | WER | CER |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for (arm, variant, lang, bucket, source), s in summary.items():
        name = f"{arm} {variant.removeprefix('moonshine-')}".strip()
        print(
            f"| {name} | {lang} | {bucket} | {source} | {fmt(s['latency_final_ms'], '.0f')} "
            f"| {fmt(s['latency_final_audio_end_ms'], '.0f')} "
            f"| {fmt(s['latency_first_partial_ms'], '.0f')} "
            f"| {fmt(s['partial_instability'], '.0f')} "
            f"| {fmt(s['rtf_sustained'], '.3f')} "
            f"| {fmt(s['peak_rss_mb'], '.0f')} "
            f"| {fmt(s['disk_size_mb'], '.1f')} "
            f"| {fmt(s['wer'] * 100 if s['wer'] is not None else None, '.1f')}% "
            f"| {fmt(s['cer'] * 100 if s['cer'] is not None else None, '.1f')}% |"
        )


def print_detail(summary: dict) -> None:
    for (arm, variant, lang, bucket, source), s in summary.items():
        print(f"\n=== Arm {arm}{' / ' + variant if variant else ''} / {lang} / {bucket} / {source}")
        print(f"  {s['arm_label']}  [{s['runtime']}]")
        print(f"  rows                     {s['n']}  (errors: {s['errors']}, "
              f"no text: {s['empty']})"
              + ("   [SINGLE REPETITION -- indicative, not statistical]"
                 if s.get("single_rep") else ""))
        print(f"  slip median/p90/max      {fmt(s['slip_median_ms'], '.0f')} / "
              f"{fmt(s['slip_p90_ms'], '.0f')} / {s['max_slip_ms']} ms"
              f"   ({fmt(s['slip_over_budget_pct'], '.0f')}% over {SLIP_BUDGET_MS}ms)")
        print(f"  latency_final_ms         {fmt(s['latency_final_ms'], '.0f')}  (median)")
        if s["n_audio_end"]:
            print(f"    from actual audio end  {fmt(s['latency_final_audio_end_ms'], '.0f')}"
                  f"   [n={s['n_audio_end']}; signed median "
                  f"{fmt(s['final_after_audio_end_signed_ms'], '.0f')}; old stamp late by "
                  f"{fmt(s['speech_end_lag_ms'], '.0f')} ms]")
        print(f"  latency_first_partial_ms {fmt(s['latency_first_partial_ms'], '.0f')}  (median)")
        print(f"    same, low-slip rows    {fmt(s['latency_first_partial_ms_lowslip'], '.0f')}"
              f"   [n={s['n_timing']}, biased toward easy clips -- see SLIP_BUDGET_MS]")
        print(f"    final, low-slip rows   {fmt(s['latency_final_ms_lowslip'], '.0f')}")
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
    # results/superseded/ holds runs from a harness with known measurement
    # bugs. They are kept as evidence for findings in the README but must
    # never reach an aggregate -- see results/superseded/README.md.
    #
    # Excluded by name, not just by location: the device keeps every result
    # file until cleaned, so a superseded run is pulled again into every later
    # directory, where a path check alone would let it back in. Only the file
    # a superseded directory is *named for* counts -- those directories also
    # hold re-pulled copies of good runs, which must stay in.
    superseded = superseded_names(RESULTS / "superseded")
    paths = [p for p in paths
             if "superseded" not in p.parts and p.name not in superseded]

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
