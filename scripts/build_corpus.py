#!/usr/bin/env python3
"""Build the benchmark corpus from the fetched parquet sources.

Produces, under corpus/audio/ (gitignored, rebuildable):

  short/    ~5 s utterances -- latency per utterance, and the clip length that
            makes Whisper's 30 s padding penalty visible.
  session/  5-10 min of continuous speech -- sustained RTF and thermals.

Sessions are built by concatenating scored clips with inserted silence gaps.
That is the trick that makes multi-minute continuous audio have an *exact*
reference transcript: hand-recorded audio cannot give you that, and sustained
RTF measured against audio you cannot score tells you only half of what you
need.

Everything is 16 kHz mono PCM16. No exceptions -- a sample-rate mismatch
between arms would silently become a latency difference.

Selection is seeded and deterministic: re-running this reproduces the same
corpus from the same pinned parquet files.
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "corpus" / "work"
AUDIO = ROOT / "corpus" / "audio"
REFS = ROOT / "corpus" / "refs"

SEED = 20260922  # the date the plan's facts were verified; arbitrary but fixed

SAMPLE_RATE = 16000

# Short-utterance selection window. FLEURS clips run long (read paragraphs), so
# this band is what yields genuinely ~5 s utterances rather than 20 s ones.
SHORT_MIN_S, SHORT_MAX_S = 3.0, 8.0
SHORT_COUNT = 20

# Sessions: long enough to expose thermal drift, short enough to stay inside
# the 10-minute continuous-inference cap in PLAN.md §11.3.
SESSION_TARGET_S = 360.0  # 6 minutes
GAP_MIN_S, GAP_MAX_S = 0.5, 1.5

SOURCES = {
    "fleurs_en": {
        "parquet": WORK / "fleurs_en/parquet-data/en_us/test-00000-of-00001.parquet",
        "language": "en",
        "text_col": "raw_transcription",
        "id_col": "id",
        "tag": "fleurs-en",
    },
    "fleurs_es": {
        "parquet": WORK / "fleurs_es/parquet-data/es_419/test-00000-of-00001.parquet",
        "language": "es",
        "text_col": "raw_transcription",
        "id_col": "id",
        "tag": "fleurs-es",
    },
    "librispeech_other": {
        "parquet": WORK / "librispeech_other/all/test.other/0000.parquet",
        "language": "en",
        "text_col": "text",
        "id_col": "id",
        "tag": "ls-other",
    },
}


def ffmpeg(args: list[str]) -> None:
    proc = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg failed: {' '.join(args)}\n{proc.stderr}")


def wav_duration_s(path: Path) -> float:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SAMPLE_RATE, f"{path}: {w.getframerate()} Hz"
        assert w.getnchannels() == 1, f"{path}: {w.getnchannels()} channels"
        assert w.getsampwidth() == 2, f"{path}: {w.getsampwidth() * 8}-bit"
        return w.getnframes() / float(w.getframerate())


def decode_to_wav(raw: bytes, dest: Path) -> float:
    """Normalise one source clip to 16 kHz mono PCM16."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as tmp:
        tmp.write(raw)
        tmp_path = Path(tmp.name)
    try:
        ffmpeg(["-i", str(tmp_path), "-ar", str(SAMPLE_RATE), "-ac", "1",
                "-c:a", "pcm_s16le", str(dest)])
    finally:
        tmp_path.unlink(missing_ok=True)
    return wav_duration_s(dest)


def load_rows(key: str) -> list[dict]:
    spec = SOURCES[key]
    path = spec["parquet"]
    if not path.exists():
        raise SystemExit(f"missing {path}\nRun: python scripts/fetch_corpus.py")
    cols = ["audio", spec["text_col"], spec["id_col"]]
    table = pq.read_table(path, columns=cols)
    rows = []
    for rec in table.to_pylist():
        text = (rec[spec["text_col"]] or "").strip()
        if not text:
            continue
        rows.append({
            "source_id": str(rec[spec["id_col"]]),
            "text": text,
            "bytes": rec["audio"]["bytes"],
        })
    rows.sort(key=lambda r: r["source_id"])  # deterministic before sampling
    return rows


