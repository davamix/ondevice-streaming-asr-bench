#!/usr/bin/env python3
"""Fetch model weights at the revisions pinned in models/MODELS.md.

Downloads into models/<arm>/ (gitignored). Weights are never committed: see
PLAN.md D8. Sizes and revisions here must stay in sync with models/MODELS.md.

    fetch_models.py --list              # what is available, and what it costs
    fetch_models.py moonshine-tiny-en   # fetch one
    fetch_models.py --arm B             # fetch everything an arm needs
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from huggingface_hub import hf_hub_download, snapshot_download

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"

MOONSHINE_REPO = "moonshine-ai/moonshine-voice-assets"
MOONSHINE_REV = "0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae"

SPECS: dict[str, dict] = {
    # ── Arm B: Moonshine streaming English ──────────────────────────────
    "moonshine-tiny-en": {
        "arm": "B", "repo": MOONSHINE_REPO, "rev": MOONSHINE_REV,
        "prefix": "model/tiny-streaming-en/quantized_26_08_21",
        "size_mb": 77.7, "licence": "MIT", "langs": "en",
    },
    "moonshine-small-en": {
        "arm": "B", "repo": MOONSHINE_REPO, "rev": MOONSHINE_REV,
        "prefix": "model/small-streaming-en/quantized_26_08_21",
        "size_mb": 224.1, "licence": "MIT", "langs": "en",
    },
    "moonshine-medium-en": {
        "arm": "B", "repo": MOONSHINE_REPO, "rev": MOONSHINE_REV,
        "prefix": "model/medium-streaming-en/quantized_26_08_21",
        "size_mb": 416.0, "licence": "MIT", "langs": "en",
    },
    # ── Arm C: Moonshine base-es (NON-COMMERCIAL) ───────────────────────
    "moonshine-base-es": {
        "arm": "C", "repo": MOONSHINE_REPO, "rev": MOONSHINE_REV,
        "prefix": "model/base-es/quantized/base-es",
        "size_mb": 64.8, "licence": "Moonshine Community (NON-COMMERCIAL)",
        "langs": "es",
    },
    # ── Arm D: Parakeet ─────────────────────────────────────────────────
    "parakeet-tdt-v3-int8": {
        "arm": "D", "repo": "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        "rev": "2bda32ec70b097a55adaa07d9a7173915b43cc78",
        "files": ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx",
                  "tokens.txt"],
        "size_mb": 670.5, "licence": "CC-BY-4.0", "langs": "en,es",
    },
    # ── Arm E: Whisper control ──────────────────────────────────────────
    "whisper-small-int8": {
        "arm": "E", "repo": "csukuangfj/sherpa-onnx-whisper-small",
        "rev": "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
        "files": ["small-encoder.int8.onnx", "small-decoder.int8.onnx",
                  "small-tokens.txt"],
        "size_mb": 375.4, "licence": "MIT", "langs": "en,es",
    },
    "whisper-base-int8": {
        "arm": "E", "repo": "csukuangfj/sherpa-onnx-whisper-base",
        "rev": "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
        "files": ["base-encoder.int8.onnx", "base-decoder.int8.onnx",
                  "base-tokens.txt"],
        "size_mb": 160.6, "licence": "MIT", "langs": "en,es",
    },
    # ── Shared: VAD segmentation for arms C, D, E ───────────────────────
    "silero-vad": {
        "arm": "CDE", "repo": "onnx-community/silero-vad",
        "rev": "e71cae966052b992a7eca6b17738916ce0eca4ec",
        "files": ["onnx/model.onnx"],
        "rename": {"onnx/model.onnx": "silero_vad.onnx"},
        "size_mb": 2.24, "licence": "MIT", "langs": "-",
    },
}


def fetch_one(name: str) -> Path:
    spec = SPECS[name]
    dest = MODELS / name
    dest.mkdir(parents=True, exist_ok=True)

    if "NON-COMMERCIAL" in spec["licence"]:
        print(f"  ! {name} is {spec['licence']} -- experiment only, do not "
              f"redistribute or ship.")

    if "prefix" in spec:
        # Moonshine: a directory of .ort components. Pull just that subtree.
        snapshot_download(
            repo_id=spec["repo"], revision=spec["rev"],
            allow_patterns=[f"{spec['prefix']}/*"],
            local_dir=dest,
        )
        # Flatten so the on-device path is <model>/<file>, not a deep prefix.
        src = dest / spec["prefix"]
        if src.is_dir():
            for f in src.iterdir():
                if f.is_file():
                    shutil.move(str(f), str(dest / f.name))
            top = dest / spec["prefix"].split("/")[0]
            shutil.rmtree(top, ignore_errors=True)
    else:
        rename = spec.get("rename", {})
        for rel in spec["files"]:
            p = hf_hub_download(repo_id=spec["repo"], revision=spec["rev"],
                                filename=rel, local_dir=dest)
            target_name = rename.get(rel)
            if target_name:
                shutil.move(p, dest / target_name)
                nested = dest / rel.split("/")[0]
                if nested.is_dir():
                    shutil.rmtree(nested, ignore_errors=True)

    actual = sum(f.stat().st_size for f in dest.rglob("*") if f.is_file()) / 1e6
    print(f"  {name}: {actual:.1f} MB on disk (expected ~{spec['size_mb']}) -> {dest}")
    if abs(actual - spec["size_mb"]) > max(2.0, spec["size_mb"] * 0.05):
        print(f"  ! size mismatch for {name} -- upstream may have changed. "
              f"Re-verify before trusting any number measured with it.",
              file=sys.stderr)
    return dest


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="*", choices=sorted(SPECS) + [], help="models to fetch")
    ap.add_argument("--arm", help="fetch every model for an arm (B, C, D, E)")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()

    if args.list or (not args.names and not args.arm and not args.all):
        print(f"{'name':24s} {'arm':4s} {'size':>9s}  {'langs':6s} licence")
        print("-" * 78)
        for n, s in SPECS.items():
            print(f"{n:24s} {s['arm']:4s} {s['size_mb']:>7.1f}MB  "
                  f"{s['langs']:6s} {s['licence']}")
        total = sum(s["size_mb"] for s in SPECS.values())
        print("-" * 78)
        print(f"{'ALL':24s} {'':4s} {total:>7.1f}MB")
        return 0

    names = list(args.names)
    if args.all:
        names = list(SPECS)
    elif args.arm:
        names = [n for n, s in SPECS.items() if args.arm.upper() in s["arm"]]
        if not names:
            print(f"no models for arm {args.arm!r}", file=sys.stderr)
            return 1

    MODELS.mkdir(parents=True, exist_ok=True)
    print(f"fetching {len(names)}: {', '.join(names)}")
    for n in names:
        fetch_one(n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
