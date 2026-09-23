# On-device streaming ASR on a mid-range Android phone

An experiment: **can one speech-to-text model serve both English and Spanish at
acceptable live latency on a 2021 mid-range phone — or do we ship a different
model per language?**

Everything runs on-device. Live, as the user speaks. No network.

This repo is the lab notebook, not the final report. It was made public before
any results existed, and the results table below grows as phases complete.
Negative results stay in.

**Status:** Phases 0–2 complete. Arm B (Moonshine streaming English) is
measured at all three sizes; see the [Phase 2 summary](summaries/phase-2-arm-b.md).
Arm A was re-measured in Phase 2 on a corrected harness and corpus; several
Phase 1 figures were revised, and the revisions are marked where they occur.
Arms C–E not started.

---

## Contents

| Section | What's in it |
|---|---|
| [Why this is not obvious](#why-this-is-not-obvious) | Why live ASR is a different problem from batch ASR, and the English/Spanish asymmetry the experiment exists to price |
| [Hardware under test](#hardware-under-test) | The phone, its SoC, and why no published number comes from an emulator |
| [The matrix](#the-matrix) | The five arms, with current status per arm |
| [**Results**](#results) | **The measured numbers.** Plus [the three things worth stopping on](#three-things-worth-stopping-on), [reproducibility](#reproducibility), why [`rtf_sustained` is blank for Arm A](#rtf_sustained-is-blank-for-arm-a-and-slip-does-not-stand-in-for-it), the [decision gate](#decision-gate-planmd-10-phase-1), and [Arm B: Moonshine](#arm-b-moonshine-streaming-english) |
| [Metrics](#metrics) | What is measured and why RTF alone would mislead |
| [Method](#method-paced-file-fed-streaming) | Paced file-fed streaming — the one implementation detail everything rests on |
| [Corpus](#corpus) | How the audio was built, and the concatenation trick for scored continuous speech |
| [Reproducing](#reproducing) | Commands to rebuild the corpus, fetch models and run the harness |
| [**Findings and dead ends**](#findings-and-dead-ends) | **The useful part** — see the table below |
| [Model licences](#model-licences) | What each arm's weights permit, including one that blocks shipping |
| [Repo layout](#repo-layout) | Where everything lives |
| [A note on the test device](#a-note-on-the-test-device) | The safety policy, and why disabling thermal throttling is refused twice over |
| [summaries/](summaries/) | One short write-up per completed phase — [Phase 1: Arm A](summaries/phase-1-arm-a.md) (with Phase 2's revisions marked) and [Phase 2: Arm B](summaries/phase-2-arm-b.md) |
| [HANDOVER.md](HANDOVER.md) | State, commands and constraints for running the next phase in a fresh session |

### Findings index

Negative results included on purpose. Several of these are not documented
anywhere else.

| # | Finding | Kind |
|---|---|---|
| 1 | [Moonshine has no deployable Spanish streaming model](#moonshine-has-no-deployable-spanish-streaming-model) | Ecosystem gap |
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
| 19 | [Accuracy reproduced; one session's timing did not](#reproducibility) | Measurement integrity |
| 20 | [Excluded before testing](#excluded-before-testing) | Scope decisions |

> **New here?** Start with the [Phase 2 summary](summaries/phase-2-arm-b.md)
> and the [Phase 1 summary](summaries/phase-1-arm-a.md) for results without the
> process. Then [Findings](#findings-and-dead-ends) for
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
| D | Parakeet TDT 0.6b v3 int8 (VAD-segmented) | no | ✅ | ✅ | 670 MB | sherpa-onnx | One-model-for-both candidate | ⬜ not started |
| E | Whisper small + base int8 (chunked) | no | ✅ | ✅ | 375 / 161 MB | sherpa-onnx | Known baseline / calibration | ⬜ not started |

Arm A decides whether bundling a model is justified at all. If the platform
recognizer is good enough on this audio, that is a legitimate and
money-saving result — which is why it is built first.

## Results

Every number comes from the Snapdragon 870: file-fed, paced to the wall
clock, phone unplugged, never above 31 °C. Repetition 0 is discarded, and
figures are medians over all remaining rows.

**How runs are combined.** Accuracy reproduces across runs. Arm A scored an
identical 9.69% on FLEURS English in two runs seven hours apart. So WER and CER
are **pooled over every valid run**. Timing did not always reproduce (see
[Reproducibility](#reproducibility)), so latency comes from the **Phase 2
runs**: made back to back on the afternoon of 2026-09-23, both arms, with the
corrected harness.

**Final text** is measured from the actual end of the audio, clamped at zero:
text already final when the audio ended counts as 0 ms of waiting. Phase 1
measured it from when the feeder returned, which ran up to one frame early (see
[finding](#end-of-speech-was-stamped-up-to-one-frame-early)).

### Arm A: the platform recognizer (0 MB)

| Lang | Source | Audio | WER | CER | Final text | First text | Revisions | Slip median | `peak_rss_mb` |
|---|---|---|---|---|---|---|---|---|---|
| es | FLEURS `es_419` | clean | **8.14%** | 2.84% | 0 ms | 2009 ms | 6 | 6 ms | 124 |
| en | FLEURS `en_us` | clean, level-matched | **12.60%** | 8.78% | 76 ms | 1259 ms | 8 | 6 ms | 118 |
| en | FLEURS `en_us` | clean, original (**very quiet**) | 9.69% | 4.36% | 50 ms | 1212 ms | 8 | 6 ms | 118 |
| en | LibriSpeech `test-other` | **noisy** | **33.33%** | 25.92% | 68 ms | 1012 ms | 8 | 6 ms | 118 |

Spanish is pooled over three runs (180 rows, 2322 reference words), the two
original English sources over two runs (120 rows each), and the level-matched
clips come from one run (60 rows). `peak_rss_mb` is our harness only; the
recognizer runs in Google's process.

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

### Three things worth stopping on

**1. Accuracy collapses on noisy audio, and that reproduces.** 33.33% WER on
LibriSpeech `test-other` (33.45% and 33.22% in two runs), with CER at 26%.
Roughly one word in three is wrong in conditions resembling an ordinary room.
That failure is the case a bundled model would exist to fix.

**2. "Spanish is more accurate than English" is not established.** Phase 1
reported 7.73% vs 9.69% on "the same corpus, recorded the same way". Neither
half survived. The English clips turned out to be ~40 dB quieter, and Spanish
was corrected to 8.14%. At matched level, English scores 12.60%, but that
figure is dominated by one clip where the recognizer returned only the last
clause ([finding](#the-platform-recognizer-can-return-only-the-last-clause)).
On the other 19 clips, level-matched English scores 8.22%. So the language gap
is either large or nil, depending on a single utterance.

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
| WER, LibriSpeech | 33.45% | 33.22% |
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
speech, but 33% WER on noisy English is disqualifying for anything used in an
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
phone as Arm A. English only, because no Spanish streaming `.ort` exists (see
[Findings](#moonshine-has-no-deployable-spanish-streaming-model)).

| Variant | Source | Audio | WER | CER | Final text | First text | Revisions | `rtf_sustained` | `peak_rss_mb` |
|---|---|---|---|---|---|---|---|---|---|
| tiny | FLEURS `en_us` | clean, level-matched | 11.25% | 5.76% | 0 ms | 1064 ms | 16 | 0.423 | 363–475 |
| tiny | FLEURS `en_us` | clean, original (**very quiet**) | 30.05% | 23.05% | 0 ms | 1558 ms | 8 | 0.378 | |
| tiny | LibriSpeech `test-other` | **noisy** | 15.77% | 7.44% | 84 ms | 1058 ms | 10 | 0.404 | |
| small | FLEURS `en_us` | clean, level-matched | 7.71% | 3.65% | 144 ms | 1655 ms | 8 | 0.734 | 605 |
| small | FLEURS `en_us` | clean, original (**very quiet**) | 27.29% | 23.41% | 0 ms | 1675 ms | 8 | 0.667 | |
| small | LibriSpeech `test-other` | **noisy** | 8.72% | 3.27% | 442 ms | 1191 ms | 8 | 0.723 | |
| medium | FLEURS `en_us` | clean, level-matched | 5.62% | 2.27% | 414 ms | 1285 ms | 7 | 0.797 | 933 |
| medium | FLEURS `en_us` | clean, original (**very quiet**) | 25.31% | 22.78% | 246 ms | 1251 ms | 6 | 0.763 | |
| medium | LibriSpeech `test-other` | **noisy** | 6.26% | 2.83% | 662 ms | 1241 ms | 8 | 0.797 | |

Disk: tiny 77.7 MB · small 224.1 MB · medium 416.0 MB. Tiny is pooled over
two runs (LibriSpeech gave 15.77% in both); small and medium are one
4-repetition run each. A one-repetition medium pilot (6.71% / 5.94%) agreed
with the full run and is kept in `results/superseded/`. `peak_rss_mb` is a
process high-water mark that includes the harness's own heap. It bounds each
model's footprint from above, and it is not comparable to Arm A's.

**The size curve, English, against Arm A:**

| | Arm A (0 MB) | tiny (78 MB) | small (224 MB) | medium (416 MB) |
|---|---|---|---|---|
| WER, **noisy** | 33.33% | 15.77% | 8.72% | **6.26%** |
| WER, clean, level-matched | 12.60% | 11.25% | 7.71% | **5.62%** |
| WER, clean, **very quiet** | **9.69%** | 30.05% | 27.29% | 25.31% |
| First text, noisy / clean | 1012 / 1259 ms | 1058 / 1064 ms | 1191 / 1655 ms | 1241 / 1285 ms |
| Final text, noisy / clean | **68 / 76 ms** | 84 / 0 ms | 442 / 144 ms | 662 / 414 ms |
| `rtf_sustained` (worst clip) | — | 0.40 (0.61) | 0.72 (0.94) | 0.80 (0.95) |
| SDK time per model pass | — | ~200 ms | ~450 ms | ~550 ms |
| Peak RSS | 118 MB² | 363–475 MB | 605 MB | 933 MB |
| Battery per 240 clips | 3 pts | 6 pts | 10 pts | 11 pts |
| Peak temperature | 29.2 °C | 30.7 °C | 33.5 °C | **35.0 °C** |

² Our harness only; the recognizer's own memory is in Google's process.

**What Arm B answers:**

1. **Does Moonshine beat Arm A on noisy English? Yes, at every size.** Tiny
   halves Arm A's WER, small cuts it to about a quarter (8.72% vs 33.33%), and
   medium to under a fifth (6.26%). Small's CER on noisy speech is 3.27% against
   Arm A's 25.92%. That noisy-audio failure was the main reason to consider
   bundling a model, and it is fixed.
2. **The size curve bends at small.** Going from tiny to small nearly halves
   WER on noisy audio (15.77% → 8.72%) and cuts it by a third on clean
   (11.25% → 7.71%), for +146 MB. Medium buys roughly two more points on
   each (6.26% and 5.62%), for +192 MB more, ~330 MB more resident memory,
   noticeably slower final text, and a phone running at the thermal gate.
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
| `short` | 20 clips × 3 sources, 3–8 s (mean 5.5–6.7 s), plus a level-matched copy of the English FLEURS clips | latency per utterance |
| `session` | 1 per language, ~6 min, 0.5–1.5 s gaps | sustained RTF, thermals |

Sources are deliberately symmetric: FLEURS `en_us` and `es_419` are the same
corpus recorded the same way in both languages, so an English-vs-Spanish
comparison is not confounded by domain or recording conditions. LibriSpeech
`test-other` adds the noisy-English stress case that FLEURS's clean read
speech does not cover.

Current build: 82 clips, 20.5 minutes total (en 723.3 s, es 505.1 s), all
16 kHz mono PCM16. Twenty of them are `fleurs_en_norm`: the English FLEURS
clips lifted by a static gain to −23 dBFS RMS, because the originals are
recorded ~40 dB quieter than the other sources
([finding](#fleurs-english-is-recorded-40-db-quieter-than-fleurs-spanish)).
The build is deterministic: adding them reproduced every existing file byte
for byte. Session clips are disjoint from short clips, so
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

# Arm B: push the pinned weights, then measure one variant per run
.venv/Scripts/python scripts/run_bench.py --device physical --push-models moonshine-tiny-en
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-tiny-en --langs en --reps 4 --no-push

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
