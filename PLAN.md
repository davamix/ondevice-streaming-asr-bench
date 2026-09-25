# On-Device Streaming ASR — Experiment Plan

> **Read this first.** It captures every decision and finding from the design session so a
> fresh session can execute without re-deriving anything. Facts here were verified on
> 2026-09-22; re-check anything marked ⚠️ before relying on it.

---

## 1. Objective

Choose a speech-to-text stack for an Android app that transcribes **live, as the user speaks**,
in **English and Spanish**, running **entirely on-device**.

**Central question, stated precisely:**

> Can one model serve both English and Spanish at acceptable live latency on a mid-range
> phone — or do we ship a different model per language?

This framing matters because the research below showed the two languages have *asymmetric*
options. English has excellent streaming-native models; Spanish does not. The experiment
exists to price that asymmetry, not just to rank models.

**Size budget is deliberately open.** We want the accuracy-vs-megabytes curve to *tell* us
what the budget should be, so no model is excluded for size alone.

---

## 2. Fixed context (verified — do not re-derive)

### Target device

> ⚠️ **This is the owner's only phone, personal and irreplaceable. Read §11 before running
> anything against it.** Serial deliberately not recorded here — this file is public.

| Property | Value |
|---|---|
| Model | Xiaomi `M2012K11AG` (Poco F3 / Mi 11i, codename `alioth`) |
| SoC | Qualcomm `SM8250` — **Snapdragon 870** |
| Cores | 1× Cortex-A77 @ 3.19 GHz · 3× A77 @ 2.42 GHz · 4× A55 @ 1.80 GHz |
| RAM | **6 GB total (~2 GB available)** ← the binding constraint |
| Storage free | ~75 GB |
| Android | 13 (API 33) |
| ABI | `arm64-v8a` |

API 33 matters: `SpeechRecognizer.createOnDeviceSpeechRecognizer()` is available, which gives
us a zero-megabyte control arm.

### Toolchain
| Tool | Status |
|---|---|
| adb | `D:\Android\Sdk\platform-tools\adb.exe` (v37.0.1) — **not on PATH**, use full path |
| emulator | `D:\Android\Sdk\emulator\emulator.exe`; AVDs: `Medium_Phone_API_36.0`, `IAmOk_Watcher_36` |
| NDK | 28.2.13676358 |
| JDK | OpenJDK 25.0.2 |
| `hf` CLI | v1.32.0 |
| ffmpeg | installed — used for all corpus normalisation |
| `gh` CLI | installed, with the **LFS extension** |
| ANDROID_HOME | `D:\Android\Sdk` |

---

## 3. Decisions already made (with rationale)

These are settled. Revisit only if a listed assumption breaks.

**D1 — The emulator is for plumbing, never for numbers.**
It executes x86_64 on the desktop CPU: no ARM cluster, no thermal envelope, no big.LITTLE
scheduler. Timings from it are unrelated to the quantity we care about. Use it for
"does the manifest parse, does the model load, does JSON come back". **Every published
number comes from the SD870.**

**D2 — Vosk is excluded.** Kaldi-era HMM/DNN architecture, no punctuation or casing,
accuracy collapses on noisy audio. Superseded by every other arm. (It survives in blog posts
through tutorial inertia, not merit.)

**D3 — Whisper is a control, not a contender.** It is an offline encoder-decoder needing 30 s
windows. Pseudo-streaming via VAD chunking inherits a latency floor equal to chunk size,
re-processes overlapping audio, and rewrites partial hypotheses. We keep it *because* it is
the known quantity that calibrates everything else.

**D4 — Whisper `large-v3-turbo` is excluded.** ~1.6 GB of weights on a 6 GB phone with ~2 GB
available. Will OOM or thrash.

**D5 — Measured runs are file-fed, never microphone-fed.** You cannot say the same sentence
twice identically, so mic input confounds every comparison with your own delivery. See §6.

