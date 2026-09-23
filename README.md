# On-device streaming ASR on a mid-range Android phone

An experiment: **can one speech-to-text model serve both English and Spanish at
acceptable live latency on a 2021 mid-range phone — or do we ship a different
model per language?**

Everything runs on-device. Live, as the user speaks. No network.

This repo is the lab notebook, not the final report. It was made public before
any results existed, and the results table below grows as phases complete.
Negative results stay in.

**Status:** Phase 0 complete (corpus + scorer). Phase 1 not started.
No measurements yet — the results table is empty on purpose.

---

## Why this is not obvious

Live transcription and batch transcription are different problems, and the
models that win at one tend to lose at the other.

A batch model may report a real-time factor of 0.3 and still feel terrible
live, because it needs the *whole* utterance before it emits anything. Whisper
is the clearest case: it is an encoder-decoder that wants 30-second windows, so
pseudo-streaming it via VAD chunking inherits a latency floor equal to the
chunk size, re-processes overlapping audio, and visibly rewrites text the user
has already read.

So the metrics here are latency-shaped, not throughput-shaped — and one of them
(`partial_instability`, how often already-displayed text gets revised) does not
appear in any WER table but is what makes a transcriber feel broken.

The second non-obvious part is the language asymmetry. English has excellent
streaming-native open models. **Spanish does not.** Pricing that asymmetry is
the point of the experiment.

## Hardware under test

| Property | Value |
|---|---|
| Device | Xiaomi `M2012K11AG` (Poco F3 / Mi 11i, codename `alioth`) |
| SoC | Qualcomm `SM8250` — Snapdragon 870 |
| Cores | 1× Cortex-A77 @ 3.19 GHz · 3× A77 @ 2.42 GHz · 4× A55 @ 1.80 GHz |
| RAM | **6 GB total (~2 GB available)** ← the binding constraint |
| Android | 13 (API 33), `arm64-v8a` |

API 33 matters: `SpeechRecognizer.createOnDeviceSpeechRecognizer()` exists,
which gives a zero-megabyte control arm.

All published numbers come from this physical device. An emulator is used for
plumbing only — it executes x86_64 on a desktop CPU, with no ARM cluster, no
thermal envelope and no big.LITTLE scheduler, so its timings are unrelated to
the quantity of interest.

## The matrix

| # | Arm | Streaming | EN | ES | Size | Runtime | Role | Status |
|---|---|---|---|---|---|---|---|---|
| A | Android on-device recognizer | native | ✅ | ✅ | **0 MB** | platform | The bar to beat | ⬜ not started |
| B | Moonshine streaming tiny/small/medium | native | ✅ | ❌ | 78 / 224 / 416 MB | `ai.moonshine:moonshine-voice` | EN frontrunner | ⬜ not started |
| C | Moonshine `base-es` (VAD-segmented) | no | ❌ | ✅ | 64.8 MB | same | ES cheap option ⚠️ non-commercial | ⬜ not started |
| D | Parakeet TDT 0.6b v3 int8 (VAD-segmented) | no | ✅ | ✅ | 670 MB | sherpa-onnx | One-model-for-both candidate | ⬜ not started |
| E | Whisper small + base int8 (chunked) | no | ✅ | ✅ | 375 / 161 MB | sherpa-onnx | Known baseline / calibration | ⬜ not started |

Arm A decides whether bundling a model is justified at all. If the platform
recognizer is good enough on this audio, that is a legitimate and
money-saving result — which is why it is built first.

## Results

*Empty until Phase 1 produces numbers on the physical device. It grows here.*

| Arm | Lang | `latency_final_ms` | `partial_instability` | `rtf_sustained` | `peak_rss_mb` | `disk_size_mb` | WER |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — |

## Metrics

Real-time factor alone is a batch metric and would mislead here.

**Latency** — `latency_final_ms` (end of speech → final text; the user-facing
number), `latency_first_partial_ms`, and `partial_instability` (revisions to
already-displayed text).

**Sustained behaviour** — `rtf_sustained` measured over a 5–10 minute
continuous session, not per clip. If RTF drifts above 1.0 under thermal
throttling you fall behind the microphone *permanently* and latency grows
without bound; a per-clip RTF of 0.3 says nothing about whether that happens.
Plus battery temperature delta.

