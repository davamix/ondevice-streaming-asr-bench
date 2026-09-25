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


def prepare_dirs(dev: Device, model_dirs: list[str] | None = None) -> None:
    """Have the *app* create its directory tree before anything is pushed.

    `adb push` writes as the `shell` user, and on Android 11+ a directory
    created by shell inside an app's own external files dir is not reliably
    readable by that app. The push reports success, the app sees an empty
    corpus, and the failure looks like a missing file rather than a
    permissions problem. Creating the tree app-side first avoids it.
    """
    print("[prepare] creating app-owned directory tree")
    extras = {"model_dirs": ",".join(model_dirs)} if model_dirs else {}
    run_instrumentation(dev, "prepareDirs", extras, timeout_s=180)


def push_models(dev: Device, names: list[str]) -> None:
    """Push pinned model directories to the app's files dir.

    Weights are never bundled in the APK -- at 78-416 MB per Moonshine variant
    that would mean rebuilding and reinstalling for every model change (§9).
    They are also never fetched by the SDK's own downloader, which would let a
    silent upstream change alter what was measured; these are the exact files
    pinned in models/MODELS.md (D8).
    """
    models_root = ROOT / "models"
    dest_root = f"{APP_FILES}/models"
    total = 0.0
    for name in names:
        local = models_root / name
        if not local.is_dir():
            raise SystemExit(
                f"missing {local} -- run: python scripts/fetch_models.py {name}"
            )
        files = [f for f in local.iterdir() if f.is_file()]
        size = sum(f.stat().st_size for f in files) / 1e6
        total += size
        print(f"[push-models] {name}: {len(files)} files, {size:.1f} MB")
        shell(dev, f"mkdir -p {dest_root}/{name}")
        for f in files:
            sh(dev, "push", str(f), f"{dest_root}/{name}/{f.name}")
    print(f"[push-models] {total:.1f} MB total -> {dest_root}")


def push_corpus(dev: Device, buckets: list[str],
                sources: list[str] | None = None) -> None:
    """Push manifest + only the audio the requested buckets need."""
    manifest_path = CORPUS / "manifest.json"
    if not manifest_path.exists():
        raise SystemExit("corpus/manifest.json missing -- run scripts/build_corpus.py")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    dest = f"{APP_FILES}/corpus"
    sh(dev, "push", str(manifest_path), f"{dest}/manifest.json")

    wanted = [c for c in manifest["clips"] if c["bucket"] in buckets
              and (not sources or c.get("source") in sources)]
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


SELF_RECORDED = CORPUS / "self-recorded"


def mic_prompt(lang: str, count: int) -> str:
    """Sentences for the owner to read: FLEURS references, public text.

    The first `count` distinct sentences of the language's original FLEURS
    short clips, in clip order, so every take of a language reads the same
    script and a live take can be scored against it (qualitatively, §8).
    """
    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    source = {"en": "fleurs_en", "es": "fleurs_es"}[lang]
    seen: list[str] = []
    for c in sorted(manifest["clips"], key=lambda c: c["clip_id"]):
        if c["bucket"] != "short" or c.get("source") != source:
            continue
        text = (CORPUS / c["ref"]).read_text(encoding="utf-8").strip()
        if text not in seen:
            seen.append(text)
        if len(seen) == count:
            break
    return "\n\n".join(seen) + "\n"


