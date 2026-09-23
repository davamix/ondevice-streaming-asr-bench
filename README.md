# On-device streaming ASR on a mid-range Android phone

An experiment: **can one speech-to-text model serve both English and Spanish at
acceptable live latency on a 2021 mid-range phone — or do we ship a different
model per language?**

Everything runs on-device. Live, as the user speaks. No network.

This repo is the lab notebook, not the final report. It was made public before
any results existed, and the results table below grows as phases complete.
Negative results stay in.

**Status:** Phases 0–1 complete. Arm A measured on the SD870 in **both
languages**, clean and noisy. Arms B–E not started.

---

## Contents

| Section | What's in it |
|---|---|
| [Why this is not obvious](#why-this-is-not-obvious) | Why live ASR is a different problem from batch ASR, and the English/Spanish asymmetry the experiment exists to price |
| [Hardware under test](#hardware-under-test) | The phone, its SoC, and why no published number comes from an emulator |
| [The matrix](#the-matrix) | The five arms, with current status per arm |
| [**Results**](#results) | **The measured numbers.** Plus [the three things worth stopping on](#three-things-worth-stopping-on), [reproducibility](#reproducibility), why [`rtf_sustained` is blank](#rtf_sustained-is-blank-and-slip-stands-in-for-it), and the [decision gate](#decision-gate-planmd-10-phase-1) |
| [Metrics](#metrics) | What is measured and why RTF alone would mislead |
| [Method](#method-paced-file-fed-streaming) | Paced file-fed streaming — the one implementation detail everything rests on |
| [Corpus](#corpus) | How the audio was built, and the concatenation trick for scored continuous speech |
| [Reproducing](#reproducing) | Commands to rebuild the corpus, fetch models and run the harness |
| [**Findings and dead ends**](#findings-and-dead-ends) | **The useful part** — see the table below |
| [Model licences](#model-licences) | What each arm's weights permit, including one that blocks shipping |
| [Repo layout](#repo-layout) | Where everything lives |
| [A note on the test device](#a-note-on-the-test-device) | The safety policy, and why disabling thermal throttling is refused twice over |

### Findings index

Negative results included on purpose. Several of these are not documented
anywhere else.

| # | Finding | Kind |
|---|---|---|
| 1 | [Moonshine has no deployable Spanish streaming model](#moonshine-has-no-deployable-spanish-streaming-model) | Ecosystem gap |
| 2 | [The two Moonshine repos contradict each other on licensing](#the-two-moonshine-repos-contradict-each-other-on-licensing) | Licensing |
| 3 | [sherpa-onnx has no streaming Zipformer for Spanish](#sherpa-onnx-has-no-streaming-zipformer-for-spanish) | Ecosystem gap |
| 4 | [Whisper "small q5 = 180 MB" does not apply to a sherpa-onnx stack](#whisper-small-q5--180-mb-does-not-apply-to-a-sherpa-onnx-stack) | Correction to the plan |
| 5 | [Moonshine's algorithmic lookahead looks genuinely low](#moonshines-algorithmic-lookahead-looks-genuinely-low) | Observation, unconfirmed |
| 6 | [The platform recognizer had Spanish installed and English missing](#the-platform-recognizer-had-spanish-installed-and-english-missing) | Android gotcha |
| 7 | [Installing a language pack: the API works, the Settings UI does not](#installing-an-on-device-language-pack-the-api-works-the-settings-ui-does-not) | Android gotcha |
| 8 | [The recognizer sometimes ends a session with an error *and* correct text](#the-platform-recognizer-sometimes-ends-a-session-with-an-error-and-correct-text) | Android gotcha |
| 9 | [`adb push` into an app's own files dir can be invisible to that app](#adb-push-into-an-apps-own-external-files-dir-can-be-invisible-to-that-app) | Android gotcha |
| 10 | [A stalled consumer will hang a paced feeder, not fail it](#a-stalled-consumer-will-hang-a-paced-feeder-not-fail-it) | Harness bug |
| 11 | [Filtering out "bad" measurement rows can flatter what you measure](#filtering-out-bad-measurement-rows-can-flatter-the-thing-you-are-measuring) | Measurement integrity |
| 12 | [Excluded before testing](#excluded-before-testing) | Scope decisions |

> **New here?** [Results](#results) for the numbers, [Findings](#findings-and-dead-ends)
> for what was learned the hard way, and [`PLAN.md`](PLAN.md) for the full
> experiment design and the reasoning behind every decision.

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
| A | Android on-device recognizer | native | ✅ | ✅ | **0 MB** | platform | The bar to beat | ✅ **measured, both** |
| B | Moonshine streaming tiny/small/medium | native | ✅ | ❌ | 78 / 224 / 416 MB | `ai.moonshine:moonshine-voice` | EN frontrunner | ⬜ not started |
| C | Moonshine `base-es` (VAD-segmented) | no | ❌ | ✅ | 64.8 MB | same | ES cheap option ⚠️ non-commercial | ⬜ not started |
| D | Parakeet TDT 0.6b v3 int8 (VAD-segmented) | no | ✅ | ✅ | 670 MB | sherpa-onnx | One-model-for-both candidate | ⬜ not started |
| E | Whisper small + base int8 (chunked) | no | ✅ | ✅ | 375 / 161 MB | sherpa-onnx | Known baseline / calibration | ⬜ not started |

Arm A decides whether bundling a model is justified at all. If the platform
recognizer is good enough on this audio, that is a legitimate and
money-saving result — which is why it is built first.

## Results

Arm A (the platform on-device recognizer, 0 MB bundled), file-fed and
wall-clock-paced on the Snapdragon 870. Repetition 0 discarded. Medians over
all rows. Phone unplugged throughout, never above 30.7 °C.

| Lang | Source | Audio | WER | CER | `latency_final_ms` | `latency_first_partial_ms` | `partial_instability` | slip median | `peak_rss_mb` |
|---|---|---|---|---|---|---|---|---|---|
| es | FLEURS `es_419` | clean read | **7.73%** | 2.33% | **27** | 2010 | 6 | 4 ms | 103 |
| en | FLEURS `en_us` | clean read | **9.69%** | 4.36% | 123 | 1260 | 8 | 6 ms | 107 |
| en | LibriSpeech `test-other` | **noisy** | **33.45%** | 26.02% | 315 | 1276 | 7 | **196 ms** | 107 |

Spanish: 120 rows / 1539 reference words, pooled over two independent runs.
English: 60 rows each / 960 and 894 reference words.

### Three things worth stopping on

**1. Spanish is more accurate than English.** 7.73% vs 9.69% WER on the *same
corpus, same recording conditions, same recognizer* — FLEURS exists precisely
so this comparison is not confounded by domain. The language the plan expected
to be hardest to serve is the one the free arm handles best.

**2. Accuracy collapses on noisy audio.** 33.45% WER on LibriSpeech
`test-other` is 3.5× worse than clean English, and CER goes from 4.4% to 26%.
Clean read speech flatters this arm badly. Any judgement based only on FLEURS
would be wrong about real-world use, which is exactly why the noisy stress case
is in the corpus.

**3. The latency profiles are opposite, by language.**

| | Spanish | English |
|---|---|---|
| time to *first* text | 2010 ms (slow) | 1260 ms |
| time to *finalise* after speech ends | 27 ms (instant) | 123 ms |

Spanish takes two seconds to show anything and then commits instantly; English
shows text sooner but takes ~5× longer to settle. These are different models
with different buffering, not one recognizer with one behaviour — so "Android's
on-device recognizer has latency X" is not a meaningful statement without
naming the language.

### Reproducibility

Two independent 4-rep Spanish runs, hours apart:

| | run 1 | run 2 |
|---|---|---|
| WER | 7.71% | 7.75% |
| `latency_final_ms` | 27 | 27 |
| `latency_first_partial_ms` | 2010 | 2008 |

Tighter within a run: the *same clip* across repetitions lands within about
3 ms (2105 / 2109 / 2108 / 2109 ms), and one clip produced byte-identical text
on all four passes. That determinism is what file-fed measurement bought (D5);
a human repeating a sentence four times cannot produce it.

### `rtf_sustained` is blank, and slip stands in for it

Recognition runs inside Google's process, so time spent in our sink is a pipe
write and says nothing about the model. Reporting a number there would be a
fiction (D6).

Schedule slip fills the gap. It measures how far the paced feeder fell behind
the wall clock — i.e. how long the recognizer stopped draining audio — and it
tracks difficulty exactly as you would hope:

| | slip median | % over one frame |
|---|---|---|
| Spanish, clean | 4 ms | 2% |
| English, clean | 6 ms | 42% |
| English, **noisy** | **196 ms** | **67%** |

On noisy audio the recognizer stalls the input pipe for roughly 200 ms per
clip. For an arm whose internals are invisible, that is the "not keeping up
with real time" signal.

### Decision gate (PLAN.md §10, Phase 1)

**Arms C and D stay in the matrix.** Arm A is genuinely good on clean Spanish
and free, which is a real result — but 33% WER on noisy English is
disqualifying for anything used in an ordinary room, and that is the case a
bundled model would exist to fix.

Still open before the gate can close properly:

- **The first-partial numbers are an upper bound.** The harness builds a fresh
  `SpeechRecognizer` per clip, so every utterance pays full session startup.
  That models a voice-command app; continuous dictation would hold one open and
  amortise it. The session bucket settles this.
- **Nothing is known yet about sustained behaviour** — no 5–10 minute run, so
  no thermal drift data.
- **Arm A depends on a language pack the user may not have.** It had to be
  installed on this handset before English could be measured at all.

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

Then on a device. The emulator comes first, always — it is the blast shield for
a phone that cannot be replaced (§11.5):

```bash
# plumbing only; no recognizer needed, no numbers of record
.venv/Scripts/python scripts/run_bench.py --device emulator --plumbing

# the phone, where every published number comes from
.venv/Scripts/python scripts/devicelib.py --device physical   # status, read-only
.venv/Scripts/python scripts/run_bench.py --device physical --probe
.venv/Scripts/python scripts/run_bench.py --device physical --arms A --langs en,es --reps 4
```

`run_bench.py` refuses to start unless storage, battery level, temperature and
charging state are all in range, and it checks the device again inside the run
loop, aborting at 43 °C. The device is always selected explicitly — with an
emulator frequently attached at the same time, an implicit choice is how a
benchmark ends up pointed at the wrong machine.

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

### The platform recognizer had Spanish installed and English missing

PLAN.md §12 flagged this risk: *"Is Arm A's Spanish on-device model present on
this Xiaomi/MIUI build, or does it need a download?"* — the worry being that
Spanish would be the gap.

It is the other way round. `checkRecognitionSupport()` on the device under test
(MIUI V816, Android 13) reports:

| | |
|---|---|
| On-device recognition available | ✅ yes |
| **Installed** | **`es-ES` only** |
| Supported but not installed | 30 languages, including `en-US` |
| Pending | none |

So Arm A can do Spanish immediately and **cannot do English at all** until the
`en-US` pack is downloaded. That is consistent with an `alioth_eea` handset
used in Spanish: Speech Services installs the pack matching the device locale,
not the full set.

Two consequences worth generalising:

- **"Supported" is not "installed."** `en-US` appears in
  `supportedOnDeviceLanguages` while being completely unusable offline. Code
  that checks the supported list will pass and then fail at run time with
  `ERROR_LANGUAGE_UNAVAILABLE`. Only `installedOnDeviceLanguages` answers the
  question you actually care about.
- **Arm A's coverage is a property of the individual handset**, not of Android
  or of the device model. Any product relying on it needs a runtime check and a
  fallback, because a user's phone may simply not have the language.

The harness now checks installed packs in `Arm.load()` and skips unsupported
languages rather than burning thermal budget on a row of identical failures.

*Probed 2026-09-23.*

### Installing an on-device language pack: the API works, the Settings UI does not

The device under test shipped with only `es-ES`, and there was **no way to add
English through the UI**. The Settings path that documentation and forum advice
point at — Google → Voice → Offline speech recognition — offered a language
*selector* on this build, with no download control anywhere.

A natural wrong turn: Google Translate *does* offer downloadable English, and
it reports English as installed. That is a **different mechanism**. Translate's
offline packs serve translation, not `SpeechRecognizer`, and installing them
changes nothing for on-device ASR.

The supported answer is an API, not a settings screen:

```kotlin
SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    .triggerModelDownload(recognizerIntent)   // API 33
```

On this device that took **15 seconds**:

```
installed_before: ["es-ES"]
installed_after:  ["es-ES", "en-US"]
succeeded: true
```

Two caveats worth carrying into any product that relies on this:

- On **API 33 it is fire-and-forget.** The `ModelDownloadListener` overload
  that reports progress and failure only arrives in API 34, so on 33 the only
  way to know the outcome is to poll `checkRecognitionSupport` and watch the
  installed and pending lists. The harness does exactly that.
- It is a **request, not a command.** The download happens inside Google's
  process and may be deferred or declined. Code should handle "still not
  installed" as a normal outcome.

The practical upshot: an app that depends on on-device recognition cannot
assume a user's phone has their language, and cannot send them to Settings to
fix it. It has to call `triggerModelDownload()` itself and verify.

### The platform recognizer sometimes ends a session with an error *and* correct text

On about 3% of clips, Arm A finished with `ERROR_CLIENT` instead of delivering
a final result — while having already emitted a complete, correct transcript
through partial results.

It is not clip-dependent in the way you would expect. One clip produced
**byte-identical output on all four repetitions** but raised the error on only
two of them. Same audio, same text, different error outcome.

The practical consequence for anyone building on `SpeechRecognizer`: **treat
"error with partial text" as a usable result, not a failure.** Discarding the
transcript because `onError` fired would throw away perfectly good output
several times per hundred utterances.

A separate, avoidable version of this was our own bug: calling `stopListening()`
after closing the audio source races a session that has already finalised on
EOF, and comes back as `ERROR_CLIENT` on rows whose text is fine. Closing the
write end is sufficient; only stop a session still running.

### `adb push` into an app's own external files dir can be invisible to that app

`adb push` writes as the `shell` user. On Android 11+, a directory shell
creates inside `/sdcard/Android/data/<pkg>/files/` is not reliably readable by
the app that owns it: the push reports success, every byte is on the device,
and the app sees nothing. The failure then presents as a missing corpus rather
than a permissions problem, which sends you looking in the wrong place.

The fix is to have the app create its own directory tree first — here via an
instrumented `prepareDirs` test — and push into app-owned directories.

### A stalled consumer will hang a paced feeder, not fail it

A pipe buffer is about 64 KB; six seconds of 16 kHz PCM16 is 192 KB. If the
consumer stops reading, the feeder blocks on `write` and stays blocked
*forever*.

This is not hypothetical. On the emulator the platform recognizer aborted
immediately (`SodaSpeechRecognizer: Failed to get language pack of required
locale: error 13` → `LANGUAGE_UNAVAILABLE`), fired `onError`, and stopped
draining the pipe — and the run hung until an external 600 s timeout killed it.

The same thing would happen on the phone if a language pack were missing, which
is a live risk for Spanish. So the feed now runs on its own thread under a
watchdog, stops early when the consumer reports an error, and closes the read
end to break a blocked write. A stalled arm produces a recorded failure in
about 0.2 s instead of a hang — and a failure is a result.

This is precisely what the emulator-first rule is for.

### Filtering out "bad" measurement rows can flatter the thing you are measuring

The paced feeder records how far behind the wall clock it ever fell. The first
instinct — drop rows where slip exceeded a frame, because their latency is
contaminated — is wrong here, and the way it is wrong is worth generalising.

Slip is not harness noise. It is the recognizer pausing its intake, and it
pauses hardest on the audio it finds hardest. On LibriSpeech `test-other` two
thirds of rows exceeded the budget. Dropping them keeps the easy third:

| | all rows | slip ≤ 100 ms | effect |
|---|---|---|---|
| `latency_final_ms` (noisy en) | 315 | 114 | **2.8× better** |
| `latency_first_partial_ms` (noisy en) | 1276 | 1010 | 21% better |
| `latency_final_ms` (clean es) | 27 | 27 | none |

The filter would have made the arm look nearly three times faster on exactly
the audio it handles worst, while doing nothing on the audio it handles well —
the worst possible shape for a bias, because it is invisible unless you check.

Headline numbers therefore use every row, and slip is reported as its own
metric. For Arm A it doubles as the missing one: recognition happens inside
Google's process, so `rtf_sustained` is unmeasurable, and slip is the only
available signal for "not keeping up with real-time input".

The general rule: before excluding measurements as low-quality, check whether
the exclusion correlates with the thing being measured. If it does, the filter
is part of the result.

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