**Resource & quality** — `peak_rss_mb` (first-class: Arm D is expected to
strain it), `model_load_ms`, `disk_size_mb`, and WER/CER scored on the PC.

Fixed at 4 inference threads across all runs, after one deliberate 2/4/6/8
sweep to quantify big.LITTLE scheduling effects.

## Method: paced file-fed streaming

Measured runs are **file-fed, never microphone-fed**. You cannot say the same
sentence twice identically, so mic input would confound every comparison with
your own delivery.

WAV is pushed through each streaming API in **wall-clock-paced 100 ms chunks**,
so the model sees exactly what it would see from a live microphone — but
deterministically, and identically across every arm.

Ground truth for continuous audio comes from a concatenation trick: scored
clips from a reference dataset are joined with inserted silence gaps. That
yields realistic multi-minute continuous speech **with an exact reference
transcript**, which hand-recorded audio cannot give.

A single real-microphone sanity check runs at the very end. That is
validation, not measurement.

## Corpus

Built by `scripts/build_corpus.py` from pinned dataset revisions
(`corpus/SOURCES.md`). Audio is gitignored and rebuildable.

| Bucket | Content | Purpose |
|---|---|---|
| `short` | 20 clips × 3 sources, 3–8 s (mean 5.5–6.7 s) | latency per utterance |
| `session` | 1 per language, ~6 min, 0.5–1.5 s gaps | sustained RTF, thermals |

Sources are deliberately symmetric: FLEURS `en_us` and `es_419` are the same
corpus recorded the same way in both languages, so an English-vs-Spanish
comparison is not confounded by domain or recording conditions. LibriSpeech
`test-other` adds the noisy-English stress case that FLEURS's clean read
speech does not cover.

Current build: 62 clips, 18.4 minutes total (en 598.8 s, es 505.1 s), all
16 kHz mono PCM16. Session clips are disjoint from short clips, so
sustained-RTF audio is not audio the latency measurement already warmed.

## Reproducing

```bash
python -m venv .venv
.venv/Scripts/python -m pip install "huggingface_hub>=0.34" jiwer pyarrow soundfile numpy

.venv/Scripts/python scripts/fetch_corpus.py      # ~1.4 GB, pinned revisions
.venv/Scripts/python scripts/build_corpus.py      # -> corpus/audio + manifest.json
.venv/Scripts/python scripts/score.py --validate  # self-test the scorer

.venv/Scripts/python scripts/fetch_models.py --list
.venv/Scripts/python scripts/fetch_models.py --arm B
```

Scoring runs on the PC; the device emits hypothesis text only. That keeps the
harness small and means a scoring bug is fixable without re-running a single
measurement.

Normalisation before scoring is explicit and self-tested: lowercase, strip
punctuation, expand digits to words, collapse whitespace. **Spanish accents are
kept** — stripping them would flatter models that do not produce them, which is
exactly the difference being measured. Corpus WER is total edits over total
reference words, not the mean of per-utterance WERs.

## Findings and dead ends

Negative results are the useful part of this repo. They are recorded so nobody
re-derives them.

### Moonshine has no deployable Spanish streaming model

Moonshine v2 is streaming-native, MIT-licensed and has first-class Android
support — the obvious English frontrunner. For Spanish it is blocked, and the
reason is not documented anywhere else:

Spanish streaming weights **do** exist on HuggingFace
(`moonshine-streaming-tiny-es`, 108.6 MB; `moonshine-streaming-small-es`,
452.0 MB; both MIT). But the HF *model* repos ship `safetensors` — a training
format. The Android SDK consumes pre-quantized **`.ort`** files from
`moonshine-ai/moonshine-voice-assets`, and that assets repo contains **no
Spanish streaming variant at all**:

| Variant | Deployable `.ort`? | Size |
|---|---|---|
| `tiny-streaming-en` | ✅ | 77.7 MB |
| `small-streaming-en` | ✅ | 224.1 MB |
| `medium-streaming-en` | ✅ | 416.0 MB |
| `base-es` (legacy, non-streaming) | ✅ | 64.8 MB |
| **any Spanish streaming** | ❌ | **does not exist** |

