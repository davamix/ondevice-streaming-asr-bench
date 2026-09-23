#!/usr/bin/env python3
"""Fetch the corpus sources at pinned revisions.

Downloads into corpus/work/ (gitignored). Nothing here is committed: the point
of pinning revisions is that a re-download reproduces byte-identical inputs, so
mirroring them into the repo would duplicate a source that already exists.

Sources are the FLEURS test split for each language plus LibriSpeech
test-other. FLEURS ships a single parquet per language per split under
parquet-data/, which is far cheaper to handle than the tar.gz + tsv pair.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from huggingface_hub import hf_hub_download

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "corpus" / "work"

# Pinned revisions. A silent re-download must not change what was measured.
SOURCES = {
    "fleurs_en": {
        "repo_id": "google/fleurs",
        "repo_type": "dataset",
        "revision": "70bb2e84b976b7e960aa89f1c648e09c59f894dd",
        "filename": "parquet-data/en_us/test-00000-of-00001.parquet",
        "licence": "CC-BY-4.0",
        "language": "en",
        "note": "FLEURS en_us test split",
    },
    "fleurs_es": {
        "repo_id": "google/fleurs",
        "repo_type": "dataset",
        "revision": "70bb2e84b976b7e960aa89f1c648e09c59f894dd",
        "filename": "parquet-data/es_419/test-00000-of-00001.parquet",
        "licence": "CC-BY-4.0",
        "language": "es",
        "note": "FLEURS es_419 test split",
    },
    "librispeech_other": {
        "repo_id": "openslr/librispeech_asr",
        "repo_type": "dataset",
        "revision": "71cacbfb7e2354c4226d01e70d77d5fca3d04ba1",
        "filename": "all/test.other/0000.parquet",
        "licence": "CC-BY-4.0",
        "language": "en",
        "note": "LibriSpeech test-other -- the noisy-English stress case",
    },
}


def fetch(keys: list[str]) -> dict[str, Path]:
    WORK.mkdir(parents=True, exist_ok=True)
    out: dict[str, Path] = {}
    for key in keys:
        spec = SOURCES[key]
        print(f"[fetch] {key}: {spec['repo_id']}@{spec['revision'][:8]} {spec['filename']}")
        path = hf_hub_download(
            repo_id=spec["repo_id"],
            repo_type=spec["repo_type"],
            revision=spec["revision"],
            filename=spec["filename"],
            local_dir=WORK / key,
        )
        size_mb = Path(path).stat().st_size / 1e6
        print(f"[fetch] {key}: {size_mb:.1f} MB -> {path}")
        out[key] = Path(path)
    return out


def write_sources_md(paths: dict[str, Path]) -> None:
    """corpus/SOURCES.md is committed; it is the provenance record."""
    lines = [
        "# Corpus sources",
        "",
        "Fetched by `scripts/fetch_corpus.py` at the revisions pinned below.",
        "Audio itself is gitignored -- rebuild it with `scripts/build_corpus.py`.",
        "",
        "| Key | Repo | Revision | File | Lang | Licence |",
        "|---|---|---|---|---|---|",
    ]
    for key, spec in SOURCES.items():
        lines.append(
            f"| `{key}` | [`{spec['repo_id']}`](https://huggingface.co/datasets/{spec['repo_id']}) "
            f"| `{spec['revision']}` | `{spec['filename']}` | {spec['language']} | {spec['licence']} |"
        )
    lines += [
        "",
        "## Why these",
        "",
        "FLEURS `en_us` and `es_419` are the same corpus recorded the same way in both",
        "languages, so an English-vs-Spanish comparison is not confounded by domain or",
        "recording conditions. LibriSpeech `test-other` adds the noisy-English stress case",
        "that FLEURS's clean read speech does not cover.",
        "",
        "Both are CC-BY-4.0, so a small curated sample could be redistributed with",
        "attribution if reproducibility ever needs it.",
        "",
        "## Attribution",
        "",
        "- FLEURS: Conneau et al., *FLEURS: Few-shot Learning Evaluation of Universal",
        "  Representations of Speech* (Google, 2022). CC-BY-4.0.",
        "- LibriSpeech: Panayotov et al., *LibriSpeech: an ASR corpus based on public domain",
        "  audio books* (ICASSP 2015). CC-BY-4.0.",
        "",
    ]
    dest = ROOT / "corpus" / "SOURCES.md"
    dest.write_text("\n".join(lines), encoding="utf-8")
    print(f"[fetch] wrote {dest}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--only",
        nargs="*",
        choices=sorted(SOURCES),
        help="fetch a subset (default: all)",
    )
    args = ap.parse_args()
    keys = args.only or sorted(SOURCES)
    paths = fetch(keys)
    write_sources_md(paths)
    return 0


if __name__ == "__main__":
    sys.exit(main())