**D6 — Runtime cannot be held perfectly constant; accept it and document it.**
Moonshine has its own SDK, Android's recognizer is a black box, sherpa-onnx covers the rest.
We are comparing **deployable stacks** (model + runtime), which is the decision we actually
face. Record the runtime per arm and do not attribute differences to the model alone.

**D7 — Server-side hosting is out of scope for now.** Revisit after on-device results.
(Prior finding for reference: the rented Hetzner CX33 — 4 shared vCPU @ 2.0 GHz, 8 GB — is
*not* meaningfully faster than this phone, so it buys centralisation and battery relief,
not speed.)

**D8 — The work is published in a public GitHub repo, but weights are never mirrored there.**
Models stay on HuggingFace (the canonical source) and are fetched by script against a pinned
revision. Mirroring them would bloat the repo, duplicate a source that already exists, and
drag four different model licences into ours — including `base-es`, which is non-commercial
and should not be redistributed at all. Git LFS is held in reserve for a *small curated audio
sample* (FLEURS and LibriSpeech are CC-BY-4.0, so redistributable with attribution) if
reproducibility needs it. Results JSON is small and needs no LFS.

**D9 — The repo is public from day one, so nothing personal enters it.** No device serial, no
self-recorded audio (that is the owner's own voice), no account identifiers, and `getprop`
dumps get scrubbed before committing. See §11.6.

---

## 4. Research findings that shaped the matrix

### 4.1 Moonshine v2 — strong for English, blocked for Spanish ⚠️

> **Corrected 2026-09-24 (Phase 4).** The blocker below was wrong. Spanish
> streaming `.ort` files exist (`small-streaming-es` 121.8 MB,
> `tiny-streaming-es` 32.3 MB, MIT). They are on the vendor's CDN,
> `download.moonshine.ai`, which is where the SDK's own catalog downloads
> from, and not in the HF assets repo checked here. `small-streaming-es` was
> added to Arm B in Phase 4. See README finding 1.

Moonshine v2 (open-weights release Feb 2026) is streaming-native, MIT-licensed, ONNX
Runtime-based, with first-class Android support. For English it is the frontrunner.

**The blocker:** HF *model* repos ship `safetensors` (training format). The Android SDK
consumes pre-quantized **`.ort`** files from `moonshine-ai/moonshine-voice-assets`. That
assets repo contains:

| Variant | Deployable? | Size (`quantized_26_08_21`) |
|---|---|---|
| `tiny-streaming-en` | ✅ | **77.7 MB** |
| `small-streaming-en` | ✅ | **224.1 MB** |
| `medium-streaming-en` | ✅ | **416.0 MB** |
| `base-es` (legacy, non-streaming) | ✅ | **64.8 MB** |
| **Spanish streaming** | ❌ | **does not exist as `.ort`** |

Spanish streaming weights *do* exist on HF (`moonshine-ai/moonshine-streaming-tiny-es`
108 MB, `moonshine-streaming-small-es` 452 MB, both MIT) but have **not** been converted to
deployable `.ort`. Exporting them ourselves means reproducing a 6-component pipeline
(`frontend`, `encoder`, `adapter`, `cross_kv`, `decoder_kv`, `decoder_kv_with_attention`)
— a real project with uncertain payoff. **Out of scope for v1; logged as a stretch goal.**

**Licensing (resolved — the two repos' READMEs contradict each other):**
- All `moonshine-streaming-*` repos, every language: **MIT** ✅
- `moonshine-es` (legacy non-streaming): **Moonshine Community License** — non-commercial,
  free under $1M annual revenue. ⚠️ Fine for an experiment; a constraint if this ships.

Android integration: Maven `ai.moonshine:moonshine-voice`. Reference implementation at
`examples/android/Transcriber/` in the `moonshine-v2` repo. API shape:
`Transcriber.start() / stop() / add_audio(chunk, sample_rate)` with callbacks
`on_line_started()`, `on_line_text_changed()`, `on_line_completed()`.

> `on_line_text_changed()` is exactly the hook needed for the partial-instability metric (§5).

### 4.2 No streaming Zipformer for Spanish

sherpa-onnx's streaming transducers cover English, Chinese, Korean, Bengali, French and a
zh-en bilingual model. **No Spanish.** The model that would otherwise be the natural live-ASR
pick is unavailable for half our requirement.

### 4.3 Parakeet is fast but offline, and a RAM risk

`sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` covers 25 European languages (en + es ✅),
~640 MB, RTF 0.088 on 4× Cortex-A76. Fast enough to run on short VAD-delimited segments and
feel near-live. But it is an **offline** transducer, and ~1.5–2 GB resident on a 6 GB phone
is the arm most likely to fail on **memory**, not speed.

---

## 5. The matrix

Five arms. Each earns its place; resist adding more.

| # | Arm | Streaming | EN | ES | Size | Runtime | Role |
|---|---|---|---|---|---|---|---|
| A | Android on-device recognizer | native | ✅ | ✅ | **0 MB** | platform | **The bar to beat** |
| B | Moonshine streaming tiny/small/medium | native | ✅ | ❌ | 78 / 224 / 416 MB | `ai.moonshine:moonshine-voice` | EN frontrunner |
| C | Moonshine `base-es` (VAD-segmented) | no | ❌ | ✅ | 64.8 MB | same | ES cheap option ⚠️ non-commercial |
| D | Parakeet TDT 0.6b v3 int8 (VAD-segmented) | no | ✅ | ✅ | ~640 MB | sherpa-onnx | One-model-for-both candidate |
| E | Whisper small + base q5 (chunked) | no | ✅ | ✅ | 180 / 57 MB | sherpa-onnx | Known baseline / calibration |

Arm A decides whether bundling a model is justified at all. If Google's recognizer is good
enough on our audio, that is a legitimate and money-saving result — which is why it is built
first (§7).

---

## 6. Metrics — what live actually requires

RTF alone is a batch metric and will mislead here. Capture per (arm, clip, rep):

**Latency (the product-deciding numbers)**
- `latency_final_ms` — end-of-speech → final text. **The user-facing number.**
- `latency_first_partial_ms` — speech start → first text on screen.
- `partial_instability` — count of revisions to already-displayed text
  (from `on_line_text_changed` or equivalent). Chunked Whisper will flicker badly; this is
  what will make it *feel* bad in a way a WER table never shows.

**Sustained behaviour (easy to miss, kills products)**
- `rtf_sustained` measured over a **5–10 minute continuous session**, not per clip.
  If RTF drifts above 1.0 under thermal throttling you fall behind the mic *permanently* and
  latency grows without bound. A per-clip RTF of 0.3 says nothing about whether that happens.
- `battery_temp_c` before/after via `dumpsys battery`; `battery_pct_delta`.

**Resource & quality**
- `peak_rss_mb` — first-class, not an afterthought. Arm D is expected to strain this.
- `model_load_ms` (cold start), `disk_size_mb`
- `wer` / `cer` — scored **on the PC**, not the device. Device emits hypothesis text only.

**Fixed across all runs:** 4 inference threads. Do one deliberate 2/4/6/8 sweep on a single
model to quantify big.LITTLE scheduling effects (the kernel may park threads on the 1.8 GHz
A55 cores), then hold at 4.

### Measurement protocol
- Discard rep 1 (cold start / page cache), then **N ≥ 3**.
- Fixed cooldown between runs; log start temp and abort if above threshold.
- Screen on, airplane mode, **not charging** (charging adds heat).
- Randomise arm order across repetitions so run order cannot masquerade as an effect.

---

## 7. Methodology: paced file-fed streaming

The single most important implementation detail.

Feed WAV through each streaming API in **wall-clock-paced chunks** (100 ms frames released in
real time) so the model sees exactly what it would see from a live microphone — but
deterministically and identically across every arm. This yields real streaming-latency
measurements *and* repeatability.

Ground truth for continuous audio comes from a concatenation trick: take scored clips from a
reference dataset and join them with inserted silence gaps. The result is realistic
multi-minute continuous speech **with exact reference transcripts** — something hand-recorded
audio cannot give.

Run a **single real-microphone sanity check at the very end** to confirm the simulation
matches reality. That is validation, not measurement.

> **Corrected 2026-09-25 (Phase 5).** The check measured the one thing the simulation
> was known to get wrong. The feeder releases each 100 ms frame at the *start* of its slot;
> a microphone delivers it at the *end*. Live text therefore arrives ~0.2 s later than file-fed on this
> phone (0.1 s this head start, 0.1 s the capture path), identically for every arm. Text
> and compute matched exactly. Published numbers are left as measured; a new harness
> should release frames at slot end. See README finding 33.

---

## 8. Corpus specification

**Sources** (symmetry is deliberate — same domain and recording conditions across languages
makes EN vs ES directly comparable):
- `google/fleurs`, config `en_us` → English
- `google/fleurs`, config `es_419` → Spanish
- LibriSpeech `test-other` → noisy-English stress case
- 3–5 self-recorded clips → **qualitative realism check only, not scored**

**Normalisation — every file, no exceptions:** 16 kHz mono PCM16 WAV.

```bash
# normalise
ffmpeg -i in.ext -ar 16000 -ac 1 -c:a pcm_s16le out.wav

# silence gap generator
ffmpeg -f lavfi -i anullsrc=r=16000:cl=mono -t 0.8 -c:a pcm_s16le gap.wav

# concatenate into a session (concat demuxer, list of file '...' lines)
ffmpeg -f concat -safe 0 -i list.txt -c copy session.wav
```

**Structure**
- *Short utterances*: ~5 s, for latency-per-utterance and Whisper's 30 s-padding penalty.
- *Sessions*: 5–10 min concatenated with 0.5–1.5 s gaps, for sustained RTF and thermals.
- Both, in both languages. Keep a `manifest.json` mapping clip → reference → language →
  duration bucket.

---

## 9. Repository layout

```
android-transcription-sample/
├── PLAN.md                 # this file
├── bench/                  # Gradle project — headless instrumented harness
│   └── src/androidTest/    # connectedAndroidTest entry point
├── models/                 # hf downloads — GITIGNORED
│   └── MODELS.md           # repo + pinned revision hash per model
├── corpus/
│   ├── audio/              # 16 kHz mono PCM16, duration-bucketed
│   ├── refs/               # ground-truth transcripts
│   └── manifest.json
├── results/                # pulled JSON, one dir per device+date
└── scripts/                # fetch-models / build-corpus / push / run / score
```

**Harness shape:** headless, not a demo UI. An instrumented test (`connectedAndroidTest`) or
an Activity launched by `adb shell am start` with extras. Reads a manifest of
(arm, clip, threads) rows; writes results as JSON to the app's external files dir; `adb pull`
retrieves them. One command, reproducible, scriptable. A demo UI comes *after* there are
numbers.

**Model delivery:** `adb push` to the app's external files dir
(`/sdcard/Android/data/<pkg>/files/models/`) — needs no runtime permission on API 33.
Do **not** bundle models in the APK; you would rebuild for every change.

**Provenance:** pin the HF revision hash of every model in `MODELS.md`. A silent re-download
must not change what was measured. Keep `models/` out of git.

---

## 10. Execution phases

Ordered so failures are cheap and early.

### Phase 0 — Repo, corpus and scaffold (PC only, no Android)
1. `git init`; create the directory layout in §9; add `.gitignore` covering `models/`,
   `corpus/audio/`, and `corpus/self-recorded/`.
2. **Create the public GitHub repo** via `gh repo create --public`, with `LICENSE` (MIT for
   our code) and the `README.md` specified in §10.1. Push before any results exist — the repo
   is the lab notebook, not the final report.
3. `hf download` the FLEURS `en_us` / `es_419` slices and LibriSpeech `test-other`, pinning
   revisions into `models/MODELS.md` and `corpus/SOURCES.md`.
4. Normalise everything to 16 kHz mono PCM16 with ffmpeg.
5. Build short-utterance and 5–10 min session files; write `manifest.json` + `refs/`.
6. Write `scripts/score.py` (WER/CER via `jiwer`) and validate it against a known pair.

*Exit criterion:* a scored corpus exists, the scorer is trusted, and the public repo is live.
No Android code yet.

#### 10.1 README contents
The README is a living document, updated at the end of every phase — not written once.

- What the experiment asks (§1) and why on-device streaming ASR is non-obvious
- The hardware under test (SoC, cores, RAM — **no serial**)
- The matrix (§5) with current status per arm
- **A results table that grows as phases complete** — the main artifact
- Reproduction steps: fetch models, build corpus, run harness, score
- Model licence table (MIT / CC-BY-4.0 / non-commercial), stated plainly, since `base-es`
  restricts what readers may do with that arm
- Findings and dead ends, including negative results — the Moonshine Spanish `.ort` gap (§4.1)
  is genuinely useful to anyone else evaluating this and is not documented elsewhere

### Phase 1 — Harness plumbing + Arm A
6. Gradle project in `bench/`, minSdk 33, `arm64-v8a`.
7. Implement the paced file-feeder (§7) and the JSON result writer.
8. Implement **Arm A** (`createOnDeviceSpeechRecognizer`) — cheapest arm, zero model download,
   and it exercises the whole streaming + metrics path end to end.
9. Validate the loop on the **emulator** (correctness only), then take the first real numbers
   on the phone.

*Exit criterion:* `adb pull` returns scored results for Arm A on the SD870.
**Decision gate:** if Arm A's WER is already acceptable for the target audio, the size
question is answered and arms C/D may be droppable. Record this verdict explicitly.

### Phase 2 — Moonshine English (Arm B)
10. Add `ai.moonshine:moonshine-voice`; mirror `examples/android/Transcriber/`.
11. Pull `.ort` assets for `tiny/small/medium-streaming-en` (use the newest
    `quantized_26_08_21` set).
12. Wire `on_line_text_changed` to the `partial_instability` counter.
13. Full English matrix across the three sizes.

*Exit criterion:* the English accuracy-vs-size curve exists — the answer to the open budget
question, for English.

### Phase 3 — Both languages via sherpa-onnx (Arms D, E)
14. Integrate sherpa-onnx (Android AAR) + Silero VAD segmentation.
15. Arm E (Whisper small/base q5) — the calibration baseline, both languages.
16. Arm D (Parakeet TDT v3 int8) — **watch `peak_rss_mb` closely**; expect this to be where
    the 6 GB ceiling bites. Record the failure mode honestly if it OOMs; that is a result.

### Phase 4 — Resolve Spanish
17. Arm C (Moonshine `base-es`, VAD-segmented) for a cheap Spanish datapoint.
18. Compare Spanish across A / C / D / E and answer the §1 central question:
    one model for both, or per-language?

### Phase 5 — Analysis and publication
19. Sustained 5–10 min thermal runs for the surviving arms only.
20. Single real-microphone sanity check (§7) — the only step needing `RECORD_AUDIO`.
21. Write up: latency / WER / size / RSS tradeoff curves, and a recommendation.
22. Final README update: complete results table, the §1 verdict, and the dead ends. Tag a
    release so the published numbers correspond to a fixed commit.

---

## 11. Device safety policy (personal phone)

The test device is the owner's **only** phone, used daily. There is no spare and no
replacement. Treat every operation against it as production. When in doubt, don't — ask.

### 11.1 Hard prohibitions
Never run these, and never propose them without explicit, specific confirmation:

- **`adb root`, bootloader unlock, `adb disable-verity`, fastboot, recovery, factory reset.**
  Nothing in this experiment needs elevated privileges.
- **Disabling thermal throttling or forcing the CPU governor to `performance`.** Benchmarking
  guides suggest this constantly. It requires root and risks real hardware damage on a device
  we cannot replace. It is also *methodologically wrong here*: live transcription runs hot for
  minutes, so throttling is part of the phenomenon we are measuring. Measuring the phone as
  users actually experience it is the honest result (§6, `rtf_sustained`).
- **`pm uninstall` / `pm clear` / `pm disable`** on any package other than the harness's own
  applicationId.
- **`rm` outside the two write-scope paths in §11.2.** No recursive deletes with wildcards.
- **Enabling MIUI's "USB debugging (Security settings)".** It grants simulated input and
  silent install rights we have no use for.
- **Persistent `settings put` changes** that outlive the session, unless noted and reverted in
  the same run.

### 11.2 Write scope
Everything the harness touches lives in exactly two places:

- `/sdcard/Android/data/<applicationId>/files/` — models, corpus, results. Removed
  automatically when the app is uninstalled, which is the clean exit.
- `/data/local/tmp/bench/` — staging only, cleaned explicitly.

Nothing else on the device is read or written. The harness never touches user storage.

### 11.3 Thermal and battery care
This phone is a 2021 device; its battery has already aged, and repeated thermal cycling is
what degrades it further.

- **Temperature gate:** start a run only below **35 °C** battery temp; **abort at 43 °C**;
  cool back below 35 °C before the next run. Read via `dumpsys battery`.
- **Never run while charging.** Charging heat plus inference heat compounds.
- Keep charge between **30–80 %** during sessions.
- Cap continuous inference at **10 minutes** per session, with a hard daily ceiling.
- Prefer fewer repetitions with clean thermals over more repetitions with throttled,
  unusable data. If the thermal budget and the statistics conflict, the phone wins.

### 11.4 Permissions — least privilege
`RECORD_AUDIO` is **not declared or requested until Phase 5**. Every measured run is file-fed
(§7) and needs no microphone access whatsoever. This is both good hygiene and a natural
consequence of D5.

### 11.5 Emulator-first rule
Any new adb command, script, teardown path or cleanup routine is exercised on
`Medium_Phone_API_36.0` **before** it ever touches the phone. The emulator is disposable; the
phone is not. This extends D1: the emulator is not just for plumbing, it is the blast shield.

### 11.6 Pre-flight and post-run checklist
**Before:** free storage ≥ 5 GB · battery 30–80 % · temp < 35 °C · not charging · correct
device selected if more than one is attached (always pass `-s <serial>` explicitly when
an emulator is also running).

**After:** pull results · remove pushed models if storage is tight · confirm the app still
uninstalls cleanly · confirm no stray files outside §11.2.

### 11.7 Privacy (the repo is public)
- Device serial is never committed. Scrub `getprop` output before it enters the repo.
- Self-recorded clips are the owner's own voice — kept local, gitignored, never pushed.
- No account identifiers, no MIUI/Xiaomi account data, no logs containing either.

---

## 12. Open questions and risks

| Item | Impact | When to resolve |
|---|---|---|
| ⚠️ Does `ai.moonshine:moonshine-voice` let us point at arbitrary `.ort` asset dirs, or does it self-download? | Changes how models get on-device | Phase 2, step 10 |
| ⚠️ Parakeet peak RSS on a 6 GB / ~2 GB-available device | May eliminate Arm D entirely | Phase 3, step 16 |
| ⚠️ Is Arm A's Spanish on-device model present on this Xiaomi/MIUI build, or does it need a download? | Arm A may be unavailable for ES | Phase 1, step 8 |
| Thread scheduling onto A55 little cores | Could distort all timings | Phase 1, thread sweep |
| Moonshine ES streaming `.ort` export | Would make Moonshine a both-languages winner | Stretch goal, post-v1 |
| `base-es` non-commercial licence | Blocks shipping, not experimenting | Before any product decision |

---

## 13. What "done" looks like

A table in `results/` giving, per arm and per language: `latency_final_ms`,
`partial_instability`, `rtf_sustained`, `peak_rss_mb`, `disk_size_mb`, `wer` — plus a
one-paragraph recommendation answering §1, and an explicit note on whether Arm A made
bundling a model unnecessary.

Published: that table and verdict live in the public README at a tagged commit, with the
negative results included. The phone comes out of it in the same condition it went in.
