# Phase 3 — Arms D and E: one model for both languages

**What was measured:** three offline models that cover English *and* Spanish,
made live with Silero VAD through sherpa-onnx 1.13.8, on a Snapdragon 870
(Xiaomi `M2012K11AG`, Android 13):

- **Arm D, Parakeet TDT 0.6b v3** (int8, 670 MB), the candidate for one model
  serving both languages.
- **Arm E, Whisper base and small** (int8, 161 and 375 MB), the well-known
  baseline that calibrates everything else.

**Why it matters:** this is the central question of the experiment. English
has a strong bundled option (Moonshine small, 8.7% WER on noisy speech) but no
Spanish one. Spanish has the free platform recognizer (7.62%), which fails on
noisy English (29%). Either one model serves both, or the app ships two
stacks.

Measured 2026-09-23 (Parakeet, Whisper base) and 2026-09-24 (Whisper small;
then Arm A and Parakeet again on an expanded, 100-clip Spanish set). Full
method in the
[README](../README.md#arms-d-and-e-offline-models-made-live-both-languages).

> **Revised in Phase 3.** The first version of this summary said Parakeet
> beats Arm A in Spanish, 6.98% against 8.14% on 20 clips. Those 20 clips
> could not support it: the 95% interval on the difference ran from −3.2 to
> +4.9 points. On 100 clips they can, and it holds: 4.64% against 7.62%. See
> [the finding](../README.md#twenty-clips-could-not-tell-the-arms-apart). A
> scoring fix for clock times also lowered every Spanish figure by ~0.35
> points.

---

## Results

Audio is file-fed and paced to the wall clock, so every arm hears identical
input. Four repetitions of 80 clips per model, repetition 0 discarded, plus
the 100-clip Spanish runs. WER counts each clip once; latency is the median,
measured from the moment the audio ends.

| | Arm A (0 MB) | Moonshine small (224 MB) | Moonshine medium (416 MB) | Whisper base (161 MB) | Whisper small (375 MB) | **Parakeet (670 MB)** |
|---|---|---|---|---|---|---|
| WER, **Spanish**, 100 clips (FLEURS) | 7.62% | — | — | — | — | **4.64%** |
| WER, Spanish, original 20 clips | 7.78% | — | — | 14.79% | 11.28% | **6.61%** |
| WER, English **noisy** (LibriSpeech `test-other`) | 29.31% | 8.72% | **6.26%** | 22.82% | 14.43% | 7.05% |
| WER, English clean (FLEURS, level-matched) | 12.60% | 7.71% | 5.62% | 11.88% | **5.31%** | 6.88% |
| WER, English clean but **very quiet** | **9.69%** | 27.29% | 25.31% | 20.62% | 10.31% | 14.69% |
| Time to **first** text, noisy / clean / Spanish | 1.0 / 1.3 / 2.0 s | 1.2 / 1.7 / — | 1.2 / 1.3 / — | 1.5 / 1.7 / 2.3 s | 2.2 / 2.3 / 3.0 s | 1.6 / 1.8 / 2.4 s |
| Time to **final** text, noisy / clean / Spanish | **68 / 76 / 0 ms** | 442 / 144 / — | 662 / 414 / — | 678 / 618 / 471 ms | 2424 / 2683 / 2750 ms | 353 / 186 / 0 ms |
| Share of real time spent computing | — | 0.72 | 0.80 | 0.76–0.88 | 1.24–1.36 | 0.52–0.60 |
| Peak memory | — | 605 MB | 933 MB | 572 MB | 997 MB | 1027 MB |
| Battery per 240 clips | 3 points | 10 points | 11 points | 15 points | 29 points | 10.5 points |

Arm A's English and all Moonshine figures are from Phases 1–2, with the
noisy-English scoring correction made in this phase. Arm A's Spanish includes
its 100-clip re-measure.

---

## What the numbers say

### Parakeet serves both languages

Parakeet is the first model in the matrix that is strong in both languages at
once. In Spanish it beats the platform recognizer: 4.64% against 7.62% on 100
clips, a 3.0-point gap whose 95% interval (+0.6 to +5.9) excludes zero.
Nothing else measured so far does. In English it is level with Moonshine
small and medium, on noisy speech (7.05%) and on clean (6.88%); none of those
differences is resolvable on 20 clips. It detects the language by itself; it
was given no hint.

### The memory risk did not materialise

Parakeet was the arm expected to fail on memory, with 1.5–2 GB resident
forecast on a phone that has about 2 GB free. It peaked at 1027 MB, about
100 MB more than Moonshine medium, loaded in 2.6 s, and ran 320 clips with no
error. Its real cost is disk: 670 MB, three times Moonshine small.

### It is the cheapest of the three to run

Parakeet spends about half of real time computing. Whisper base spends over
three quarters, and Whisper small more than all of it. Parakeet never reached
the phone's 35 °C gate. Whisper base paused to cool seven times; Whisper small
paused fifteen times and spent half its run cooling. Parakeet also used the
least battery of the three.

### Showing live text multiplies the compute

None of these models is streaming, so live text comes from re-decoding the
open sentence every half second. The final decodes alone need 0.20–0.59 of
real time. The partials bring that to 0.52–1.36, two to three times as much.
How often to refresh partial text is a trade between how live the app looks
and how hot the phone gets.

### Final text is fast; first text is slow

Parakeet's final text arrives 0–350 ms after the speaker stops, and in
Spanish it is already there when the audio ends, just like Arm A's. First text
is the weak point for all three: 1.5–3.0 s, the slowest in the matrix. The
voice detector must first be sure someone is speaking, the harness then waits
one refresh interval (500 ms), and the first decode has to finish.

### Whisper is a baseline, not a candidate

Whisper base has nearly twice Arm A's error in Spanish (14.79% against 7.78%
on the same 20 clips), is level with Arm A on clean English, and is far behind
every bundled model on noisy English. On one clip it looped ("4x4, 3x3, 3x4, 3x4…") instead of
transcribing, in every repetition.

Whisper small is the most accurate model measured on clean English (5.31%),
and holds up on very quiet audio better than any other bundled model
(10.31%). Its Spanish (11.28%) is well behind Parakeet's, and it is too slow
to feel live: final text arrives 2.4–2.8 s after the speaker stops.

### Quiet speech loses its first word

The voice detector found speech in every very quiet clip. Moonshine's own
detector had returned nothing on some of them. But it fires late, and all
three models then miss the first word on 8–12 of 20 quiet clips, against 3–4
at normal level.

---

## Verdict

**One model for both languages is viable, and it is Parakeet.** It is the
only arm that beats Arm A in Spanish, measurably, while matching the Moonshine
models in English, including on noisy speech. The price is 670 MB on disk, 1 GB of
memory, and first text that takes about two seconds to appear.

| | Verdict |
|---|---|
| Spanish | **Parakeet** (4.64%), ahead of Arm A (7.62%) on 100 clips; the gap is resolved. |
| Noisy English | **Moonshine small or medium, or Parakeet** (8.7 / 6.3 / 7.1%). 20 clips cannot rank them. |
| One model for both | **Parakeet.** Nothing else is competitive in both. |
| Memory | **Fits.** 1.03 GB peak, no failures. |
| Responsiveness | **Final text good, first text slow** (1.6–2.4 s). |
| Very quiet audio | **Arm A** is still the most robust; Whisper small comes close. |
| Whisper | **Calibration only.** Base is inaccurate; small is accurate in English but ~2.7 s behind the speaker. |

The alternative is two stacks: Moonshine small for English (224 MB) plus Arm A
for Spanish (0 MB). That is smaller and shows text faster, but it depends on a
Spanish language pack the user may not have installed. Phase 4 prices the
cheap Spanish option (Moonshine `base-es`) before the final call.

---

## Known limits of these numbers

- **English rankings are within noise.** Twenty clips per English source
  resolve Arm A against the bundled models, but not the bundled models
  against each other. Spanish now has 100 clips; English does not yet.
- **Per clip, not sustained.** No 5–10 minute sessions yet. Parakeet's worst
  clip used 85% of real time with partials.
- **The partial cadence is one setting.** Every 500 ms, matching Moonshine.
  The final-only latency is estimated from the logs, not measured.
- **Whisper small ran on a different morning** from the other two, after a
  recharge. Its accuracy is deterministic, so that does not matter for WER. Its
  timing and heat reflect that morning's conditions.
- **Temperatures lag.** The only temperature the phone exposes can be minutes
  old ([finding](../README.md#the-battery-temperature-an-app-can-read-can-be-minutes-old)).
- **One phone.** Timing and memory are properties of this handset.
