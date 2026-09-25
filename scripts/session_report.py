#!/usr/bin/env python3
"""What happened inside each 6-minute session (PLAN.md §6, `rtf_sustained`).

    session_report.py                          # every session row under results/
    session_report.py results/<dir>/<f>.json   # one run
    session_report.py --include-emulator       # plumbing check (§11.5)

`summarize.py` scores a session like any clip: one WER, one set of timings.
This reads the timeline a session-length row carries (see
bench/.../SessionTimeline.kt) and answers the questions a per-clip run
cannot:

  - Does the model keep up for the whole session? `rtf_sustained` over the
    session, and per 30 s window: above 1.0 the phone falls behind the
    microphone and never catches up.
  - Does final latency drift as the phone heats? Each utterance's final text
    is timed against the end of that utterance in the corpus (the manifest's
    segment times), and the first third of the session is compared with the
    last third.
  - Temperature, the platform's thermal status and headroom, feeder slip and
    resident memory, from the first window to the last.

Final latency per utterance is measured from the end of the utterance's audio
in the session, the same reference point as the per-clip "final text" figure,
and clamped at zero the same way. An utterance's text is final once every
segment (or line) overlapping it has committed; one the arm never produced
text for has no final and is counted separately.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score import score_pair  # noqa: E402
from summarize import result_paths  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
CORPUS = ROOT / "corpus"

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

# An event must reach this far into an utterance to count as covering it. The
# VAD dates a segment's start ~0.3 s before it declares speech, and a segment
# can run a little past the end of the speech it holds; neither should tie an
# utterance to its neighbour's text across a 0.5-1.5 s gap.
OVERLAP_TOLERANCE_MS = 150

THERMAL_STATUS = {0: "none", 1: "light", 2: "moderate", 3: "severe",
                  4: "critical", 5: "emergency", 6: "shutdown"}


def manifest_clips() -> dict:
    m = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    return {c["clip_id"]: c for c in m["clips"]}


def med(v: list):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None


def fmt(v, spec=".0f", dash="—"):
    return dash if v is None else format(v, spec)


def utterance_finals(clip: dict, events: list[dict]) -> list[dict]:
    """Per reference utterance: when its text was final, against its end."""
    out = []
    for i, seg in enumerate(clip["segments"]):
        s_ms, e_ms = seg["start_s"] * 1000, seg["end_s"] * 1000
        covering = [ev for ev in events
                    if ev["start_ms"] < e_ms - OVERLAP_TOLERANCE_MS
                    and ev["end_ms"] > s_ms + OVERLAP_TOLERANCE_MS]
        with_text = [ev for ev in covering if (ev.get("text") or "").strip()]
        final_at = max((ev["commit_ms"] for ev in covering), default=None)
        out.append({
            "i": i,
            "end_ms": e_ms,
            "events": len(covering),
            "has_text": bool(with_text),
            "signed_ms": None if final_at is None else final_at - e_ms,
        })
    return out


def trend(xs: list, n_perm: int = 5000, seed: int = 1):
    """Spearman rank correlation of `xs` with their order, and a two-sided
    permutation p-value. Eleven utterances per third cannot show a drift by
    eye; this asks whether lag rises (or falls) with position at all."""
    import numpy as np
    if len(xs) < 5:
        return None, None
    y = np.argsort(np.argsort(xs, kind="stable"), kind="stable").astype(float)
    x = np.arange(len(xs), dtype=float)
    def rho(v):
        return float(np.corrcoef(x, v)[0, 1])
    r = rho(y)
    rng = np.random.default_rng(seed)
    hits = sum(abs(rho(rng.permutation(y))) >= abs(r) for _ in range(n_perm))
    return r, (hits + 1) / (n_perm + 1)


def thirds(xs: list):
    n = len(xs)
    k = max(1, n // 3)
    return xs[:k], xs[n - k:]


def report(run: dict, clip: dict, path: Path, emulator: bool) -> None:
    extra = run.get("extra") or {}
    windows = extra.get("timeline") or []
    events = extra.get("final_events") or []
    variant = extra.get("variant") or ""
    name = f"Arm {run['arm']}{' / ' + variant if variant else ''}"
    print(f"\n=== {name} / {run['clip_id']}  [{path.name}]"
          + ("  [EMULATOR -- plumbing only, no numbers]" if emulator else ""))

    audio_s = run["audio_duration_ms"] / 1000
    sc = score_pair((CORPUS / clip["ref"]).read_text(encoding="utf-8").strip(),
                    run.get("hypothesis") or "", clip["language"])
    print(f"  audio                    {audio_s:.1f} s, {len(clip['segments'])} utterances"
          + (f"   ERROR: {run['error']}" if run.get("error") else ""))
    print(f"  WER / CER                {sc['wer'] * 100:.2f}% / {sc['cer'] * 100:.2f}%"
          f"  ({sc['ref_words']} ref words; S {sc['substitutions']} "
          f"D {sc['deletions']} I {sc['insertions']})")
    print(f"  rtf_sustained, session   {fmt(run.get('rtf_sustained'), '.3f')}"
          + (f"   (finals only {extra['rtf_finals_only']:.3f})"
             if extra.get("rtf_finals_only") is not None else ""))

    if not windows:
        print("  no timeline in this row (harness before Phase 5, or a short clip)")
        return

    rtfs = [w["rtf"] for w in windows if w.get("rtf") is not None]
    full = [w["rtf"] for w in windows[:-1] if w.get("rtf") is not None] or rtfs
    first, last = thirds(full)
    print(f"  rtf per 30 s window      median {fmt(med(full), '.2f')}, "
          f"max {fmt(max(full, default=None), '.2f')}; "
          f"first third {fmt(med(first), '.2f')} -> last third {fmt(med(last), '.2f')}")
    wr, wp = trend(full)
    print(f"    trend over the session: Spearman rho {fmt(wr, '+.2f')}, "
          f"permutation p {fmt(wp, '.2f')}  (full windows only)")
    partial = len(windows) > 1 and (windows[-1]["audio_ms"] - windows[-2]["audio_ms"]) < 30_000
    print(f"    windows                " + " ".join(fmt(r, ".2f") for r in rtfs)
          + ("*   (* last window partial: "
             f"{(windows[-1]['audio_ms'] - windows[-2]['audio_ms']) / 1000:.1f} s)"
             if partial else ""))
    slips = [w.get("slip_ms") for w in windows]
    print(f"  feeder slip              max {run.get('max_slip_ms')} ms; per window "
          + " ".join(fmt(s) for s in slips))
    t0, t1 = windows[0].get("temp_c"), windows[-1].get("temp_c")
    print(f"  battery temp             {fmt(run.get('battery_temp_c_before'), '.1f')} before, "
          f"{fmt(t0, '.1f')} at 30 s -> {fmt(t1, '.1f')} at end, "
          f"{fmt(run.get('battery_temp_c_after'), '.1f')} after (lags; README finding 28)")
    heads = [w.get("thermal_headroom") for w in windows]
    stats = [w.get("thermal_status") for w in windows]
    print(f"  thermal headroom         "
          + (" ".join(fmt(h, ".2f") for h in heads) if any(h is not None for h in heads)
             else "not reported by this device"))
    print(f"  thermal status           worst "
          f"{THERMAL_STATUS.get(max((s for s in stats if s is not None), default=-1), '—')}")
    if any("audio_mode" in w for w in windows):
        calls = sum(1 for w in windows if w.get("audio_mode") not in (None, 0))
        screen = sum(1 for w in windows if w.get("screen_on"))
        flag = "   <-- PHONE IN USE: confounded" if calls else ""
        print(f"  phone in use             call in {calls} of {len(windows)} windows, "
              f"screen on in {screen}{flag}")
    else:
        print("  phone in use             not recorded (harness before the in-use guard)")
    print(f"  rss                      {fmt(windows[0].get('rss_mb'))} MB at 30 s -> "
          f"{fmt(windows[-1].get('rss_mb'))} MB at end; peak {fmt(run.get('peak_rss_mb'))} MB")
    print(f"  battery                  {run.get('battery_pct_before')}% -> "
          f"{run.get('battery_pct_after')}%")

    if not events:
        print("  no final events")
        return
    utts = utterance_finals(clip, events)
    got = [u for u in utts if u["signed_ms"] is not None]
    signed = [u["signed_ms"] for u in got]
    clamped = [max(0, s) for s in signed]
    e_first, e_last = thirds([max(0, u["signed_ms"]) for u in got])
    print(f"  final text, per utterance, from the end of its audio "
          f"({len(got)} of {len(utts)} utterances had a final):")
    print(f"    median {fmt(med(clamped))} ms (signed {fmt(med(signed))}), "
          f"p90 {fmt(sorted(clamped)[int(0.9 * (len(clamped) - 1))] if clamped else None)} ms, "
          f"max {fmt(max(clamped, default=None))} ms")
    print(f"    first third {fmt(med(e_first))} ms -> last third {fmt(med(e_last))} ms")
    rho, pval = trend([u["signed_ms"] for u in got])
    print(f"    trend over the session: Spearman rho {fmt(rho, '+.2f')}, "
          f"permutation p {fmt(pval, '.2f')}  (lag against position; p < 0.05 = a drift)")
    no_text = [u["i"] for u in utts if not u["has_text"]]
    shared = sum(1 for ev in events
                 if sum(1 for s in clip["segments"]
                        if ev["start_ms"] < s["end_s"] * 1000 - OVERLAP_TOLERANCE_MS
                        and ev["end_ms"] > s["start_s"] * 1000 + OVERLAP_TOLERANCE_MS) > 1)
    print(f"    {len(events)} final events; {shared} span more than one utterance; "
          f"utterances with no text: {no_text or 'none'}")
    lags = [ev["commit_ms"] - ev["end_ms"] for ev in events]
    print(f"    commit after the arm's own segment end: median {fmt(med(lags))} ms, "
          f"max {fmt(max(lags, default=None))} ms")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", type=Path)
    ap.add_argument("--include-emulator", action="store_true",
                    help="also report emulator runs (plumbing only; D1)")
    args = ap.parse_args()

    clips = manifest_clips()
    found = 0
    for path in result_paths(args.paths or [RESULTS]):
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue
        emulator = bool((doc.get("device") or {}).get("is_emulator"))
        if emulator and not args.include_emulator:
            continue
        if doc.get("notes", {}).get("complete") is False:
            print(f"[session] WARNING {path.name} is a checkpoint of a run that did "
                  f"not finish", file=sys.stderr)
        for run in doc.get("runs", []):
            if run.get("bucket") != "session":
                continue
            clip = clips.get(run.get("clip_id"))
            if not clip or "segments" not in clip:
                continue
            report(run, clip, path, emulator)
            found += 1
    if not found:
        print("no session rows found", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