def run_mic_check(dev: Device, arm: str, lang: str, seconds: int,
                  sentences: int, timeout_s: int) -> None:
    """The single real-microphone check (PLAN.md §7, §10 step 20).

    Needs the owner at the phone: it asks for microphone access on screen,
    then shows the sentences to read and a countdown. This echoes the
    harness's `MIC:` log lines so the reader can follow along here too.

    Everything it produces is the owner's voice, so it is pulled into
    corpus/self-recorded/, which is gitignored, never into results/ (§11.7).
    """
    prompt = mic_prompt(lang, sentences)
    local = SELF_RECORDED / f"prompt-{lang}.txt"
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text(prompt, encoding="utf-8")
    sh(dev, "push", str(local), f"{APP_FILES}/mic/prompt-{lang}.txt")
    print(f"\n[mic] the phone will show {sentences} sentences to read "
          f"({len(prompt.split())} words) and a {seconds}s countdown.")
    print("[mic] unlock the phone and keep it in hand; tap Allow if it asks "
          "for the microphone.\n")

    # Only lines from now on: -T 1 prints the last line and follows. The
    # device's log buffer is left alone (no logcat -c on the owner's phone).
    log = subprocess.Popen(
        [adb_path(), "-s", dev.serial, "logcat", "-T", "1", "-v", "time",
         "-s", "AsrBench:*"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
        encoding="utf-8", errors="replace",
    )
    import threading

    def echo() -> None:
        for line in log.stdout:  # type: ignore[union-attr]
            if "MIC:" in line or "ABORT" in line or "E/AsrBench" in line:
                print("  " + line.split("AsrBench", 1)[-1].lstrip(":( 0123456789)").strip(),
                      flush=True)

    t = threading.Thread(target=echo, daemon=True)
    t.start()
    try:
        run_instrumentation(dev, "micCheck", {
            "arm": arm, "lang": lang, "seconds": str(seconds), "label": "mic",
        }, timeout_s=timeout_s)
    finally:
        log.terminate()

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    out_dir = SELF_RECORDED / f"{stamp}-{lang}"
    out_dir.mkdir(parents=True, exist_ok=True)
    listing = shell(dev, f"ls {APP_FILES}/mic/ 2>/dev/null", check=False).split()
    have = {p.name for p in SELF_RECORDED.rglob("*") if p.is_file()}
    for name in listing:
        name = name.strip()
        if not name or name.startswith("prompt-") or name in have:
            continue
        sh(dev, "pull", f"{APP_FILES}/mic/{name}", str(out_dir / name))
        print(f"[mic] pulled {name} -> {out_dir.relative_to(ROOT)} (gitignored)")


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
    ap.add_argument("--sources", default="",
                    help="comma-separated corpus sources to include (default: all), "
                         "e.g. fleurs_en_norm,librispeech_other")
    ap.add_argument("--reps", type=int, default=4)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--label", default=None)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--download-lang", metavar="BCP47",
                    help="request an on-device language pack (e.g. en-US), then exit")
    ap.add_argument("--probe", action="store_true",
                    help="only probe platform recognition support, then exit")
    ap.add_argument("--smoke", action="store_true",
                    help="only run the single-clip Arm A smoke test")
    ap.add_argument("--plumbing", action="store_true",
                    help="only run the plumbing check (no recognizer needed)")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--push-models", metavar="NAMES",
                    help="comma-separated model dirs to push (e.g. "
                         "moonshine-tiny-en,moonshine-small-en), then exit "
                         "unless a run is also requested")
    ap.add_argument("--clean", action="store_true",
                    help="remove pushed corpus/results from the device afterwards")
    ap.add_argument("--session-cap-s", type=int, default=None,
                    help="lower the 10-minute continuous-inference cap (the "
                         "harness refuses to raise it); used to exercise the "
                         "per-break checkpoint on the emulator")
    ap.add_argument("--partial-ms", type=int, default=None,
                    help="arms C/D/E: re-decode open speech for partial text "
                         "every N ms (default 500; 0 = final text only)")
    ap.add_argument("--moonshine-options", metavar="K=V,...",
                    help="arm C: Moonshine option overrides for an ablation, e.g. "
                         "max_tokens_per_second=13; rows get a distinct variant")
    ap.add_argument("--mic", metavar="ARM",
                    help="the real-microphone check with this arm, e.g. "
                         "D:parakeet-tdt-v3-int8, in the first of --langs; needs "
                         "the owner at the phone (PLAN.md §7), then exits")
    ap.add_argument("--mic-seconds", type=int, default=60)
    ap.add_argument("--mic-sentences", type=int, default=6,
                    help="corpus sentences shown on the phone to read")
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
    sources = [x.strip() for x in args.sources.split(",") if x.strip()]

    if args.download_lang:
        prepare_dirs(dev)
        run_instrumentation(
            dev, "downloadLanguagePack",
            {"lang": args.download_lang, "timeout_s": "300"},
            timeout_s=600,
        )
        out = pull_results(dev, f"langpack-{args.download_lang}")
        f = out / f"language-pack-{args.download_lang}.json"
        if f.exists():
            print("\n=== language pack outcome ===")
            print(f.read_text(encoding="utf-8"))
        return 0

    if args.probe:
        prepare_dirs(dev)
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

    if args.push_models:
        names = [m.strip() for m in args.push_models.split(",") if m.strip()]
        prepare_dirs(dev, model_dirs=names)
        push_models(dev, names)
        if args.arms == "A" and not args.label and not args.mic:
            return 0

    if args.mic:
        prepare_dirs(dev)
        lang = args.langs.split(",")[0].strip()
        run_mic_check(dev, args.mic, lang, args.mic_seconds, args.mic_sentences,
                      timeout_s=args.timeout)
        from devicelib import battery
        bat = battery(dev)
        print(f"[post] battery: {bat.get('pct')}% {bat.get('temp_c')} C")
        return 0

    if not args.no_push:
        prepare_dirs(dev)
        push_corpus(dev, buckets, sources)

    if args.plumbing:
        run_instrumentation(dev, "plumbing", {}, timeout_s=600)
        pull_results(dev, "plumbing")
        if args.clean:
            clean_device(dev)
        return 0

    if args.smoke:
        run_instrumentation(dev, "smokeArmA", {}, timeout_s=600)
        pull_results(dev, "smoke")
        if args.clean:
            clean_device(dev)
        return 0

    label = args.label or f"arms{args.arms.replace(',', '')}-{args.buckets.replace(',', '')}"
    extras = {
        "arms": args.arms,
        "langs": args.langs,
        "buckets": args.buckets,
        "sources": args.sources,
        "reps": str(args.reps),
        "threads": str(args.threads),
        "label": label,
        "seed": str(args.seed),
    }
    if args.session_cap_s is not None:
        extras["session_cap_s"] = str(args.session_cap_s)
    if args.partial_ms is not None:
        extras["partial_ms"] = str(args.partial_ms)
    if args.moonshine_options:
        extras["moonshine_options"] = args.moonshine_options
    run_instrumentation(dev, "benchmark", extras, timeout_s=args.timeout)
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
