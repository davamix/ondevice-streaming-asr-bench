# Models — pinned revisions

Weights are **never** mirrored into this repo (PLAN.md D8). HuggingFace is the
canonical source; `scripts/fetch_models.py` fetches each model at the revision
pinned below into `models/` (gitignored).

Pinning matters: a silent upstream re-upload must not change what was measured.
Every published number in the README corresponds to these exact revisions.

Sizes verified **2026-09-23** against the HF API.

---

## Arm B — Moonshine streaming (English)

Repo: [`moonshine-ai/moonshine-voice-assets`](https://huggingface.co/moonshine-ai/moonshine-voice-assets)
Revision: `0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae`
Licence: **MIT**

Use the `quantized_26_08_21` set (newer than `quantized_26_07_30`, and smaller —
the newer set splits the frontend into `frontend.model.ort` +
`frontend.weights.ort`, which accounts for most of the reduction).

| Variant | Path prefix | Size |
|---|---|---|
| `tiny-streaming-en` | `model/tiny-streaming-en/quantized_26_08_21/` | **77.7 MB** |
| `small-streaming-en` | `model/small-streaming-en/quantized_26_08_21/` | **224.1 MB** |
| `medium-streaming-en` | `model/medium-streaming-en/quantized_26_08_21/` | **416.0 MB** |

Each variant is 6 ONNX-Runtime components plus a tokenizer and config:
`frontend.model.ort`, `frontend.weights.ort`, `encoder.ort`, `adapter.ort`,
`cross_kv.ort`, `decoder_kv.ort`, `decoder_kv_with_attention.ort`,
`streaming_config.json`, `tokenizer.bin`.

## Arm C — Moonshine `base-es` (Spanish, non-streaming)

Same repo and revision as Arm B.
Path: `model/base-es/quantized/base-es/` — **64.8 MB**
(`encoder_model.ort` 21.0 MB, `decoder_model_merged.ort` 43.6 MB, `tokenizer.bin` 0.2 MB)

> ⚠️ **Licence: Moonshine Community License — non-commercial**, free under $1M
> annual revenue. The two repos' READMEs contradict each other on this; the
> resolution is that all `moonshine-streaming-*` repos are MIT, while the legacy
> non-streaming `base-es` is not. Fine for this experiment, a blocker for
> shipping. Do not redistribute these weights.

## Arm D — Parakeet TDT 0.6b v3 int8

Repo: [`csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8)
Revision: `2bda32ec70b097a55adaa07d9a7173915b43cc78`
Licence: CC-BY-4.0 (NVIDIA NeMo upstream)

| File | Size |
|---|---|
| `encoder.int8.onnx` | 652.2 MB |
| `decoder.int8.onnx` | 11.8 MB |
| `joiner.int8.onnx` | 6.4 MB |
| `tokens.txt` | 0.1 MB |
| **total** | **670.5 MB** |

25 European languages including en + es. Offline transducer — needs VAD
segmentation. This is the arm most likely to fail on **memory**, not speed
(PLAN.md §4.3, open risk in §12).

## Arm E — Whisper (control / calibration)

Repos (both multilingual — *not* the `.en` variants, which cannot do Spanish):

| Model | Repo | Revision |
|---|---|---|
| small | [`csukuangfj/sherpa-onnx-whisper-small`](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small) | `8f3c18b358db4d1f2fc1eae49d75cd20989e4309` |
| base | [`csukuangfj/sherpa-onnx-whisper-base`](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base) | `bb53ee204431c90d314c1cc08d28d23e5b7927cc` |

Licence: MIT (OpenAI Whisper upstream).

| Variant | Files | Size |
|---|---|---|
| small int8 | `small-encoder.int8.onnx` 112.4 + `small-decoder.int8.onnx` 262.2 + tokens 0.8 | **375.4 MB** |
| base int8 | `base-encoder.int8.onnx` 29.1 + `base-decoder.int8.onnx` 130.7 + tokens 0.8 | **160.6 MB** |

> ### ⚠️ Correction to PLAN.md §5
>
> The plan's matrix lists Arm E as "Whisper small + base **q5** — **180 / 57 MB**"
> while also listing its runtime as sherpa-onnx. Those two facts are
> incompatible: 180/57 MB are the **whisper.cpp GGML `q5_1`** sizes, and
> sherpa-onnx does not consume GGML — it consumes ONNX, where the smallest
> published quantisation is int8.
>
> **Resolution: keep sherpa-onnx, correct the sizes to 375.4 / 160.6 MB.**
> Arm E is a control, not a contender (D3), so it does not justify integrating a
> third runtime just to hit a smaller number. The consequence is that Whisper
> small is no longer the "small" option it appeared to be in the matrix — at
> 375 MB it sits between Moonshine small (224 MB) and medium (416 MB), which
> makes the accuracy-per-megabyte comparison *more* interesting, not less.
>
> If the q5 sizes turn out to matter for a shipping decision, adding a
> whisper.cpp arm is a scoped follow-up, not a change to this experiment.

## Silero VAD (segmentation for Arms C, D, E)

Repo: [`onnx-community/silero-vad`](https://huggingface.co/onnx-community/silero-vad)
Revision: `e71cae966052b992a7eca6b17738916ce0eca4ec`
File: `onnx/model.onnx` — 2.24 MB · Licence: **MIT**

sherpa-onnx expects this file named `silero_vad.onnx`; the fetch script renames
it on download.

---

## Not used (recorded so the decision is not re-litigated)

| Model | Why not |
|---|---|
| `moonshine-ai/moonshine-streaming-tiny-es` (`215dc49e…`, 108.6 MB, MIT) | Spanish streaming weights exist as **`safetensors` only**. No `.ort` conversion is published, and the Android SDK consumes `.ort`. Exporting means reproducing a 6-component pipeline. Stretch goal — see PLAN.md §4.1. |
| `moonshine-ai/moonshine-streaming-small-es` (`8cb0974f…`, 452.0 MB, MIT) | Same. |
| Whisper `large-v3-turbo` | ~1.6 GB on a 6 GB / ~2 GB-available phone. Will OOM or thrash (D4). |
| Vosk | Kaldi-era HMM/DNN; no punctuation or casing; collapses on noisy audio (D2). |
| Streaming Zipformer | sherpa-onnx ships no Spanish streaming transducer (§4.2). |

## Licence summary

| Arm | Model | Licence | Ship-safe? |
|---|---|---|---|
| A | Android on-device recognizer | platform | ✅ |
| B | Moonshine streaming en | MIT | ✅ |
| C | Moonshine `base-es` | Moonshine Community (non-commercial) | ❌ **experiment only** |
| D | Parakeet TDT v3 | CC-BY-4.0 | ✅ with attribution |
| E | Whisper small/base | MIT | ✅ |
| — | Silero VAD | MIT | ✅ |
