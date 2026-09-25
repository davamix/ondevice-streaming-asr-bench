# On-device streaming ASR on a mid-range Android phone

An experiment: **can one speech-to-text model serve both English and Spanish at
acceptable live latency on a 2021 mid-range phone — or do we ship a different
model per language?**

Everything runs on-device. Live, as the user speaks. No network.

This repo is the lab notebook, not the final report. It was made public before
any results existed, and the results table below grows as phases complete.
Negative results stay in.

**Status:** Phases 0–3 complete; Phase 4 under way. Arm D (Parakeet) is the
first model to serve **both** languages well: 4.47% WER in Spanish against
the platform recognizer's 7.39%, on 100 clips. On English, re-measured on 100
clips per source, it is level with Moonshine medium on clean speech (4.97%
vs 4.71%) and with Moonshine small on noisy (9.34% vs 8.92%); Moonshine
medium is the most accurate English model measured
([English on 100 clips](#english-on-100-clips-per-source)). Phase 4 also
found that Moonshine's Spanish streaming model exists after all
([finding](#moonshines-spanish-streaming-models-are-on-its-cdn-not-on-huggingface)).
Arm C and Moonshine Spanish are next. Revised figures are marked where they
occur.

---

## Contents

| Section | What's in it |
|---|---|
| [Why this is not obvious](#why-this-is-not-obvious) | Why live ASR is a different problem from batch ASR, and the English/Spanish asymmetry the experiment exists to price |
| [Hardware under test](#hardware-under-test) | The phone, its SoC, and why no published number comes from an emulator |
| [The matrix](#the-matrix) | The five arms, with current status per arm |
| [**Results**](#results) | **The measured numbers.** Plus [the three things worth stopping on](#three-things-worth-stopping-on), [reproducibility](#reproducibility), why [`rtf_sustained` is blank for Arm A](#rtf_sustained-is-blank-for-arm-a-and-slip-does-not-stand-in-for-it), the [decision gate](#decision-gate-planmd-10-phase-1), [Arm B: Moonshine](#arm-b-moonshine-streaming-english), [Arms D and E: Parakeet and Whisper](#arms-d-and-e-offline-models-made-live-both-languages), and [English on 100 clips per source](#english-on-100-clips-per-source) |
| [Metrics](#metrics) | What is measured and why RTF alone would mislead |
| [Method](#method-paced-file-fed-streaming) | Paced file-fed streaming — the one implementation detail everything rests on |
| [Corpus](#corpus) | How the audio was built, and the concatenation trick for scored continuous speech |
| [Reproducing](#reproducing) | Commands to rebuild the corpus, fetch models and run the harness |
| [**Findings and dead ends**](#findings-and-dead-ends) | **The useful part** — see the table below |
| [Model licences](#model-licences) | What each arm's weights permit, including one that blocks shipping |
| [Repo layout](#repo-layout) | Where everything lives |
| [A note on the test device](#a-note-on-the-test-device) | The safety policy, and why disabling thermal throttling is refused twice over |
| [summaries/](summaries/) | One short write-up per phase — [Phase 1: Arm A](summaries/phase-1-arm-a.md), [Phase 2: Arm B](summaries/phase-2-arm-b.md) and [Phase 3: Arms D and E](summaries/phase-3-arms-d-e.md), with later revisions marked |
| [HANDOVER.md](HANDOVER.md) | State, commands and constraints for running the next phase in a fresh session |

### Findings index

Negative results included on purpose. Several of these are not documented
anywhere else.

| # | Finding | Kind |
|---|---|---|
| 1 | [Moonshine's Spanish streaming models are on its CDN, not on HuggingFace](#moonshines-spanish-streaming-models-are-on-its-cdn-not-on-huggingface) | Correction (was: ecosystem gap) |
| 2 | [The two Moonshine repos contradict each other on licensing](#the-two-moonshine-repos-contradict-each-other-on-licensing) | Licensing |
| 3 | [sherpa-onnx has no streaming Zipformer for Spanish](#sherpa-onnx-has-no-streaming-zipformer-for-spanish) | Ecosystem gap |
| 4 | [Whisper "small q5 = 180 MB" does not apply to a sherpa-onnx stack](#whisper-small-q5--180-mb-does-not-apply-to-a-sherpa-onnx-stack) | Correction to the plan |
| 5 | [Moonshine's first text is paced by its update interval, not its lookahead](#moonshines-first-text-is-paced-by-its-update-interval-not-its-lookahead) | Measured (was: observation) |
| 6 | [Moonshine returns no text, and no error, on some quiet clips](#moonshine-returns-no-text-and-no-error-on-some-quiet-clips) | Model behaviour |
| 7 | [Moonshine runs inference inside `addAudio()`](#moonshine-runs-inference-inside-addaudio) | Integration gotcha |
| 8 | [Moonshine 0.1.5 has no thread-count setting](#moonshine-015-has-no-thread-count-setting) | Deviation from plan |
| 9 | [FLEURS English is recorded 40 dB quieter than FLEURS Spanish](#fleurs-english-is-recorded-40-db-quieter-than-fleurs-spanish) | Corpus confound |
| 10 | [The platform recognizer can return only the last clause](#the-platform-recognizer-can-return-only-the-last-clause) | Android gotcha |
| 11 | [The platform recognizer had Spanish installed and English missing](#the-platform-recognizer-had-spanish-installed-and-english-missing) | Android gotcha |
| 12 | [Installing a language pack: the API works, the Settings UI does not](#installing-an-on-device-language-pack-the-api-works-the-settings-ui-does-not) | Android gotcha |
| 13 | [The recognizer sometimes ends a session with an error *and* correct text](#the-platform-recognizer-sometimes-ends-a-session-with-an-error-and-correct-text) | Android gotcha |
| 14 | [`adb push` into an app's own files dir can be invisible to that app](#adb-push-into-an-apps-own-external-files-dir-can-be-invisible-to-that-app) | Android gotcha |
| 15 | [A stalled consumer will hang a paced feeder, not fail it](#a-stalled-consumer-will-hang-a-paced-feeder-not-fail-it) | Harness bug |
| 16 | [Filtering out "bad" measurement rows can flatter what you measure](#filtering-out-bad-measurement-rows-can-flatter-the-thing-you-are-measuring) | Measurement integrity |
| 17 | ["End of speech" was stamped up to one frame early](#end-of-speech-was-stamped-up-to-one-frame-early) | Measurement integrity |
| 18 | [An empty transcript is every word missed, not a row to skip](#an-empty-transcript-is-every-word-missed-not-a-row-to-skip) | Measurement integrity |
| 19 | [Live partials from an offline model multiply its compute](#live-partials-from-an-offline-model-multiply-its-compute) | Measured |
| 20 | [Silero VAD hears quiet speech, but late](#silero-vad-hears-quiet-speech-but-late) | Model behaviour |
| 21 | [Parakeet can return nothing for a tightly cut segment](#parakeet-can-return-nothing-for-a-tightly-cut-segment) | Model behaviour |
| 22 | [Years written as digits cost four WER points](#years-written-as-digits-cost-four-wer-points) | Measurement integrity |
| 23 | [Twenty clips could not tell the arms apart](#twenty-clips-could-not-tell-the-arms-apart) | Measurement integrity |
| 24 | [Accuracy reproduced; one session's timing did not](#reproducibility) | Measurement integrity |
| 25 | [Two ONNX Runtimes in one APK collide at packaging](#two-onnx-runtimes-in-one-apk-collide-at-packaging) | Integration gotcha |
| 26 | [sherpa-onnx's Kotlin VAD splits utterances after 5 seconds by default](#sherpa-onnxs-kotlin-vad-splits-utterances-after-5-seconds-by-default) | Integration gotcha |
| 27 | [The battery temperature an app can read can be minutes old](#the-battery-temperature-an-app-can-read-can-be-minutes-old) | Measurement integrity |
| 28 | [Excluded before testing](#excluded-before-testing) | Scope decisions |

> **New here?** Start with the [Phase 3 summary](summaries/phase-3-arms-d-e.md),
> then [Phase 2](summaries/phase-2-arm-b.md) and [Phase 1](summaries/phase-1-arm-a.md),
> for results without the process. Then [Findings](#findings-and-dead-ends) for
> what was learned the hard way, and [`PLAN.md`](PLAN.md) for the full
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
| B | Moonshine streaming tiny/small/medium | native | ✅ | ❌ | 78 / 224 / 416 MB | `ai.moonshine:moonshine-voice` | EN frontrunner | ✅ **measured, all three sizes** |
| C | Moonshine `base-es` (VAD-segmented) | no | ❌ | ✅ | 64.8 MB | same | ES cheap option ⚠️ non-commercial | ⬜ not started |
| D | Parakeet TDT 0.6b v3 int8 (VAD-segmented) | no | ✅ | ✅ | 670 MB | sherpa-onnx | One-model-for-both candidate | ✅ **measured, both** |
| E | Whisper small + base int8 (VAD-segmented) | no | ✅ | ✅ | 375 / 161 MB | sherpa-onnx | Known baseline / calibration | ✅ **measured, both sizes, both languages** |

Arm A decides whether bundling a model is justified at all. If the platform
recognizer is good enough on this audio, that is a legitimate and
money-saving result — which is why it is built first.

## Results

Every number comes from the Snapdragon 870: file-fed, paced to the wall
clock, phone unplugged, behind a 35 °C start gate. Repetition 0 is
discarded, and figures are medians over all remaining rows.

**How runs are combined.** Accuracy reproduces across runs. Arm A scored an
identical 9.69% on FLEURS English in two runs seven hours apart. So WER and CER
are **pooled over every valid run**. Timing did not always reproduce (see
[Reproducibility](#reproducibility)), so latency comes from the **Phase 2
runs**: made back to back on the afternoon of 2026-09-23, both arms, with the
corrected harness. English was later re-measured on 100 clips per source. Its
WER replaces the 20-clip figures in every table below, while the timing
columns keep the original runs; the 100-clip runs' own timing is in
[English on 100 clips per source](#english-on-100-clips-per-source).

**Final text** is measured from the actual end of the audio, clamped at zero:
text already final when the audio ended counts as 0 ms of waiting. Phase 1
measured it from when the feeder returned, which ran up to one frame early (see
[finding](#end-of-speech-was-stamped-up-to-one-frame-early)).

### Arm A: the platform recognizer (0 MB)

| Lang | Source | Audio | WER | CER | Final text | First text | Revisions | Slip median | `peak_rss_mb` |
|---|---|---|---|---|---|---|---|---|---|
| es | FLEURS `es_419` | clean | **7.39%** | 3.55% | 0 ms | 2009 ms | 6 | 6 ms | 124 |
| en | FLEURS `en_us` | clean, level-matched | **9.72%** | 5.22% | 76 ms | 1259 ms | 8 | 6 ms | 118 |
| en | FLEURS `en_us` | clean, original (**very quiet**) | 9.69% | 4.36% | 50 ms | 1212 ms | 8 | 6 ms | 118 |
| en | LibriSpeech `test-other` | **noisy** | **27.09%** | 20.11% | 68 ms | 1012 ms | 8 | 6 ms | 118 |

Spanish covers 100 clips (1,767 reference words): the original 20, measured
in four runs, and 80 added in Phase 3, measured in the fourth. Each clip
counts once, however many runs measured it. Level-matched and noisy English
cover 100 clips each (1,710 and 1,489 reference words), from the Phase 2 runs
and a 2-repetition run on 2026-09-25; the very quiet originals are still the
20 clips of Phases 1–2. `peak_rss_mb` is our harness only; the recognizer
runs in Google's process.

> **Revised in Phase 2.** Three things Phase 1 published have changed:
>
> - **Spanish WER** was 7.73%. The scorer skipped a row that returned no text
>   instead of counting its words as missed
>   ([finding](#an-empty-transcript-is-every-word-missed-not-a-row-to-skip)).
>   Pooled with a third run it is 8.14%.
> - **English latency** (final 123 / 315 ms, first text 1260 / 1276 ms) came
>   from a run whose timing did not reproduce. Its accuracy did.
> - **English accuracy** was measured on clips ~40 dB quieter than everything
>   else ([finding](#fleurs-english-is-recorded-40-db-quieter-than-fleurs-spanish)).
>   Level-matched clips are now in the corpus.

> **Revised in Phase 3.** Two scoring fixes and a larger Spanish set:
>
> - **Noisy-English WER** was 33.33% (CER 25.92%). The scorer read "1848" as
>   "one thousand eight hundred forty eight", while the reference says
>   "eighteen forty eight", so every year this recognizer wrote as digits cost
>   about four errors
>   ([finding](#years-written-as-digits-cost-four-wer-points)). The English
>   session moved too, from 10.17% to 10.10%.
> - **Spanish WER** was 8.14% on 20 clips. The scorer read "12:00" as "doce
>   cero", which no one says, and dropped the "%" this recognizer writes
>   where the reference says "por ciento". Fixed, those 20 clips score 7.75%.
>   On the 100-clip set it is 7.39%
>   ([finding](#twenty-clips-could-not-tell-the-arms-apart)).

> **Revised before Phase 4.** English grew to 100 clips per source. Noisy
> English was 29.31% (CER 22.42%) on 20 clips and is **27.09%** on 100.
> Level-matched English was 12.60% (CER 8.78%), dominated by one clip that
> returned only its last clause, and is **9.72%** on 100
> ([results](#english-on-100-clips-per-source)).

### Three things worth stopping on

**1. Accuracy collapses on noisy audio, and that reproduces.** 27.09% WER on
100 LibriSpeech `test-other` clips, with CER at 20%. The original 20 gave
29.42% and 29.19% in two runs. Roughly one word in four is wrong in
conditions resembling an ordinary room.
That failure is the case a bundled model would exist to fix.

**2. "Spanish is more accurate than English" is not established.** Phase 1
reported 7.73% vs 9.69% on "the same corpus, recorded the same way". Neither
half survived. The English clips turned out to be ~40 dB quieter, and Spanish
was corrected to 8.14% (7.39% on the 100-clip set since Phase 3). On the
original 20 clips at matched level, English scored 12.60%, dominated by one
clip where the recognizer returned only the last clause
([finding](#the-platform-recognizer-can-return-only-the-last-clause)). On 100
clips it scores 9.72% against Spanish's 7.39%. The sentences differ between
languages, so no paired test applies, and a gap of about two points is as
far as the data goes.

**3. Spanish is slower to show text, and that one does reproduce.** First text
arrives at ~2.0 s in Spanish against 1.0–1.3 s in English. The Spanish figure
landed within 11 ms across three runs (2019 / 2008 / 2009 ms). For continuous
dictation it is paid once per session (6-minute sessions: 2607 vs 1214 ms), and
for voice commands on every utterance. Final text is fast in both languages
(0–76 ms after the audio ends). Clips end with different amounts of silence,
though, so the final-text difference between languages is not a clean
comparison.

### Reproducibility

Accuracy reproduces almost exactly. Timing reproduced in every run but one.

| Arm A, English | Phase 1 run | Phase 2 run |
|---|---|---|
| WER, FLEURS | 9.69% | 9.69% |
| WER, LibriSpeech | 29.42% | 29.19% |
| Slip median, LibriSpeech | **196 ms** | 6 ms |
| Final text (old stamp), LibriSpeech | **315 ms** | 120 ms |
| First text, LibriSpeech | 1276 ms | 1012 ms |
| First text, FLEURS | 1260 ms | 1212 ms |

The Phase 1 English run began **one minute after the English language pack
finished installing**. The Spanish runs before it, and every run since, show
slip of 4–6 ms on all audio, noisy included. The likeliest explanation is the
recognizer still settling after the install, but that is an inference, not a
measurement. What is certain is that Phase 1's English timing, including the
315 ms final latency on noisy audio, describes that session rather than the
recognizer.

| Arm A, Spanish | run 1 | run 2 | run 3 (Phase 2) |
|---|---|---|---|
| WER | 8.79% | 7.75% | 7.88% |
| WER, utterances that returned text | 7.71% | 7.75% | 7.88% |
| First text | 2019 ms | 2008 ms | 2009 ms |
| Final text (old stamp) | 28 ms | 27 ms | 27 ms |

These are the Phase 2 scorer's figures. Under Phase 3's (clock times,
percent signs and similar formatting read as spoken), the three runs read
8.40%, 7.36% and 7.49%. A fourth run, a day later, gave 7.75% on the same 20
clips, with first text at 2006 ms.

Run 1's higher WER is one utterance, `fleurs-es-short-009`. It ended in
`ERROR_CLIENT` / `EPIPE` before emitting any text, on a sentence every other
repetition transcribed identically.

Within a run, the determinism file-fed measurement was meant to buy is there.
The same clip lands within a few milliseconds across repetitions, and most
clips produce byte-identical text every time (D5). A human repeating a sentence
four times cannot do that.

### `rtf_sustained` is blank for Arm A, and slip does not stand in for it

Recognition runs inside Google's process, so time spent in our sink is a pipe
write and says nothing about the model. Reporting a number there would be a
fiction (D6).

Phase 1 proposed schedule slip as the stand-in, because it seemed to track
difficulty: 4 ms on clean Spanish, 6 ms on clean English, 196 ms on noisy
English. The Phase 2 re-run withdrew that. Slip is 6 ms on every source,
noisy included, and the 196 ms belonged to the one disturbed session above.
So for Arm A there is no proxy for keeping up with real time. What can be said
is that in the Phase 2 runs it never stalled its input.

### Decision gate (PLAN.md §10, Phase 1)

**Arms C and D stay in the matrix.** Arm A is free and reasonable on clean
speech, but 29% WER on noisy English is disqualifying for anything used in an
ordinary room, and that is the case a bundled model would exist to fix. It also
depends on a language pack the user may not have: on this handset, English had
to be installed before it could be measured at all.

The first-text numbers are an upper bound for dictation. The harness builds a
fresh `SpeechRecognizer` per clip, so every utterance pays full session
startup. That models a voice-command app; continuous dictation holds one open
and pays it once.

### Arm B: Moonshine streaming, English

Moonshine v2 streaming, via `ai.moonshine:moonshine-voice:0.1.5`, weights
loaded from disk at the pinned revision. Same corpus, same paced feeder, same
phone as Arm A. English only in Phases 2–3: the Spanish streaming model was
believed not to exist, and in fact sits on Moonshine's CDN rather than
HuggingFace ([finding](#moonshines-spanish-streaming-models-are-on-its-cdn-not-on-huggingface)).
Phase 4 adds it.

| Variant | Source | Audio | WER | CER | Final text | First text | Revisions | `rtf_sustained` | `peak_rss_mb` |
|---|---|---|---|---|---|---|---|---|---|
| tiny | FLEURS `en_us` | clean, level-matched | 11.25% | 5.76% | 0 ms | 1064 ms | 16 | 0.423 | 363–475 |
| tiny | FLEURS `en_us` | clean, original (**very quiet**) | 30.05% | 23.05% | 0 ms | 1558 ms | 8 | 0.378 | |
| tiny | LibriSpeech `test-other` | **noisy** | 14.43% | 6.44% | 84 ms | 1058 ms | 10 | 0.404 | |
| small | FLEURS `en_us` | clean, level-matched | 7.36% | 3.01% | 144 ms | 1655 ms | 8 | 0.734 | 605 |
| small | FLEURS `en_us` | clean, original (**very quiet**) | 27.29% | 23.41% | 0 ms | 1675 ms | 8 | 0.667 | |
| small | LibriSpeech `test-other` | **noisy** | 8.92% | 4.19% | 442 ms | 1191 ms | 8 | 0.723 | |
| medium | FLEURS `en_us` | clean, level-matched | 4.71% | 1.73% | 414 ms | 1285 ms | 7 | 0.797 | 933 |
| medium | FLEURS `en_us` | clean, original (**very quiet**) | 25.31% | 22.78% | 246 ms | 1251 ms | 6 | 0.763 | |
| medium | LibriSpeech `test-other` | **noisy** | 7.02% | 3.58% | 662 ms | 1241 ms | 8 | 0.797 | |

Disk: tiny 77.7 MB · small 224.1 MB · medium 416.0 MB. Tiny is pooled over
two runs (LibriSpeech gave 14.43% in both); small and medium are one
4-repetition run each, plus, for level-matched and noisy WER, a 3-repetition
run each on 100 clips per source
([results](#english-on-100-clips-per-source)). Tiny and the very quiet rows
are the original 20 clips. A one-repetition medium pilot (6.71% / 5.94%) agreed
with the full run and is kept in `results/superseded/`. `peak_rss_mb` is a
process high-water mark that includes the harness's own heap. It bounds each
model's footprint from above, and it is not comparable to Arm A's.

> **Revised in Phase 3.** Tiny's noisy-English WER was 15.77% (CER 7.44%),
> and Arm A's, compared against below, was 33.33%. Both wrote some years as
> digits, which the scorer penalised
> ([finding](#years-written-as-digits-cost-four-wer-points)). Small and
> medium spell years out and are unchanged.

**The size curve, English, against Arm A:**

| | Arm A (0 MB) | tiny (78 MB) | small (224 MB) | medium (416 MB) |
|---|---|---|---|---|
| WER, **noisy** | 27.09% | 14.43%³ | 8.92% | **7.02%** |
| WER, clean, level-matched | 9.72% | 11.25%³ | 7.36% | **4.71%** |
| WER, clean, **very quiet** | **9.69%** | 30.05% | 27.29% | 25.31% |
| First text, noisy / clean | 1012 / 1259 ms | 1058 / 1064 ms | 1191 / 1655 ms | 1241 / 1285 ms |
| Final text, noisy / clean | **68 / 76 ms** | 84 / 0 ms | 442 / 144 ms | 662 / 414 ms |
| `rtf_sustained` (worst clip) | — | 0.40 (0.61) | 0.72 (0.94) | 0.80 (0.95) |
| SDK time per model pass | — | ~200 ms | ~450 ms | ~550 ms |
| Peak RSS | 118 MB² | 363–475 MB | 605 MB | 933 MB |
| Battery per 240 clips | 3 pts | 6 pts | 10 pts | 11 pts |
| Peak temperature | 29.2 °C | 30.7 °C | 33.5 °C | **35.0 °C** |

² Our harness only; the recognizer's own memory is in Google's process.
³ Original 20 clips; the other WERs cover 100 clips per source. Timing, memory
and cost rows are the Phase 2 runs.

**What Arm B answers:**

1. **Does Moonshine beat Arm A on noisy English? Yes, at every size.** On
   100 noisy clips small cuts Arm A's WER to a third (8.92% vs 27.09%) and
   medium to about a quarter (7.02%). Tiny, measured on the original 20,
   halves it (14.43% vs 29.31% on those clips). Small's CER on noisy speech
   is 4.19% against Arm A's 20.11%. That noisy-audio failure was the main
   reason to consider bundling a model, and it is fixed.
2. **The size curve bends at small.** On the original 20 clips, going from
   tiny to small cuts WER by two fifths on noisy audio and by a third on
   clean, for +146 MB. On 100 clips, medium is 1.9 points better than small
   on noisy speech and 2.7 on clean, both resolved, the noisy one only just.
   That costs +192 MB of disk, 200–330 MB more resident memory, slower final
   text, and a phone running at the thermal gate.
3. **Bigger models do not fall behind real time; they finalise later.**
   `rtf_sustained` stays under 1.0 even for medium (0.80, worst clip 0.95),
   because the SDK's update cadence absorbs longer passes. The cost goes
   elsewhere. Each pass takes ~200 / 450 / 550 ms by size, and final text on
   noisy audio moves from 84 to 442 to 662 ms after the speaker stops. For
   Moonshine, the constraints that bind are latency and memory, not keeping
   up.
4. **Streaming-native buys no latency over Arm A at SDK defaults.** First text
   takes 1.0–1.7 s for every arm. Moonshine's first text is paced by a 0.5 s
   update interval, not its 80 ms lookahead
   ([finding](#moonshines-first-text-is-paced-by-its-update-interval-not-its-lookahead)),
   and that interval is tunable. Only tiny matches Arm A on final text; small
   and medium are slower. An earlier version of this section credited
   Moonshine with much faster final text (114 vs 315 ms). That comparison
   leaned on Arm A's disturbed Phase 1 run, and it does not stand.
5. **On very quiet audio, Arm A is robust and Moonshine is not, at any
   size.** Every variant returns no text at all on the same quiet clips, with
   no error, and tiny's output there does not even reproduce between runs.
   The level-matched copies of those clips transcribe fine. A real microphone
   with gain control usually delivers far hotter levels, so this may rarely
   matter live. But a quiet talker or a distant phone is exactly where it
   would, and the API gives no way to detect it.

**Known limits of these numbers:**

- **`rtf_sustained` is per clip here, not over a session.** The metric is
  defined over 5–10 minutes of continuous audio (§6). No Arm B session has
  been run. Medium is the one to watch: it is the only run of any arm to hit
  the 35 °C gate, after about 30 minutes of clips with the mandatory breaks
  (the harness paused ~4 minutes to cool). Across its four repetitions,
  `rtf_sustained` held at 0.79–0.81 and final latency showed no trend while
  the phone warmed from 33.5 to 35 °C. So there was no throttling visible at
  this duty cycle, but a continuous session is a harder test.
- **The thread count is not controlled for Arm B.** The harness records
  `threads: 4`, but the SDK has no such setting, so ONNX Runtime's default
  pool applies ([finding](#moonshine-015-has-no-thread-count-setting)).
- **Louder audio makes Moonshine revise more, at least for tiny.** On the 17
  clips where both versions produced text, tiny's revisions rose from 10 to 16
  per clip with level matching, while the number of updates barely moved
  (11 vs 12). So it rewrites more of what it has already shown, not just shows
  more. Arm A's count did not change (8 and 8), and small and medium show
  7–8 on both. Why is not known.

### Arms D and E: offline models made live, both languages

Parakeet TDT 0.6b v3 (Arm D) and Whisper (Arm E) are offline models: they
take a whole utterance and return its text. Both run through sherpa-onnx
1.13.8 with Silero VAD, the way a live app would use them
([`SherpaOfflineArm`](bench/app/src/main/kotlin/io/github/davamix/asrbench/arms/SherpaOfflineArm.kt)):

- **The VAD cuts the paced audio into utterances.** A segment closes after
  0.25 s of silence and is decoded once for its **final** text.
- **While speech is still open, the audio so far is re-decoded every 500 ms**
  and shown as a **partial**. That is the Moonshine SDK's update interval, so
  both stacks refresh text on the same cadence. A partial is skipped if the
  previous decode is still running.
- **Decoding runs on its own thread**, so the feed does only the VAD and
  never stalls, unlike Moonshine's `addAudio()`
  ([finding](#moonshine-runs-inference-inside-addaudio)). Slip therefore stays
  under 50 ms and says little. The cost of a slow model shows up as
  compute and as final latency.
- 4 ONNX Runtime threads. sherpa-onnx exposes the setting, so unlike
  Moonshine's this one is real.

Whisper is told the language, as Arm A is. Parakeet takes no hint and detects
it. Same corpus, paced feeder and phone as Arms A and B.

| Arm | Source | Audio | WER | CER | Final text | First text | Revisions | Compute, with partials (finals only) | `peak_rss_mb` |
|---|---|---|---|---|---|---|---|---|---|
| D Parakeet | FLEURS `es_419`, 100 clips | clean | **4.47%** | 1.76% | 0 ms | 2372 ms | 12 | 0.58 (0.21) | 1027 |
| D Parakeet | FLEURS `en_us` | clean, level-matched | **4.97%** | 2.30% | 186 ms | 1797 ms | 12 | 0.60 (0.22) | |
| D Parakeet | FLEURS `en_us` | clean, original (**very quiet**) | 14.69% | 7.81% | 143 ms | 2416 ms | 24 | 0.56 (0.21) | |
| D Parakeet | LibriSpeech `test-other` | **noisy** | 9.34% | 5.57% | 353 ms | 1556 ms | 8 | 0.56 (0.23) | |
| E Whisper base | FLEURS `es_419`, 20 clips | clean | 14.73% | 4.35% | 471 ms | 2322 ms | 14 | 0.81 (0.27) | 572 |
| E Whisper base | FLEURS `en_us` | clean, level-matched | 11.88% | 6.64% | 618 ms | 1717 ms | 9 | 0.88 (0.28) | |
| E Whisper base | FLEURS `en_us` | clean, original (**very quiet**) | 21.88% | 13.39% | 518 ms | 1835 ms | 26 | 0.76 (0.28) | |
| E Whisper base | LibriSpeech `test-other` | **noisy** | 22.82% | 9.62% | 678 ms | 1522 ms | 16 | 0.83 (0.30) | |
| E Whisper small | FLEURS `es_419`, 20 clips | clean | 11.24% | 4.22% | 2750 ms | 2990 ms | 2 | 1.24 (0.52) | 997 |
| E Whisper small | FLEURS `en_us` | clean, level-matched | **5.31%** | 2.39% | 2683 ms | 2306 ms | 4 | 1.36 (0.53) | |
| E Whisper small | FLEURS `en_us` | clean, original (**very quiet**) | 10.31% | 5.69% | 2659 ms | 2400 ms | 8 | 1.31 (0.53) | |
| E Whisper small | LibriSpeech `test-other` | **noisy** | 14.43% | 6.19% | 2424 ms | 2180 ms | 5 | 1.35 (0.59) | |

One 4-repetition run per model, 80 clips per repetition, repetition 0
discarded. WER was identical to the hundredth in every repetition: greedy
decoding behind a deterministic VAD, fed identical audio, gives identical text.
Disk: Parakeet 670.5 MB, Whisper small 375.4 MB, Whisper base 160.6 MB, plus
2.2 MB for Silero VAD. Parakeet and Whisper base were measured on 2026-09-23,
Whisper small the next morning after a recharge. Parakeet's Spanish row is
from a second run that day on the 100-clip Spanish set; its text on the
original 20 clips was byte-identical to the first run's. Parakeet's
level-matched and noisy WER cover 100 clips per source, adding a 2-repetition
run on 2026-09-24 ([results](#english-on-100-clips-per-source)); the other
columns are the first run. Whisper was measured on the original 20 clips per
source only.

**Against the bar, both languages:**

| | Arm A (0 MB) | Moonshine small (224 MB) | Moonshine medium (416 MB) | Whisper base (161 MB) | Whisper small (375 MB) | **Parakeet (670 MB)** |
|---|---|---|---|---|---|---|
| WER, **Spanish**, 100 clips | 7.39% | — | — | — | — | **4.47%** |
| WER, Spanish, original 20 clips | 7.75% | — | — | 14.73% | 11.24% | **6.20%** |
| WER, English **noisy** | 27.09% | 8.92% | **7.02%** | 22.82%³ | 14.43%³ | 9.34% |
| WER, English clean, level-matched | 9.72% | 7.36% | **4.71%** | 11.88%³ | 5.31%³ | 4.97% |
| WER, English clean, **very quiet** | **9.69%** | 27.29% | 25.31% | 21.88% | 10.31% | 14.69% |
| First text, noisy / clean / es | 1012 / 1259 / 2009 ms | 1191 / 1655 / — | 1241 / 1285 / — | 1522 / 1717 / 2322 | 2180 / 2306 / 2990 | 1556 / 1797 / 2373 |
| Final text, noisy / clean / es | **68 / 76 / 0 ms** | 442 / 144 / — | 662 / 414 / — | 678 / 618 / 471 | 2424 / 2683 / 2750 | 353 / 186 / 0 |
| Compute, share of real time | — | 0.72 | 0.80 | 0.76–0.88 | **1.24–1.36** | 0.52–0.60 |
| Peak RSS | 118 MB² | 605 MB | 933 MB | 572 MB | 997 MB | 1027 MB |
| Battery per 240 clips | 3 pts | 10 pts | 11 pts | 15 pts | 29 pts | 10.5 pts |
| Cooling pauses at the 35 °C gate | 0 | 0 | 1 | 7 | **15** | 0 |

² Our harness only; the recognizer's own memory is in Google's process.
³ Original 20 clips. The other English WERs cover 100 clips per source.

**What Arms D and E answer:**

1. **One model can serve both languages, and it is Parakeet.** It beats the
   platform recognizer in Spanish: 4.47% against 7.39% on 100 clips, a gap of
   2.9 points with a 95% interval of +0.55 to +5.81
   ([finding](#twenty-clips-could-not-tell-the-arms-apart)). Nothing else in
   the matrix does. On English, on 100 clips per source, it is level with
   Moonshine medium on clean speech (4.97% vs 4.71%) and with Moonshine
   small on noisy (9.34% vs 8.92%)
   ([results](#english-on-100-clips-per-source)). It is the first arm
   competitive in both languages at once, with no language setting.
2. **Memory did not bite.** The plan flagged Parakeet as the arm most likely
   to fail on memory, expecting 1.5–2 GB resident. It peaked at 1027 MB,
   about 100 MB above Moonshine medium, and loaded in 2.6 s. There were no
   kills and no errors in 320 clips. The price is disk: 670 MB, three times
   Moonshine small.
3. **Parakeet is also faster than Whisper base, at eight times the size.**
   Its partial decodes take ~290 ms against Whisper base's ~480 ms, and its
   finals 310–460 ms against 540–840 ms (medians by source). It needs 0.52–0.60 of real time with
   partials, where Whisper base needs 0.76–0.88. So it ran cooler: no cooling
   pauses, against seven for Whisper base, and a third less battery.
4. **Whisper calibrates as expected, and neither size is a contender.**
   Base's Spanish is nearly twice Arm A's error (14.73% against 7.75% on the
   same 20 clips), and its English is level with Arm A on clean speech and
   well behind every bundled model on noisy. On one quiet clip it produced a repetition loop ("4x4, 3x3, 3x4,
   3x4…") instead of the sentence, in every repetition. Small is the most
   accurate model on the original 20 clean English clips (5.31%, against
   medium's 5.94% and Parakeet's 6.88% on the same clips, gaps 20 clips
   cannot resolve), and the most robust
   bundled model on very quiet audio (10.31%, close to Arm A's 9.69%). Its
   Spanish is well behind Parakeet's and 3.5 points behind Arm A's on the same
   20 clips (11.24% vs 7.75%), a gap 20 clips cannot resolve. It is also far
   too slow to be live:
   final text 2.4–2.8 s after the speaker stops, each final decode ~2 s.
5. **Final text is quick; first text is not.** Final text arrives 0–350 ms
   after the audio ends for Parakeet, because the VAD closes most segments on
   the clip's own trailing silence and the decode is fast. First text is the
   weak spot. At 1.5–3.0 s the VAD arms are the slowest in the matrix. A
   partial waits for the VAD to declare speech (at least 0.25 s of it), then
   one 500 ms interval, then a decode. In Spanish every arm is slow
   (2.0–3.0 s), and there Parakeet matches Arm A's final text (0 ms).
6. **Partials cost more than the model.** Showing live text roughly doubles
   or triples the compute: finals alone need 0.20–0.59 of real time, and
   re-decoding open speech every 500 ms brings that to 0.52–1.36
   ([finding](#live-partials-from-an-offline-model-multiply-its-compute)).
   Whisper small goes past 1.0: its decode thread never idles while someone
   is speaking, partials are skipped, and finals queue behind them.
7. **Quiet audio costs the first word, and that is the VAD.** Unlike
   Moonshine, the VAD found speech in every very quiet clip: no blank rows.
   But it starts late, and all three models miss the first word of the
   sentence on 8–12 of those 20 clips, against 3–4 at normal level
   ([finding](#silero-vad-hears-quiet-speech-but-late)).

**Known limits of these numbers:**

- **"Partials off" figures are derived, not measured.** Each row records how
  long the final waited behind a running partial. Subtracting that wait
  estimates final latency without partials: 0–250 ms for Parakeet, 90–480 ms
  for Whisper base, 1.5–1.8 s for Whisper small. A run with `--partial-ms 0` would measure it.
- **The partial cadence is a choice.** 500 ms matches Moonshine. The first
  partial is attempted one interval after the VAD declares speech, as in
  sherpa-onnx's own example, which uses 200 ms. A shorter interval would bring
  first text forward by up to 300 ms and cost more compute.
- **Per clip, not sustained.** No session runs yet. Parakeet's worst clip used
  0.85 of real time with partials, so a longer, hotter session is a fair
  question.
- **Temperatures lag** ([finding](#the-battery-temperature-an-app-can-read-can-be-minutes-old)).
  The cooling pauses are counted from the gate's own log.

### English on 100 clips per source

Twenty clips per English source could rank Arm A against the bundled models,
but not the bundled models against each other
([finding](#twenty-clips-could-not-tell-the-arms-apart)). So the English
corpus grew to 100 clips per source ([Corpus](#corpus)), and the four arms
that matter were measured again on 2026-09-24 and -25, on the level-matched
and noisy sources only: Moonshine small and medium with 3 repetitions, whose
streaming output varies a little between repetitions, and Parakeet and Arm A
with 2. Repetition 0 is discarded, and all four runs used the same build.
WER pools every valid run with each clip counted once, so the original 20
clips also carry their earlier runs. Timing and cost come from these runs
alone.

| English, 100 clips per source | Arm A (0 MB) | Moonshine small (224 MB) | Moonshine medium (416 MB) | Parakeet (670 MB) |
|---|---|---|---|---|
| WER, clean, level-matched | 9.72% | 7.36% | **4.71%** | 4.97% |
| WER, **noisy** | 27.09% | 8.92% | **7.02%** | 9.34% |
| CER, clean / noisy | 5.22 / 20.11% | 3.01 / 4.19% | **1.73 / 3.58%** | 2.30 / 5.57% |
| Final text, clean / noisy | **69 / 81 ms** | 222 / 486 ms | 539 / 772 ms | 250 / 349 ms |
| First text, clean / noisy | **1162 / 1114 ms** | 1316 / 1190 ms | 1291 / 1243 ms | 1872 / 1576 ms |
| Compute, share of real time | — | 0.73 | 0.82 | 0.56–0.62 (finals only 0.22) |
| Peak RSS | 111 MB² | 730–760 MB | 967 MB | 1034 MB |
| Battery per 240 clips | 2.4 pts | 10.4 pts | 13.2 pts | 10.2 pts |
| Cooling pauses at the 35 °C gate | 0 | 2 | 7 | 3 |
| Run | 400 rows, 64 min | 600 rows, 111 min | 600 rows, 132 min | 400 rows, 81 min |

² Our harness only; the recognizer's own memory is in Google's process.

Which gaps are real (`scripts/compare.py`, paired bootstrap over clips):

| English WER, 100 clips | A − B | 95% interval | |
|---|---|---|---|
| Arm A − Moonshine small, noisy | +18.2 | +12.9 to +23.7 | resolved |
| Arm A − Moonshine small, clean | +2.4 | +0.1 to +5.1 | resolved, barely |
| Arm A − Parakeet, clean | +4.8 | +2.6 to +7.4 | resolved |
| Moonshine small − medium, clean | +2.7 | +1.5 to +3.9 | resolved |
| Moonshine small − medium, noisy | +1.9 | +0.0 to +3.8 | resolved, barely |
| Moonshine small − Parakeet, clean | +2.4 | +1.3 to +3.6 | resolved |
| Moonshine small − Parakeet, noisy | −0.4 | −2.8 to +1.7 | not resolved |
| Moonshine medium − Parakeet, clean | −0.3 | −1.2 to +0.7 | not resolved |
| Moonshine medium − Parakeet, noisy | −2.3 | −5.1 to +0.2 | not resolved |

**What 100 clips answer:**

1. **Moonshine medium is the most accurate English model measured.** It
   beats small on both sources and is level with Parakeet on clean speech.
   On noisy speech it leads Parakeet by 2.3 points, an interval that just
   includes zero.
2. **Parakeet is level with medium on clean speech, and with small on noisy
   speech.** On 20 clips it looked level with both on noisy audio (7.05%).
   On the 80 new noisy clips it scored 9.91%, against medium's 7.22%. Most
   of that is not mishearing: on some tightly cut VAD segments it returns
   no text at all
   ([finding](#parakeet-can-return-nothing-for-a-tightly-cut-segment)). Half
   a second of leading silence recovers about two points in a PC diagnostic,
   which would put it level with medium. The published figure is the stack
   as sherpa-onnx ships it.
3. **Arm A is better on clean English than 20 clips said, and still fails on
   noisy.** Its level-matched 12.60% was mostly one clip; on 100 clips it
   scores 9.72%, and small's lead there is real but slim (2.4 points, lower
   bound 0.06). On noisy speech every bundled model is 18–20 points ahead.
   That noisy-audio gap is still the case for bundling a model.
4. **The latency ordering held.** Arm A finalises fastest, medium slowest,
   with small and Parakeet between; Parakeet shows first text last. The
   figures moved from the 20-clip runs by up to ~125 ms for final text and
   ~340 ms for first text, but the clip sets differ, so that is not a
   reproducibility test. Medium's final text moved most (414 / 662 → 539 /
   772 ms), in a run that sat at the 35 °C gate for much of its length.
   Whether heat is the cause was not established.
5. **One verdict is fragile to spelling.** Parakeet and small sometimes write
   a compound as one word where LibriSpeech's references use two
   ("reelected", "everyone" against "re elected", "every one"), and the
   scorer charges two errors each. Joining such pairs before scoring moves
   every bundled model by 0.2–0.6 points. It changes one verdict: small
   against medium on noisy speech becomes unresolved (−0.2 to +3.4). The
   scorer was not changed, because a generic rule would also forgive real
   errors like "a way" for "away".

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
| `short` | 100 clips × 3 sources (FLEURS `en_us`, FLEURS `es_419`, LibriSpeech `test-other`), plus a level-matched copy of every English FLEURS clip. 3–8 s (mean 5.4–6.5 s), except Spanish clips 20–99 at 3–10 s (mean 8.6 s) | latency per utterance; accuracy |
| `session` | 1 per language, ~6 min, 0.5–1.5 s gaps | sustained RTF, thermals |

Sources are deliberately symmetric: FLEURS `en_us` and `es_419` are the same
corpus recorded the same way in both languages, so an English-vs-Spanish
comparison is not confounded by domain or recording conditions. LibriSpeech
`test-other` adds the noisy-English stress case that FLEURS's clean read
speech does not cover.

Current build: 402 clips, 56.6 minutes total (en 2202.4 s, es 1193.9 s),
all 16 kHz mono PCM16. A hundred of them are `fleurs_en_norm`: the English
FLEURS clips lifted by a static gain to −23 dBFS RMS, because the originals
are recorded ~40 dB quieter than the other sources
([finding](#fleurs-english-is-recorded-40-db-quieter-than-fleurs-spanish)).
The build is deterministic: each addition reproduced every existing file byte
for byte. Session clips are disjoint from short clips, so
sustained-RTF audio is not audio the latency measurement already warmed.

**Spanish has 100 short clips, not 20.** Twenty could not separate the arms
that matter for the one-model-or-two question: on them, Arm A and Parakeet
differ by about one WER point with a 95% interval four points wide on either
side (`scripts/compare.py`). The first 20 also hold only 15 distinct
sentences, because FLEURS records each sentence by several speakers. The 80
added in Phase 3 (`fleurs-es-short-020` to `-099`) are one recording per
sentence, none shared with the first 20 or the Spanish session: 1,509 more
reference words, 1,767 in all. Few unused sentences have a recording under
8 s, so their window is 3–10 s. Every arm hears the same clips, so this does
not affect comparisons between arms. It does mean Spanish clips run longer
than English ones. Adding them reproduced all 144 existing files byte for
byte, so every earlier result still refers to the same audio.

**English followed, for the same reason.** On 20 clips per source the bundled
English models could not be ranked against each other (see
[finding](#twenty-clips-could-not-tell-the-arms-apart)). Phase 3 added 80
FLEURS English sentences (`fleurs-en-short-020` to `-099`, with their
level-matched copies `fleurs-en-norm-short-020` to `-099`) and 80 LibriSpeech
`test-other` utterances (`ls-other-short-020` to `-099`). As with Spanish,
they are one recording per sentence, none shared with the originals or the
English session, and drawn last. Unlike Spanish, enough unused FLEURS English
sentences fit the original 3–8 s window. They add 1,390 reference words to
each FLEURS source and 1,191 to LibriSpeech. Like the originals, 72 of the 80
new FLEURS recordings are very quiet (below −45 dBFS). All 304 existing files
rebuilt byte for byte. Only the level-matched and noisy sources were
re-measured on the new clips
([results](#english-on-100-clips-per-source)): the very quiet originals would
have added half again the phone time, for a question the ranking does not
need.

## Reproducing

```bash
python -m venv .venv
.venv/Scripts/python -m pip install "huggingface_hub>=0.34" jiwer pyarrow soundfile numpy

.venv/Scripts/python scripts/fetch_corpus.py      # ~1.4 GB, pinned revisions
.venv/Scripts/python scripts/build_corpus.py      # -> corpus/audio + manifest.json
.venv/Scripts/python scripts/score.py --validate  # self-test the scorer

.venv/Scripts/python scripts/fetch_models.py --list
.venv/Scripts/python scripts/fetch_models.py --arm B
.venv/Scripts/python scripts/fetch_models.py --arm D   # also --arm E; both pull silero-vad
.venv/Scripts/python scripts/fetch_runtime.py          # sherpa-onnx AAR, hash-checked
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

# Arm B: push the pinned weights, then measure one variant per run
.venv/Scripts/python scripts/run_bench.py --device physical --push-models moonshine-tiny-en
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-tiny-en --langs en --reps 4 --no-push

# Arms D and E: the VAD goes along with each model; both languages
.venv/Scripts/python scripts/run_bench.py --device physical     --push-models silero-vad,parakeet-tdt-v3-int8
.venv/Scripts/python scripts/run_bench.py --device physical     --arms D:parakeet-tdt-v3-int8 --langs en,es --reps 4 --no-push
#   --partial-ms 0 shows final text only; the default re-decodes every 500 ms
#   --sources fleurs_en_norm,librispeech_other measures only those corpus sources

.venv/Scripts/python scripts/compare.py --standard    # paired bootstrap: which gaps are real

.venv/Scripts/python scripts/summarize.py             # score everything, on the PC
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

### Moonshine's Spanish streaming models are on its CDN, not on HuggingFace

> **Corrected in Phase 4.** Until Phase 4 this finding was titled "Moonshine
> has no deployable Spanish streaming model". Moonshine has one. The check
> looked in the one place the SDK does not download from.

Moonshine v2 is streaming-native, MIT-licensed and has first-class Android
support. The Android SDK consumes pre-quantized **`.ort`** files. The public
place to get them is the HuggingFace repo `moonshine-ai/moonshine-voice-assets`,
and that repo has **no Spanish streaming variant at any revision**, including
its current head. Phase 0 checked there, found only the legacy non-streaming
`base-es`, and concluded that Spanish streaming did not exist in a deployable
form.

The SDK's own downloader looks elsewhere. Its model catalog
(`core/moonshine-model-catalog.cpp`, v0.1.5, the version this harness uses)
points at the vendor's CDN, `download.moonshine.ai`. For Spanish it lists
three models there: small and tiny streaming, and `base-es`. The streaming
pair was quantized on 2026-08-24, a month before this experiment began:

| Variant | HF `moonshine-voice-assets` | Vendor CDN | Size |
|---|---|---|---|
| `tiny/small/medium-streaming-en` | ✅ | ✅ (byte-identical, by MD5) | 77.7 / 224.1 / 416.0 MB |
| `base-es` (legacy, non-streaming) | ✅ | ✅ | 64.8 MB |
| `tiny-streaming-es` | ❌ | ✅ | 32.3 MB |
| `small-streaming-es` | ❌ | ✅ | 121.8 MB |

The SDK fetches English from the CDN as well, and the English files there are
the same bytes as the ones pinned from HuggingFace. The catalog also lists
streaming models for German, Arabic, Chinese, Vietnamese, Tagalog and
Japanese. The HuggingFace repo has streaming models for English only. Moonshine's
docs list the Spanish pair at 4.9% and 6.2% WER, so reading them would have
caught this.

What changed for the experiment: `small-streaming-es` joins Arm B in Phase 4.
It makes a second answer to the one-model-or-two question possible, Moonshine
for both languages, as two models on one runtime. A CDN has no revisions to
pin, so `fetch_models.py` pins each file by SHA-256 and refuses a mismatch
(`models/MODELS.md`).

The general lesson: the model hub is not necessarily where an SDK gets its
models. Check the catalog the SDK itself downloads from.

*HF checked 2026-09-23 and 2026-09-24; CDN and SDK source checked 2026-09-24.*

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

### Moonshine's first text is paced by its update interval, not its lookahead

Before measuring, this looked like Moonshine's big structural advantage.
`streaming_config.json` for `tiny-streaming-en` reports `frame_len: 80`
(samples, i.e. 5 ms at 16 kHz) and `total_lookahead: 16` frames, which is about
**80 ms** of algorithmic lookahead. No chunked offline model can match that.

On the phone, the first text took **1.0–1.5 s**, no faster than Arm A. The
times are also suspiciously quantised:

| First text appeared at | Clips |
|---|---|
| ~550 ms | 3 |
| ~1050 ms | 75 |
| ~1550 ms | 33 |
| ~5050 ms | 3 |

108 of 114 values fall within 100 ms of *40 + k × 500 ms*. That is a clock
ticking. `Transcriber.DEFAULT_UPDATE_INTERVAL` is **0.5 s**: the SDK buffers
audio and runs the model on that schedule, so the earliest text can appear is
the first tick after speech is detected, plus ~40 ms of compute.

So the lookahead is real, but it is not what a user sees. What a user sees is
an SDK default, and `setUpdateInterval()` is public. A shorter interval should
bring first text forward, at the cost of more model passes per second, which
raises `rtf_sustained`. The trade-off is not measured yet.

### Moonshine returns no text, and no error, on some quiet clips

On two FLEURS English clips, `fleurs-en-short-015` and `-017`, Moonshine tiny
produced **nothing** on every pass across two runs and a pilot. No line
started, no error fired, and the stream closed cleanly with an empty
transcript. A third clip (`-018`, 6.2 s) showed its first text only at
~5.05 s, again on every pass.

**It is the level.** All three are in the very quiet group (`-015` is the
quietest clip in the corpus, at −66.5 dBFS RMS). The level-matched copies,
which are the same audio lifted by a static gain, transcribe on every pass,
`-015` and `-017` included. The streaming path is gated by a voice activity
detector (the native library carries `vad_threshold`, `vad_window_duration`
and similar option names), and a detector that never fires on quiet speech
would produce exactly this.

**Near the threshold, it is also not reproducible.** Level is not a clean
cut-off: 16 other quiet clips transcribed, and in the second run a third clip
(`-000`, the *loudest* of the quiet group) went blank on every repetition,
after transcribing in the first. WER on the quiet clips moved from 26.56% to
33.54% between runs, while every other source reproduced exactly. Near the
detector's edge, small timing differences decide whether a line starts at all.

The part that matters for a product is the silence. An app cannot tell *"the
user said nothing"* from *"the model did not hear them"*, because both look
identical from the API. A real microphone with automatic gain control usually
delivers far hotter levels than these clips, so this may rarely happen live.
But a quiet talker or a distant phone is exactly the case where it would, and
the API gives no way to detect it.

### Moonshine runs inference inside `addAudio()`

`addAudioToStream()` does not just queue audio. When an update is due, the call
runs the model **before returning**. The time spent inside it is what
`rtf_sustained` measures here: about 40% of the audio duration for tiny, 72%
for small and 80% for medium. The SDK reports ~200 / 450 / 550 ms per
transcription pass by size. So Arm B's schedule slip is large on *every*
source, clean speech included: median 230 / 560 / 710 ms by size, where
Arm A's is 4–6 ms.

The two slip figures mean different things. For Arm A, slip is the recognizer
refusing input. For Arm B, it is our own thread busy computing. For tiny it
does not accumulate. After each blocking pass, the feeder releases the frames
it owes back to back, so by the end of a clip it is back on schedule (final
slip: 2 ms median). For small and medium it no longer catches up: final slip
is 60–290 ms median, and that lag goes straight into final latency.

The practical consequence: a live app must not call `addAudio()` from the
thread reading `AudioRecord`. A stall of that size on the capture thread risks
overrunning the capture buffer and dropping audio. Capture and inference need
separate threads with a queue between them.

### Moonshine 0.1.5 has no thread-count setting

The plan fixes every arm at 4 inference threads (§6). Arm B cannot be held to
that. `Transcriber`'s public API has no thread parameter, and none of the
option names in `libmoonshine.so` concerns threads. The only thread control in
the library is an environment variable, `MOONSHINE_ORT_SINGLE_THREAD`, which
forces one thread. Otherwise ONNX Runtime's default pool applies.

So Arm B rows record `threads: 4` because the harness passes it, but the value
is nominal. The planned 2/4/6/8 thread sweep is not possible on this arm;
only 1 vs default is. This is the kind of runtime difference D6 anticipated.
Arm B is measured as the deployable stack it is, and its thread behaviour
should not be read as a property of the model.

### FLEURS English is recorded 40 dB quieter than FLEURS Spanish

FLEURS was picked so that English and Spanish would share "the same corpus,
recorded the same way". On loudness, they do not:

| Source | Median RMS | Range | Clips below −45 dBFS |
|---|---|---|---|
| FLEURS `en_us` | **−62.6 dBFS** | −66.6 … −26.1 | **18 / 20** |
| FLEURS `es_419` | −22.2 dBFS | −30.8 … −18.5 | 0 / 20 |
| LibriSpeech `test-other` | −23.6 dBFS | −33.0 … −17.5 | 0 / 20 |

Peaks on the quiet English clips sit around −45 dBFS, which uses only a few of
the 16 bits available. This is **inherited from the source, not introduced by
the build**. Measuring the original parquet bytes gives the same figures, and
`build_corpus.py` only resamples. §8's "normalise every file" meant format
(16 kHz mono PCM16), never loudness.

It went unnoticed through Phase 1 because Arm A copes: it scores 9.69% on
these clips. It surfaced in Phase 2 because Moonshine does not: see
[the finding above](#moonshine-returns-no-text-and-no-error-on-some-quiet-clips).

**The fix, and what it showed.** The corpus now carries `fleurs_en_norm`, a
copy of the 20 English clips, each lifted by one static gain (+3.1 to
+43.6 dB) to −23 dBFS RMS, the level of the other two sources. A static gain,
not loudness normalisation or compression, so each copy is exactly the audio
the arms already heard, only louder. Both arms were re-run over originals and
copies together:

| Same 20 clips | Original (very quiet) | Level-matched |
|---|---|---|
| Arm A WER | **9.69%** | 12.60% |
| Arm A WER, without clip `-012` | 10.47% | **8.22%** |
| Moonshine tiny WER | 30.05% | **11.25%** |
| Moonshine tiny, rows with no text | 15 of 120 | **0 of 60** |

Level was most of Moonshine's clean-English problem: WER fell by nearly two
thirds and the blank outputs disappeared. Arm A also improved on 19 of 20
clips. Its aggregate got worse only because of one clip where, at the higher
level, the recognizer returned just the last clause
([finding](#the-platform-recognizer-can-return-only-the-last-clause)).

What it undermined:

- **Phase 1's "Spanish is more accurate than English"** compared audio 40 dB
  apart. At matched level the gap depends on a single clip (see
  [Results](#three-things-worth-stopping-on)), so it is not established.
- **Arm B's clean-English WER** on the original clips mixes level robustness
  with recognition accuracy. The level-matched figure is the one to compare.
- **The LibriSpeech comparison was never affected.** Both arms saw
  normal-level audio there.

The rebuild that added the copies reproduced all 124 existing corpus files
byte for byte, which is also the first end-to-end check that
`build_corpus.py` is deterministic.

A related provenance gap: FLEURS's `id` column is a *sentence* id, and each
sentence exists as several speakers' recordings, sometimes wildly different in
level (for `-017`, −21.6 dBFS in one recording and −59.4 in the one selected).
The manifest's `source_id` therefore does not pin which recording a clip came
from. The build is still deterministic at the pinned dataset revision, so it
rebuilds identically, but `source_id` alone is not an identifier.

### The platform recognizer can return only the last clause

On one level-matched clip, `fleurs-en-norm-short-012`, Arm A's final result
contained only the sentence's last clause, on all four repetitions:

```
reference:  traffic flow is the study of the movement of individual drivers and
            vehicles between two points and the interactions they make with one another
final:      And the interactions they make with one another
```

The same sentence at its original (quiet) level transcribes perfectly, every
time. The partial results had shown the full sentence first: 25 words were
rewritten on the way to that final (against 9 on the quiet original). The
final starts with a space, the way a continuation segment is formatted. That
suggests the recognizer split the utterance internally and reported only the
last segment. That reading is an inference; what was delivered is a fact.

For anyone building on `SpeechRecognizer`: the final result is not guaranteed
to contain everything the partials showed. An app that replaces its display
with each result, as this harness does and as most sample code does, can lose
most of an utterance. Accumulating across segments guards against it. So does
the segmented-session mode the harness uses for continuous speech.

It is one clip in eighty. It also decided the English-vs-Spanish comparison
(see [Results](#three-things-worth-stopping-on)), which is why it gets a
section.

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

*Postscript (Phase 2).* The example numbers above come from Phase 1's English
run, whose timing did not reproduce: re-run, the same recognizer showed 6 ms
of slip on the same noisy clips (see [Reproducibility](#reproducibility)). The
rule stands. The illustration shows what that one session did, not what the
recognizer always does.

### "End of speech" was stamped up to one frame early

Final latency means *time from the speaker stopping to the text being final*.
Through Phase 1 and Arm B's first run, the harness stamped "the speaker
stopped" at the moment the paced feeder returned. That is not the same moment.

The feeder releases each 100 ms frame at the **start** of its window, so with
no slip it hands over the last frame, and returns, up to one frame before the
audio actually ends. The stamp was early, and every final latency was
**overstated** by that gap. A feeder running behind schedule pushes the stamp
the other way. So the error depended on slip, and could go in either
direction.

The prediction before measuring was that Moonshine, with its 250 ms median
slip, would be *understated*. For tiny that was wrong: the feeder catches up
by the end of each clip, and the early frame dominates. For the larger
variants it was right, because their passes are long enough that the feeder
is still behind when the audio runs out. Measured directly:

| | Old stamp vs actual audio end | Final text, old stamp | Final text, actual audio end |
|---|---|---|---|
| Arm A, LibriSpeech | 53 ms early | 120 ms | 68 ms |
| Arm A, FLEURS English | 57 ms early | 98 ms | 50 ms |
| Arm A, Spanish | 76 ms early | 27 ms | 0 ms |
| Moonshine tiny, LibriSpeech | 42 ms early | 102 ms | 84 ms |
| Moonshine small, LibriSpeech | **149 ms late** | 295 ms | 442 ms |
| Moonshine medium, LibriSpeech | **239 ms late** | 446 ms | 662 ms |

So the error ran in both directions, by up to ~260 ms (medium on
level-matched FLEURS), and it would have flattered exactly the variants that
are slowest to finalise.

Both arms now record `final_after_audio_end_ms` (signed, against feed start +
audio duration) alongside the old field, and the headline uses it. The old
field is kept unchanged, so nothing published silently changes meaning.

The one-frame lead itself remains: every arm receives each frame up to 100 ms
before a microphone with 100 ms buffers would deliver it. It applies equally
to every arm, so comparisons are fair, but absolute latencies are slightly
optimistic.

### An empty transcript is every word missed, not a row to skip

The same mistake appeared a second time, in the scorer rather than the latency
code. It went unnoticed through all of Phase 1.

`summarize.py` built its scoring pairs only from rows with a non-empty
hypothesis. That looks like harmless defensiveness. It is not. Skipping a row
removes its reference words from the **denominator**, when they should be
counted as deletions. So an arm that heard nothing scored *better* for it.

Phase 2 exposed it. Moonshine tiny returned no text at all on 2 of 20 clean
English clips in its pilot: no error, no line, just silence. Its pilot WER was
**19.3% as scored and 26.9% in fact**. That gap alone could have decided
whether it looked competitive.

It had also touched a published number. One Arm A Spanish utterance ended in
`ERROR_CLIENT` before emitting any text, and correcting it moves Spanish from
7.73% to **8.27%**. Every row with a reference is now scored, and the summary
reports the count of blank rows next to the error count, so a blank row is
visible instead of silently vanishing.

### Live partials from an offline model multiply its compute

An offline model can look live by re-decoding the open utterance every few
hundred milliseconds and showing each result as partial text. This is
sherpa-onnx's own "simulate streaming" pattern, and it is what Arms D and E
do at a 500 ms cadence. What it costs was measured directly, because each
row records partial and final decode time separately:

| Share of real time | Finals only | With 500 ms partials |
|---|---|---|
| Parakeet | 0.20–0.23 | 0.52–0.60 |
| Whisper base | 0.27–0.30 | 0.76–0.88 |
| Whisper small | 0.52–0.59 | 1.24–1.36 |

Every partial decodes the whole utterance so far, from its start. A 6-second
sentence gets seven or eight partial decodes that grow toward its full
length, and then the final. For Whisper there is a second multiplier:
sherpa-onnx appends 1000 frames (10 s) of padding to every input so the
decoder finds its end-of-text token, so even a half-second partial costs a
10.5-second encoder pass.

The phone felt it. Whisper base reached the 35 °C gate six minutes into its
run, from 30.5 °C, and paused to cool seven times in 87 minutes, 29 minutes of
pauses in all. Whisper small reached it in four minutes and paused fifteen
times: about 78 of its 156 minutes were spent cooling, and it used 39 battery
points for 320 clips. Parakeet, doing less work per partial, never reached the
gate.

Above 1.0, as with Whisper small, partials stop being merely expensive. One
partial decode takes ~1.4 s, longer than the 500 ms interval, so the decode
thread never idles while someone speaks.

There is a latency cost too. A decode cannot be interrupted, so a final that
arrives while a partial is running waits for it: a median of 44–134 ms for
Parakeet, 152–376 ms for Whisper base and 790–1112 ms for Whisper small, and
at worst 0.6 s, 1.0 s and 3.6 s.

For a product, the partial cadence is a dial between how live the text looks
and how hot the phone gets. It deserves to be tuned per model, not left at a
default. The dial is `--partial-ms`, and 0 turns partials off.

### Silero VAD hears quiet speech, but late

Moonshine's built-in voice detection returned **no text at all** on some of
the very quiet FLEURS English clips
([finding](#moonshine-returns-no-text-and-no-error-on-some-quiet-clips)).
Silero VAD, in front of Arms D and E, did better: it found speech in every
one of the 20 quiet clips, in every repetition. No blank rows.

It does find it late. On the quiet clips both models miss the first word of
the sentence far more often than on the same clips at normal level:

| First reference word missing | Very quiet (original) | Level-matched |
|---|---|---|
| Parakeet | 11 / 20 | 3 / 20 |
| Whisper base | 12 / 20 | 4 / 20 |
| Whisper small | 8 / 20 | 3 / 20 |

```
reference:            It is thinner under the maria and thicker under the highlands.
Whisper, normal level:   is dinner under the maria and thicker under the highlands.
Whisper, very quiet:               under the maria and thicker under the highlands.
```

(Counts are per repetition; every repetition gave the same text.)

Three models failing the same way, and only on quiet audio, point at the
stage they share. The VAD dates a segment's start about 0.3 s before the
point where it declares speech. When a soft onset takes longer than that to
cross its threshold, the first syllables fall outside the segment and no
model ever hears them. That is part of the gap between Parakeet's quiet-clip
WER (14.69%) and its level-matched WER on the same 20 clips (6.88%). How
large a part was not separated.

The same caveat as for Moonshine applies: a phone microphone with automatic
gain delivers much hotter audio than these clips. But where it does not, a
live app loses the start of the sentence rather than the whole of it.

### Parakeet can return nothing for a tightly cut segment

Found when English grew to 100 clips per source. On the original 20 noisy
clips Parakeet scored 7.05%; on the 80 new ones, 9.91%. Moonshine medium,
on the same clips, moved from 6.24% to 7.22%. Reading Parakeet's worst clips
showed why: it was not mishearing words, it was dropping whole stretches.

```
reference:  annie stared vacantly at the cocoa then she uttered a laugh
Parakeet:   Annie stared vacantly at the cocoa.
reference:  for they are thy warmest friends and preceptors
Parakeet:   (nothing)
```

The VAD had found all the speech. In the first clip it cut two segments, at
a pause after "cocoa", and both were decoded. The second came back empty. In
the second clip there was one segment, partial text was on screen from 1.5 s,
and the final decode of that same audio returned nothing. Both happened
identically in both repetitions. On five noisy clips this cost about 30
edits, two WER points, which is most of the gap to Moonshine medium.

**It is the missing silence before the speech.** Silero VAD starts a segment
close to the speech onset, so Parakeet receives audio that begins almost
mid-syllable. Re-running the same pipeline on a PC (sherpa-onnx 1.13.8, the
same model files and VAD settings) reproduces the failure, and shows what
padding does:

| Parakeet, PC diagnostic | none | 0.5 s silence before | 0.5 s after | 0.5 s both |
|---|---|---|---|---|
| Noisy English, 100 clips | 9.47% | 7.12% | 12.09% | 6.85% |
| Clean English, 100 clips | 5.26% | 4.68% | 8.07% | 4.62% |
| Spanish, 100 clips | 4.13% | 4.02% | 4.13% | 4.19% |
| Segments decoded empty (all three) | 8 | 2 | 19 | 2 |

These are PC figures, not measurements of record. They are close to the
phone's (9.34%, 4.97%, 4.47%) but not byte-identical, since x86 and ARM
arithmetic differ slightly. Leading silence is the fix: 0.25, 0.5 and 1.0 s
all recover most of the loss. Trailing silence alone makes things worse.
Spanish, with longer and cleaner FLEURS clips, is barely affected.

**The published Parakeet numbers are left unpadded, on purpose.** The
harness decodes the VAD's segment as it comes, and so does sherpa-onnx's own
simulate-streaming Android app (`vad.front().samples`, v1.13.8). So 9.34%
is what the stack does as its authors ship it. With half a second of
padding, the PC diagnostic puts it level with Moonshine medium on noisy
English rather than with Moonshine small. Anyone building on this stack
should pad.

The cost of padding is compute: half a second more audio in every decode,
partials included. It was not measured on the phone.

For a product the worse half is what the user sees: text that was on screen
as a partial can disappear when the final arrives. The platform recognizer
does something similar
([finding](#the-platform-recognizer-can-return-only-the-last-clause)).

### Years written as digits cost four WER points

A third scoring bias, found in Phase 3, and again one that punished a
formatting choice rather than a recognition error.

The scorer spells out digits before comparing, because references spell
numbers out while some models write "42". It spelled "1848" as a plain
cardinal, "one thousand eight hundred forty eight". LibriSpeech's reference
reads it as a year: "eighteen forty eight". One noisy clip,
`ls-other-short-006`, carries three years:

```
reference:  ... born january fifteenth eighteen forty eight john in eighteen fifty one ...
Arm A:      ... born January 15 1848 John in 1851 ...
Moonshine small: ... born January fifteen, eighteen, forty eight. John in eighteen fifty one ...
```

Arm A and Whisper write years as digits, and took 13 errors per pass on this
clip's 22 words. Twelve of them came from the three years. Moonshine small and
medium spell years out, and took one. Across the 20 noisy clips that was
worth about four WER points, so the comparison on noisy English, the one the
case for bundling a model rests on, was skewed against exactly the arms that
use digits.

| Noisy English (LibriSpeech) | As published | Years read as years |
|---|---|---|
| Arm A | 33.33% | **29.31%** |
| Moonshine tiny | 15.77% | **14.43%** |
| Whisper base | 26.85% | **22.82%** |
| Moonshine small / medium | 8.72% / 6.26% | unchanged |

The scorer now reads an English four-digit number from 1100 to 1999 as a
year, and the self-test covers it. Spanish needs no change: it reads years as
cardinals. The remaining error on that clip is "15" against "fifteenth", which
is a genuine difference and stays.

The conclusions did not move. Moonshine still cuts Arm A's noisy-audio error
by half or more at every size. The numbers did, and the pattern is the one
behind the two earlier findings: a scorer rule that looks neutral can
quietly favour one output style.

**Five more formatting rules, the same week.** Scanning every new reference
for digits and symbols before measuring on them turned up more of the same
family:

| Written | Was read as | Now | Who it hurt |
|---|---|---|---|
| "12:00 GMT" | "doce cero gmt" | "doce gmt" | every arm alike |
| "20%" | "veinte" (the % stripped as punctuation) | "veinte por ciento" | **Arm A**, which writes "%" where FLEURS says "por ciento" |
| "10,000" / "10.000" | "ten zero" | "ten thousand" | whoever writes the digits |
| "M16" | "msixteen" | "m sixteen" | whoever writes "M sixteen" |
| "U.S." | "u s" | "us" | whoever writes "US" |

Each rule applies to reference and hypothesis alike, and the self-test covers
each. The percent sign mattered most. Arm A lost two words on each of two
Spanish clips, so its Spanish WER fell from 7.62% to 7.39% and Parakeet's from
4.64% to 4.47%; the gap between them barely moved. Tokenising digits and
letters apart also split Whisper base's "4x4, 3x3…" loop into more words,
raising its very-quiet English WER from 20.62% to 21.88%. That is the rule
counting a hallucination fully, not a new error.

### Twenty clips could not tell the arms apart

After Phase 3's first write-up, the README said Parakeet beats the platform
recognizer in Spanish, 6.98% against 8.14%. The numbers were right, but the
data could not support the claim. The Spanish set was 20 clips, 258
reference words. And these models give the same text on every repetition, so
four repetitions are still 20 clips of evidence, not 80. A paired bootstrap
over clips (`scripts/compare.py`) put the difference at about +1.5 points,
with a 95% interval of about −3 to +5. The two were indistinguishable.

The set was thinner than it looked. FLEURS records each sentence by several
speakers, so the 20 clips held 15 distinct sentences. Three were the same
sentence about Aerosmith, and one of those recordings (`-017`) defeated all
three offline models ("I just mean.", "Idres mi se lo") while Arm A got it.
In a 20-clip set, one recording like that is a twentieth of the evidence.

Phase 3 added 80 Spanish clips, one recording each of 80 sentences not
already used, and measured Arm A and Parakeet on all 100:

| Spanish WER | Original 20 clips (258 words) | All 100 clips (1,767 words) |
|---|---|---|
| Arm A | 7.75% | 7.39% |
| Parakeet | 6.20% | 4.47% |
| Difference, with 95% interval | +1.55 [−2.70, +5.18] | **+2.91 [+0.55, +5.81]** |

The 20-clip figures use the current scorer. The claim survives, now on
evidence that can carry it. The original 20 turned out to be the harder
clips for Parakeet: 6.20% there, 4.17% on the new 80.

Run over every other comparison the README makes, the same tool shows which
English claims 20 clips can support:

| English WER | A − B | 95% interval | |
|---|---|---|---|
| Arm A − Moonshine small, noisy | +20.6 | +9.1 to +34.3 | resolved |
| Moonshine small − Parakeet, noisy | +1.7 | −2.3 to +5.7 | not resolved |
| Moonshine medium − Parakeet, noisy | −0.8 | −5.5 to +4.1 | not resolved |
| Moonshine small − Parakeet, clean | +0.8 | −0.9 to +2.7 | not resolved |
| Whisper small − Parakeet, clean | −1.6 | −4.4 to +1.2 | not resolved |

The case for bundling a model at all rests on the first row, and it holds by
a wide margin. The ranking among the bundled English models did not hold: at
20 clips, English differences under about four points were noise.

So English got the same treatment as Spanish: 100 clips per source. On them,
the same comparisons read:

| English WER, 100 clips | A − B | 95% interval | |
|---|---|---|---|
| Arm A − Moonshine small, noisy | +18.2 | +12.9 to +23.7 | resolved |
| Moonshine small − Parakeet, noisy | −0.4 | −2.8 to +1.7 | not resolved |
| Moonshine medium − Parakeet, noisy | −2.3 | −5.1 to +0.2 | not resolved |
| Moonshine small − Parakeet, clean | +2.4 | +1.3 to +3.6 | **resolved** |
| Moonshine small − medium, clean | +2.7 | +1.5 to +3.9 | **resolved** |
| Moonshine small − medium, noisy | +1.9 | +0.0 to +3.8 | resolved, barely |

The 20-clip point estimates were not a guide. Parakeet looked 1.7 points
ahead of small on noisy speech and is level with it; on clean speech it
looked 0.8 points ahead and is 2.4 ahead, now resolved. The full table, and what it
means for the choice of model, is in
[English on 100 clips per source](#english-on-100-clips-per-source).

Two general lessons. Repeating a deterministic model adds evidence about its
timing, not its accuracy. And "X beats Y" needs an interval, not two pooled
numbers side by side. `scripts/compare.py --standard` re-checks every
comparison the README relies on.

### Two ONNX Runtimes in one APK collide at packaging

Moonshine's AAR and sherpa-onnx's regular AAR each ship
`lib/arm64-v8a/libonnxruntime.so`. With both in one app, the build fails:

```
Execution failed for task ':app:mergeDebugNativeLibs'
> 2 files found with path 'lib/arm64-v8a/libonnxruntime.so' from inputs:
   - .../sherpa-onnx-1.13.8/jni/arm64-v8a/libonnxruntime.so
   - .../moonshine-voice-0.1.5/jni/arm64-v8a/libonnxruntime.so
```

The usual fix is `jniLibs.pickFirsts`. It makes the build pass, and here it
is the wrong fix, because the two files are different builds of ONNX Runtime.
Moonshine's is 6.3 MB, a reduced build for its pre-optimised `.ort` models.
sherpa-onnx's is 22.2 MB, a full build. Keeping either one gives the other SDK
a runtime it was not built against. The best case is an operator missing at
model load. The worst is a silent change in what gets measured (D6).

sherpa-onnx publishes a second AAR,
`sherpa-onnx-static-link-onnxruntime-<version>.aar`, with ONNX Runtime linked
into `libsherpa-onnx-jni.so` for arm64-v8a and x86_64. It ships no
`libonnxruntime.so` for those ABIs, so nothing collides, and each stack runs
on its own runtime. That is the one the harness uses, pinned by hash
(`scripts/fetch_runtime.py`).

### sherpa-onnx's Kotlin VAD splits utterances after 5 seconds by default

`SileroVadModelConfig` in the Kotlin API defaults `maxSpeechDuration` to
5 s. The native library's own default is 20 s. It is not a hard cut. Once
open speech passes that length, the VAD raises its speech threshold to 0.9
and drops its minimum silence to 0.1 s, so the segment closes at the next
brief pause. With the Kotlin default, most of the corpus's 7–8 s sentences
would reach the model as two halves split at a breath, each transcribed
without the other's context.

The harness sets 20 s, and records the VAD configuration in every row.
Whisper's window is 30 s, so 20 s constrains neither model.

### The battery temperature an app can read can be minutes old

The thermal gate reads battery temperature, the only temperature this handset
exposes without root. Its thermal HAL reports no sensors to
`dumpsys thermalservice`, and shell may not read
`/sys/class/power_supply/battery/temp`. The value the app gets, from the
`ACTION_BATTERY_CHANGED` broadcast, changes in coarse, irregular steps: about
once every 80 seconds through the Moonshine medium run, and in Phase 3, once,
not at all for 5.5 minutes.

That stretch is what gave it away. During a cooling pause, the reading sat
at 35.2 °C for 5.5 minutes and then, in one 15-second step, read 31.2 °C. A
phone does not shed 4 °C in 15 seconds. The value had simply not been
updated. `dumpsys battery` reports the same cached value, so the host-side
check has the same lag.

Consequences:

- **The gate acts on a stale reading.** When heating, a clip can start
  above 35 °C. When cooling, the pause runs longer than it needs to. The
  43 °C abort has a wide enough margin that a few minutes of lag does not
  threaten it at these workloads, but it is a lag.
- **Every temperature in this README is a sample of a lagging signal.** The
  "peak temperature" figures in Phases 1–2 are the highest value the phone
  reported, not the highest the battery reached.

Why the refresh is so irregular was not established. Charge-level changes do
not explain it: most temperature updates came between them.

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
scripts/                 # fetch_corpus / build_corpus / fetch_models / fetch_runtime / run_bench / summarize / score
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