def make_gap(duration_s: float, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg(["-f", "lavfi", "-i", f"anullsrc=r={SAMPLE_RATE}:cl=mono",
            "-t", f"{duration_s:.3f}", "-c:a", "pcm_s16le", str(dest)])


def write_ref(clip_id: str, text: str) -> str:
    REFS.mkdir(parents=True, exist_ok=True)
    rel = f"refs/{clip_id}.txt"
    (ROOT / "corpus" / rel).write_text(text + "\n", encoding="utf-8")
    return rel


def build_shorts(key: str, rng: random.Random, clips: list[dict]) -> list[dict]:
    """Decode candidates until SHORT_COUNT land in the duration window."""
    spec = SOURCES[key]
    rows = load_rows(key)
    rng.shuffle(rows)
    out = []
    scratch = AUDIO / "_scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    print(f"[short] {key}: scanning {len(rows)} candidates for {SHORT_COUNT} "
          f"clips in {SHORT_MIN_S}-{SHORT_MAX_S}s")
    for row in rows:
        if len(out) >= SHORT_COUNT:
            break
        probe = scratch / f"probe_{key}_{row['source_id']}.wav"
        try:
            dur = decode_to_wav(row["bytes"], probe)
        except RuntimeError as e:
            print(f"  skip {row['source_id']}: {e}", file=sys.stderr)
            probe.unlink(missing_ok=True)
            continue
        if not (SHORT_MIN_S <= dur <= SHORT_MAX_S):
            probe.unlink(missing_ok=True)
            continue
        idx = len(out)
        clip_id = f"{spec['tag']}-short-{idx:03d}"
        dest = AUDIO / "short" / f"{clip_id}.wav"
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(probe), str(dest))
        entry = {
            "clip_id": clip_id,
            "audio": f"audio/short/{clip_id}.wav",
            "ref": write_ref(clip_id, row["text"]),
            "language": spec["language"],
            "bucket": "short",
            "duration_s": round(dur, 3),
            "source": key,
            "source_id": row["source_id"],
        }
        out.append(entry)
        clips.append(entry)
    shutil.rmtree(scratch, ignore_errors=True)
    total = sum(c["duration_s"] for c in out)
    print(f"[short] {key}: {len(out)} clips, {total:.1f}s total, "
          f"mean {total / max(len(out), 1):.1f}s")
    return out


