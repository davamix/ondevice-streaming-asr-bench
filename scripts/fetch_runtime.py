#!/usr/bin/env python3
"""Fetch the sherpa-onnx Android runtime (Arms D and E) at a pinned version.

The AAR is a build input, not a model, but it gets the same treatment as the
weights (PLAN.md D8): fetched from its canonical source, verified against a
pinned SHA-256, and never committed. It lands in bench/app/libs/ (gitignored),
where bench/app/build.gradle.kts expects it.

    fetch_runtime.py            # download + verify
    fetch_runtime.py --check    # verify what is already there

**Why the static-link build.** sherpa-onnx publishes two AARs per release. The
regular one ships its own `libonnxruntime.so`, and so does Moonshine's AAR
(Arm B), a 6 MB reduced ORT build. Two libraries at the same path in one APK
fail the packaging step. `pickFirst` would silence that by giving one of the
two runtimes the other's ONNX Runtime, which is a silent measurement change
at best. The static-link AAR links ORT into `libsherpa-onnx-jni.so` for arm64
and x86_64, so each stack keeps the runtime it was built against (D6).
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIBS = ROOT / "bench" / "app" / "libs"

VERSION = "1.13.8"
NAME = f"sherpa-onnx-static-link-onnxruntime-{VERSION}.aar"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{NAME}"
# Matches the `digest` GitHub records for the release asset (checked
# 2026-09-23), so this pins the published file, not merely the first download.
SHA256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true",
                    help="only verify the file already in bench/app/libs/")
    args = ap.parse_args()

    dest = LIBS / NAME
    if dest.exists() and sha256(dest) == SHA256:
        print(f"ok: {dest.relative_to(ROOT)} ({dest.stat().st_size / 1e6:.1f} MB, sha256 verified)")
        return 0
    if args.check:
        print(f"missing or wrong hash: {dest}", file=sys.stderr)
        return 1

    LIBS.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part")
    print(f"fetching {URL}")
    urllib.request.urlretrieve(URL, tmp)
    got = sha256(tmp)
    if got != SHA256:
        tmp.unlink(missing_ok=True)
        print(f"SHA-256 mismatch for {NAME}: got {got}, pinned {SHA256}. "
              f"Upstream changed the asset; re-verify before trusting any "
              f"number measured with it.", file=sys.stderr)
        return 1
    tmp.replace(dest)
    print(f"ok: {dest.relative_to(ROOT)} ({dest.stat().st_size / 1e6:.1f} MB, sha256 verified)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