Converting them ourselves means reproducing a 6-component export pipeline
(`frontend`, `encoder`, `adapter`, `cross_kv`, `decoder_kv`,
`decoder_kv_with_attention`). Out of scope for v1; logged as a stretch goal.

*Verified against the HF API on 2026-09-23.*

### The two Moonshine repos contradict each other on licensing

Resolved: all `moonshine-streaming-*` repos, every language, are **MIT**. The
legacy non-streaming `moonshine-es` / `base-es` is under the **Moonshine
Community License** — non-commercial, free under $1M annual revenue. Fine for
an experiment, a blocker for shipping.

### sherpa-onnx has no streaming Zipformer for Spanish

Its streaming transducers cover English, Chinese, Korean, Bengali, French and a
zh-en bilingual model. No Spanish. The model that would otherwise be the
natural live-ASR pick is unavailable for half the requirement.

### Whisper "small q5 = 180 MB" does not apply to a sherpa-onnx stack

180 MB / 57 MB are the **whisper.cpp GGML `q5_1`** sizes. sherpa-onnx consumes
ONNX, where the smallest published quantisation is int8: **375.4 MB** for small
and **160.6 MB** for base. Whisper small is therefore not the "small" option it
looks like — at 375 MB it sits between Moonshine small (224 MB) and medium
(416 MB). Details in `models/MODELS.md`.

### Moonshine's algorithmic lookahead looks genuinely low

`streaming_config.json` for `tiny-streaming-en` reports `frame_len: 80` (samples,
i.e. 5 ms at 16 kHz) and `total_lookahead: 16` frames — about **80 ms** of
algorithmic lookahead. If that holds empirically it is a structural advantage no
amount of optimisation gives a chunked offline model. To be confirmed by
measurement in Phase 2.

### Excluded before testing

- **Vosk** — Kaldi-era HMM/DNN, no punctuation or casing, accuracy collapses on
  noisy audio. Superseded by every other arm; it survives in blog posts through
  tutorial inertia, not merit.
- **Whisper `large-v3-turbo`** — ~1.6 GB of weights against ~2 GB available RAM.
  Will OOM or thrash.

## Model licences

Weights are never mirrored into this repo. They are fetched from HuggingFace at
pinned revisions (`models/MODELS.md`) under their own licences:

| Arm | Model | Licence | Ship-safe? |
|---|---|---|---|
| A | Android on-device recognizer | platform | ✅ |
| B | Moonshine streaming en | MIT | ✅ |
| C | Moonshine `base-es` | Moonshine Community (non-commercial) | ❌ **experiment only** |
| D | Parakeet TDT v3 | CC-BY-4.0 | ✅ with attribution |
| E | Whisper small/base | MIT | ✅ |
| — | Silero VAD | MIT | ✅ |

Corpus: FLEURS and LibriSpeech are both CC-BY-4.0. See `corpus/SOURCES.md` for
attribution.

## Repo layout

```
PLAN.md                  # the full experiment design and rationale
bench/                   # Gradle project — headless instrumented harness
models/MODELS.md         # repo + pinned revision per model (weights gitignored)
corpus/
  SOURCES.md             # pinned dataset revisions + attribution
  manifest.json          # clip -> reference -> language -> duration bucket
  refs/                  # ground-truth transcripts
  audio/                 # gitignored, rebuildable
results/                 # pulled JSON, one dir per device+date
scripts/                 # fetch_corpus / build_corpus / fetch_models / score
```

## A note on the test device

The phone under test is the owner's only phone — personal, daily-use and
irreplaceable. `PLAN.md` §11 is a hard safety policy: no root, no bootloader
unlock, no disabling thermal throttling, writes confined to two paths, battery
temperature gated at 35 °C start / 43 °C abort, never benchmarked while
charging.

Disabling thermal throttling is what benchmarking guides suggest constantly.
It is refused here on two grounds: it risks hardware that cannot be replaced,
and it is *methodologically wrong* — live transcription runs hot for minutes,
so throttling is part of the phenomenon being measured. Measuring the phone as
users actually experience it is the honest result.

`RECORD_AUDIO` is not declared or requested until Phase 5, because every
measured run is file-fed and needs no microphone access.

## Licence

Code: MIT. See `LICENSE`, and the model licence table above for weights.