def build_session(key: str, rng: random.Random, exclude: set[str],
                  clips: list[dict]) -> dict:
    """Concatenate clips with silence gaps into one multi-minute session."""
    spec = SOURCES[key]
    rows = [r for r in load_rows(key) if r["source_id"] not in exclude]
    rng.shuffle(rows)

    session_id = f"{spec['tag']}-session"
    workdir = AUDIO / "_session_work" / session_id
    if workdir.exists():
        shutil.rmtree(workdir)
    workdir.mkdir(parents=True, exist_ok=True)

    parts: list[Path] = []
    segments: list[dict] = []
    ref_chunks: list[str] = []
    cursor = 0.0
    n = 0

    print(f"[session] {key}: targeting {SESSION_TARGET_S:.0f}s")
    for row in rows:
        if cursor >= SESSION_TARGET_S:
            break
        piece = workdir / f"c{n:04d}.wav"
        try:
            dur = decode_to_wav(row["bytes"], piece)
        except RuntimeError as e:
            print(f"  skip {row['source_id']}: {e}", file=sys.stderr)
            piece.unlink(missing_ok=True)
            continue
        if dur < 1.0 or dur > 30.0:
            piece.unlink(missing_ok=True)
            continue

        if parts:  # gap BEFORE every clip except the first
            gap_s = round(rng.uniform(GAP_MIN_S, GAP_MAX_S), 3)
            gap = workdir / f"g{n:04d}.wav"
            make_gap(gap_s, gap)
            parts.append(gap)
            cursor += gap_s

        parts.append(piece)
        segments.append({
            "clip_id": f"{session_id}-seg-{n:03d}",
            "source_id": row["source_id"],
            "start_s": round(cursor, 3),
            "end_s": round(cursor + dur, 3),
            "text": row["text"],
        })
        ref_chunks.append(row["text"])
        cursor += dur
        n += 1

    listfile = workdir / "list.txt"
    listfile.write_text(
        "\n".join(f"file '{p.resolve().as_posix()}'" for p in parts) + "\n",
        encoding="utf-8",
    )
    dest = AUDIO / "session" / f"{session_id}.wav"
    dest.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg(["-f", "concat", "-safe", "0", "-i", str(listfile), "-c", "copy", str(dest)])
    actual = wav_duration_s(dest)

    entry = {
        "clip_id": session_id,
        "audio": f"audio/session/{session_id}.wav",
        "ref": write_ref(session_id, " ".join(ref_chunks)),
        "language": spec["language"],
        "bucket": "session",
        "duration_s": round(actual, 3),
        "source": key,
        "utterances": len(segments),
        "gap_range_s": [GAP_MIN_S, GAP_MAX_S],
        "segments": segments,
    }
    clips.append(entry)
    shutil.rmtree(AUDIO / "_session_work", ignore_errors=True)
    print(f"[session] {key}: {actual:.1f}s, {len(segments)} utterances "
          f"-> {dest.name}")
    return entry


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--clean", action="store_true", help="rebuild from scratch")
    args = ap.parse_args()

    if args.clean:
        shutil.rmtree(AUDIO, ignore_errors=True)
        shutil.rmtree(REFS, ignore_errors=True)

    AUDIO.mkdir(parents=True, exist_ok=True)
    REFS.mkdir(parents=True, exist_ok=True)

    clips: list[dict] = []

    # Short utterances: both languages, plus the noisy-English stress case.
    shorts_en = build_shorts("fleurs_en", random.Random(SEED + 1), clips)
    shorts_es = build_shorts("fleurs_es", random.Random(SEED + 2), clips)
    build_shorts("librispeech_other", random.Random(SEED + 3), clips)

    # Sessions: disjoint from the short set, so sustained-RTF audio is not
    # audio the latency measurement already warmed.
    build_session("fleurs_en", random.Random(SEED + 11),
                  {c["source_id"] for c in shorts_en}, clips)
    build_session("fleurs_es", random.Random(SEED + 12),
                  {c["source_id"] for c in shorts_es}, clips)

    manifest = {
        "schema": 1,
        "seed": SEED,
        "sample_rate": SAMPLE_RATE,
        "format": "mono PCM16 WAV",
        "buckets": {
            "short": {
                "target_s": [SHORT_MIN_S, SHORT_MAX_S],
                "count_per_source": SHORT_COUNT,
                "purpose": "latency per utterance",
            },
            "session": {
                "target_s": SESSION_TARGET_S,
                "gap_range_s": [GAP_MIN_S, GAP_MAX_S],
                "purpose": "sustained RTF and thermals",
            },
        },
        "clips": clips,
    }
    dest = ROOT / "corpus" / "manifest.json"
    dest.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")

    total = sum(c["duration_s"] for c in clips)
    print(f"\n[manifest] {len(clips)} entries, {total:.1f}s ({total / 60:.1f} min) "
          f"-> {dest}")
    by_lang: dict[str, float] = {}
    for c in clips:
        by_lang[c["language"]] = by_lang.get(c["language"], 0.0) + c["duration_s"]
    for lang, secs in sorted(by_lang.items()):
        print(f"  {lang}: {secs:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
