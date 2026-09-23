#!/usr/bin/env python3
"""Push the corpus, run the harness, pull the results.

One command, reproducible, scriptable (PLAN.md §9).

    # plumbing check on the emulator first -- always (§11.5)
    run_bench.py --device emulator --probe
    run_bench.py --device emulator --arms A --buckets short --reps 1

    # then the phone, which is where every published number comes from (D1)
    run_bench.py --device physical --probe
    run_bench.py --device physical --arms A --langs en,es --reps 4

Writes to exactly two device paths and nothing else (§11.2). `--clean` removes
the pushed corpus afterwards; uninstalling the app removes everything.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from devicelib import (
    APP_FILES,
    APPLICATION_ID,
    ROOT,
    TEST_RUNNER,
    Device,
    adb_path,
    device_props,
    pick_device,
    preflight,
    sh,
    shell,
)

BENCH = ROOT / "bench"
CORPUS = ROOT / "corpus"
RESULTS = ROOT / "results"


def gradlew() -> str:
    return str(BENCH / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))


def build(assemble: bool = True) -> None:
    if not assemble:
        return
    print("[build] assembling debug + androidTest APKs")
    proc = subprocess.run(
        [gradlew(), ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
        cwd=BENCH, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        tail = "\n".join((proc.stdout + proc.stderr).splitlines()[-30:])
        raise SystemExit(f"[build] FAILED\n{tail}")
    print("[build] ok")


def install(dev: Device) -> None:
    app = BENCH / "app/build/outputs/apk/debug/app-debug.apk"
    test = BENCH / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    for apk in (app, test):
        if not apk.exists():
            raise SystemExit(f"missing {apk} -- run without --no-build")
    print(f"[install] {app.name}")
    sh(dev, "install", "-r", "-t", str(app))
    print(f"[install] {test.name}")
    sh(dev, "install", "-r", "-t", str(test))


def push_corpus(dev: Device, buckets: list[str]) -> None:
    """Push manifest + only the audio the requested buckets need."""
    manifest_path = CORPUS / "manifest.json"
    if not manifest_path.exists():
        raise SystemExit("corpus/manifest.json missing -- run scripts/build_corpus.py")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    dest = f"{APP_FILES}/corpus"
    shell(dev, f"mkdir -p {dest}/audio/short {dest}/audio/session")
    sh(dev, "push", str(manifest_path), f"{dest}/manifest.json")

    wanted = [c for c in manifest["clips"] if c["bucket"] in buckets]
    total_mb = 0.0
    print(f"[push] {len(wanted)} clips in buckets {buckets}")
    for clip in wanted:
        local = CORPUS / clip["audio"]
        if not local.exists():
            raise SystemExit(f"missing {local} -- run scripts/build_corpus.py")
        total_mb += local.stat().st_size / 1e6
        sh(dev, "push", str(local), f"{dest}/{clip['audio']}")
    print(f"[push] {total_mb:.1f} MB pushed to {dest}")


def run_instrumentation(dev: Device, test_method: str, extras: dict[str, str],
                        timeout_s: int) -> str:
    args = [adb_path(), "-s", dev.serial, "shell", "am", "instrument", "-w",
            "-e", "class", f"{APPLICATION_ID}.BenchmarkTest#{test_method}"]
    for k, v in extras.items():
        args += ["-e", k, v]
    args.append(TEST_RUNNER)

    print(f"[run] {test_method} {extras}")
    proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout_s)
    out = proc.stdout + proc.stderr
    print(out.strip()[-4000:])
    if "FAILURES!!!" in out or "Process crashed" in out:
        print("[run] instrumentation reported failures (results may still be usable)",
              file=sys.stderr)
    return out


def pull_results(dev: Device, tag: str) -> Path:
    props = device_props(dev)
    model = props.get("model", "device").replace(" ", "_")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    out_dir = RESULTS / f"{model}-{stamp}-{tag}"
    out_dir.mkdir(parents=True, exist_ok=True)

    listing = shell(dev, f"ls {APP_FILES}/results/ 2>/dev/null", check=False).split()
    if not listing:
        print("[pull] no result files on device")
        return out_dir

    for name in listing:
        name = name.strip()
        if not name:
            continue
        sh(dev, "pull", f"{APP_FILES}/results/{name}", str(out_dir / name))
        print(f"[pull] {name}")

    # Device provenance alongside the numbers -- no serial (§11.7).
    (out_dir / "device.json").write_text(
        json.dumps(props, indent=2), encoding="utf-8"
    )
    print(f"[pull] -> {out_dir}")
    return out_dir


def clean_device(dev: Device) -> None:
    """Remove pushed corpus and results. Scoped to §11.2 paths only."""
    print(f"[clean] removing {APP_FILES}/corpus and {APP_FILES}/results")
    shell(dev, f"rm -rf {APP_FILES}/corpus", check=False)
    shell(dev, f"rm -rf {APP_FILES}/results", check=False)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", help="serial, or 'emulator' / 'physical'")
    ap.add_argument("--arms", default="A")
    ap.add_argument("--langs", default="en,es")
    ap.add_argument("--buckets", default="short")
    ap.add_argument("--reps", type=int, default=4)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--label", default=None)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--probe", action="store_true",
                    help="only probe platform recognition support, then exit")
    ap.add_argument("--smoke", action="store_true",
                    help="only run the single-clip Arm A smoke test")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--clean", action="store_true",
                    help="remove pushed corpus/results from the device afterwards")
    ap.add_argument("--skip-preflight", action="store_true")
    ap.add_argument("--timeout", type=int, default=3600)
    args = ap.parse_args()

    dev = pick_device(args.device)
    print(f"[device] {dev.short}")
    if not dev.is_emulator:
        print("[device] PHYSICAL DEVICE -- this is the owner's only phone. "
              "Write scope is limited to the two paths in PLAN.md §11.2.")

    if not args.skip_preflight:
        preflight(dev)

    build(assemble=not args.no_build)
    if not args.no_install:
        install(dev)

    buckets = [b.strip() for b in args.buckets.split(",") if b.strip()]

    if args.probe:
        run_instrumentation(
            dev, "probeRecognitionSupport",
            {"langs": "en-US,es-ES"}, timeout_s=300,
        )
        out = pull_results(dev, "probe")
        support = out / "recognition-support.json"
        if support.exists():
            print("\n=== recognition support ===")
            print(support.read_text(encoding="utf-8"))
        return 0

    if not args.no_push:
        push_corpus(dev, buckets)

    if args.smoke:
        run_instrumentation(dev, "smokeArmA", {}, timeout_s=600)
        pull_results(dev, "smoke")
        if args.clean:
            clean_device(dev)
        return 0

    label = args.label or f"arms{args.arms.replace(',', '')}-{args.buckets.replace(',', '')}"
    run_instrumentation(
        dev, "benchmark",
        {
            "arms": args.arms,
            "langs": args.langs,
            "buckets": args.buckets,
            "reps": str(args.reps),
            "threads": str(args.threads),
            "label": label,
            "seed": str(args.seed),
        },
        timeout_s=args.timeout,
    )
    pull_results(dev, label)

    if args.clean:
        clean_device(dev)

    # Post-run thermal note (§11.6).
    from devicelib import battery
    bat = battery(dev)
    print(f"[post] battery: {bat.get('pct')}% {bat.get('temp_c')} C")
    return 0


if __name__ == "__main__":
    sys.exit(main())
