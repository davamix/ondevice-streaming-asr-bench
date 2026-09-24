#!/usr/bin/env python3
"""Build the benchmark corpus from the fetched parquet sources.

Produces, under corpus/audio/ (gitignored, rebuildable):

  short/    ~5 s utterances -- latency per utterance, and the clip length that
            makes Whisper's 30 s padding penalty visible. Includes a
            level-matched copy of the FLEURS English clips (`fleurs-en-norm-*`),
            because the originals are recorded ~40 dB quieter than the rest.
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

import numpy as np
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

# Spanish gets 80 more short clips, added in Phase 3. Twenty clips could not
# tell arms apart: Arm A and Parakeet differed by 1.2 WER points on Spanish
# with a 95% interval of -3.2 to +4.9 (scripts/compare.py). The originals
# also hold only 15 distinct sentences, because FLEURS records each sentence
# by several speakers. The extra clips are one recording per sentence, none
# already in the Spanish shorts or session, and are drawn after everything
# else so every existing file rebuilds byte for byte.
#
# Their window is 3-10 s, not 3-8. FLEURS sentences mostly run longer than 8 s:
# of the 306 unused Spanish test sentences, only 29 have a recording under
# 8 s, and 103 under 10 s. Every arm hears the same clips, so comparisons
# between arms are unaffected; Spanish clips are simply a little longer.
ES_EXTRA_COUNT = 80
ES_EXTRA_MAX_S = 10.0

# Sessions: long enough to expose thermal drift, short enough to stay inside
# the 10-minute continuous-inference cap in PLAN.md §11.3.
SESSION_TARGET_S = 360.0  # 6 minutes
GAP_MIN_S, GAP_MAX_S = 0.5, 1.5

# FLEURS en_us is recorded ~40 dB quieter than es_419 and LibriSpeech (median
# -62.6 dBFS RMS against ~-23), which confounded every English-vs-Spanish and
# clean-vs-noisy comparison built on it. A level-matched copy of the English
# short clips sits alongside the originals, lifted by one static gain per clip
# to the level of the other sources. A static gain, not loudness normalisation
# or compression, so the copy is exactly the audio the arms already heard, only
# louder -- the one variable being isolated.
LEVEL_MATCH_DBFS = -23.0
PEAK_CEILING_DBFS = -1.0  # never clip: the gain yields to this if it must

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


def build_shorts(key: str, rng: random.Random, clips: list[dict],
                 count: int = SHORT_COUNT, first_index: int = 0,
                 exclude: frozenset[str] = frozenset(),
                 distinct: bool = False,
                 max_s: float = SHORT_MAX_S) -> list[dict]:
    """Decode candidates until `count` land in the duration window.

    The defaults are the original selection and must stay exactly as they
    are: changing the candidate order would change which clips are picked.
    `exclude` drops sentences already used; `distinct` keeps one recording
    per sentence (FLEURS ids are per sentence, not per recording).
    """
    spec = SOURCES[key]
    rows = [r for r in load_rows(key) if r["source_id"] not in exclude]
    rng.shuffle(rows)
    out = []
    taken: set[str] = set()
    scratch = AUDIO / "_scratch"
    scratch.mkdir(parents=True, exist_ok=True)
    print(f"[short] {key}: scanning {len(rows)} candidates for {count} "
          f"clips in {SHORT_MIN_S}-{max_s}s")
    for row in rows:
        if len(out) >= count:
            break
        if distinct and row["source_id"] in taken:
            continue
        probe = scratch / f"probe_{key}_{row['source_id']}.wav"
        try:
            dur = decode_to_wav(row["bytes"], probe)
        except RuntimeError as e:
            print(f"  skip {row['source_id']}: {e}", file=sys.stderr)
            probe.unlink(missing_ok=True)
            continue
        if not (SHORT_MIN_S <= dur <= max_s):
            probe.unlink(missing_ok=True)
            continue
        idx = first_index + len(out)
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
        taken.add(row["source_id"])
    shutil.rmtree(scratch, ignore_errors=True)
    if len(out) < count:
        raise SystemExit(f"[short] {key}: only {len(out)} of {count} clips found")
    total = sum(c["duration_s"] for c in out)
    print(f"[short] {key}: {len(out)} clips, {total:.1f}s total, "
          f"mean {total / max(len(out), 1):.1f}s")
    return out


def read_pcm16(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")


def write_pcm16(samples: np.ndarray, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(dest), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(samples.astype("<i2").tobytes())


def dbfs(x: np.ndarray) -> tuple[float, float]:
    """(RMS, peak) of float samples in [-1, 1], in dBFS."""
    rms = float(np.sqrt(np.mean(x ** 2)))
    peak = float(np.max(np.abs(x)))
    return 20 * np.log10(max(rms, 1e-12)), 20 * np.log10(max(peak, 1e-12))


def build_level_matched(shorts: list[dict], key: str, clips: list[dict]) -> list[dict]:
    """A copy of `shorts`, each lifted by a static gain to LEVEL_MATCH_DBFS.

    References are shared with the originals rather than copied: the words are
    identical by construction, and one file per sentence keeps it that way.
    """
    out = []
    for src in shorts:
        x = read_pcm16(ROOT / "corpus" / src["audio"]).astype(np.float64) / 32768.0
        rms_db, peak_db = dbfs(x)
        gain_db = min(LEVEL_MATCH_DBFS - rms_db, PEAK_CEILING_DBFS - peak_db)
        y = np.round(x * 10 ** (gain_db / 20) * 32768.0)
        y = np.clip(y, -32768, 32767)
        clip_id = src["clip_id"].replace("-short-", "-norm-short-")
        rel = f"audio/short/{clip_id}.wav"
        write_pcm16(y, ROOT / "corpus" / rel)
        out_rms, _ = dbfs(y / 32768.0)
        entry = {
            **{k: v for k, v in src.items() if k not in ("clip_id", "audio", "source")},
            "clip_id": clip_id,
            "audio": rel,
            "source": key,
            "derived_from": src["clip_id"],
            "gain_db": round(gain_db, 2),
            "rms_dbfs_before": round(rms_db, 1),
            "rms_dbfs_after": round(out_rms, 1),
        }
        out.append(entry)
        clips.append(entry)
    gains = sorted(e["gain_db"] for e in out)
    print(f"[level] {key}: {len(out)} clips, gain {gains[0]:+.1f} .. {gains[-1]:+.1f} dB "
          f"-> {LEVEL_MATCH_DBFS} dBFS RMS")
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

    # Derived from the English shorts above, not sampled: same sentences,
    # same speakers, only the level differs.
    build_level_matched(shorts_en, "fleurs_en_norm", clips)

    # Sessions: disjoint from the short set, so sustained-RTF audio is not
    # audio the latency measurement already warmed.
    build_session("fleurs_en", random.Random(SEED + 11),
                  {c["source_id"] for c in shorts_en}, clips)
    session_es = build_session("fleurs_es", random.Random(SEED + 12),
                               {c["source_id"] for c in shorts_es}, clips)

    # Last, so nothing above changes (see ES_EXTRA_COUNT).
    used_es = ({c["source_id"] for c in shorts_es}
               | {s["source_id"] for s in session_es["segments"]})
    build_shorts("fleurs_es", random.Random(SEED + 4), clips,
                 count=ES_EXTRA_COUNT, first_index=len(shorts_es),
                 exclude=frozenset(used_es), distinct=True,
                 max_s=ES_EXTRA_MAX_S)

    manifest = {
        "schema": 1,
        "seed": SEED,
        "sample_rate": SAMPLE_RATE,
        "format": "mono PCM16 WAV",
        "buckets": {
            "short": {
                "target_s": [SHORT_MIN_S, SHORT_MAX_S],
                "count_per_source": {
                    "fleurs_en": SHORT_COUNT,
                    "fleurs_en_norm": SHORT_COUNT,
                    "fleurs_es": SHORT_COUNT + ES_EXTRA_COUNT,
                    "librispeech_other": SHORT_COUNT,
                },
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
