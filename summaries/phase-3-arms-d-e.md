# Phase 3 — Arms D and E: one model for both languages

**What was measured:** two offline models that cover English *and* Spanish,
made live with Silero VAD through sherpa-onnx 1.13.8, on a Snapdragon 870
(Xiaomi `M2012K11AG`, Android 13):

- **Arm D, Parakeet TDT 0.6b v3** (int8, 670 MB), the candidate for one model
  serving both languages.
- **Arm E, Whisper base** (int8, 161 MB), the well-known baseline that
  calibrates everything else.

**Why it matters:** this is the central question of the experiment. English
has a strong bundled option (Moonshine small, 8.7% WER on noisy speech) but no
Spanish one. Spanish has the free platform recognizer (8.14%), which fails on
noisy English (29%). Either one model serves both, or the app ships two
stacks.

Measured 2026-09-23. Whisper small (375 MB), the other Arm E variant, is not
measured yet: the phone's battery budget ran out. Full method in the
[README](../README.md#arms-d-and-e-offline-models-made-live-both-languages).

---

## Results

Audio is file-fed and paced to the wall clock, so every arm hears identical
input. Four repetitions of 80 clips per model, repetition 0 discarded. WER is
pooled; latency is the median, measured from the moment the audio ends.

| | Arm A (0 MB) | Moonshine small (224 MB) | Moonshine medium (416 MB) | Whisper base (161 MB) | **Parakeet (670 MB)** |
|---|---|---|---|---|---|
| WER, **Spanish** (FLEURS) | 8.14% | — | — | 15.12% | **6.98%** |
| WER, English **noisy** (LibriSpeech `test-other`) | 29.31% | 8.72% | **6.26%** | 22.82% | 7.05% |
| WER, English clean (FLEURS, level-matched) | 12.60% | 7.71% | **5.62%** | 11.88% | 6.88% |
| WER, English clean but **very quiet** | **9.69%** | 27.29% | 25.31% | 20.62% | 14.69% |
| Time to **first** text, noisy / clean / Spanish | 1.0 / 1.3 / 2.0 s | 1.2 / 1.7 / — | 1.2 / 1.3 / — | 1.5 / 1.7 / 2.3 s | 1.6 / 1.8 / 2.4 s |
| Time to **final** text, noisy / clean / Spanish | **68 / 76 / 0 ms** | 442 / 144 / — | 662 / 414 / — | 678 / 618 / 471 ms | 353 / 186 / 0 ms |
| Share of real time spent computing | — | 0.72 | 0.80 | 0.76–0.88 | 0.52–0.60 |
| Peak memory | — | 605 MB | 933 MB | 572 MB | 1027 MB |
| Battery per 240 clips | 3 points | 10 points | 11 points | 15 points | 10.5 points |

Arm A and Moonshine figures are from Phases 1–2, with the noisy-English
scoring correction made in this phase.

---

## What the numbers say

### Parakeet serves both languages

Parakeet is the first model in the matrix that is strong in both languages at
once. In Spanish it beats the platform recognizer, 6.98% against 8.14%, and
nothing else measured so far does. In English it sits between Moonshine small
and medium, on noisy speech (7.05%) and on clean (6.88%). It detects the
language by itself; it was given no hint.

### The memory risk did not materialise

Parakeet was the arm expected to fail on memory, with 1.5–2 GB resident
forecast on a phone that has about 2 GB free. It peaked at 1027 MB, about
100 MB more than Moonshine medium, loaded in 2.6 s, and ran 320 clips with no
error. Its real cost is disk: 670 MB, three times Moonshine small.

### It is cheaper to run than Whisper base, at eight times the size

Parakeet spends about half of real time computing. Whisper base spends over
three quarters, and in the process reached the phone's 35 °C gate six minutes
in, pausing to cool seven times. Parakeet never reached the gate, and used a
third less battery.

### Showing live text triples the compute

Neither model is streaming, so live text comes from re-decoding the open
sentence every half second. The final decodes alone need 0.20–0.30 of real
time. The partials bring that to 0.52–0.88. How often to refresh partial text
is a trade between how live the app looks and how hot the phone gets.

### Final text is fast; first text is slow

Parakeet's final text arrives 0–350 ms after the speaker stops, and in
Spanish it is already there when the audio ends, just like Arm A's. First text
is the weak point: 1.6–2.4 s, the slowest in the matrix. The voice detector
must first be sure someone is speaking, the harness then waits one refresh
interval (500 ms), and the first decode has to finish.

### Whisper base is a baseline, not a candidate

Nearly twice Arm A's error in Spanish (15.12%), level with Arm A on clean
English, and far behind every bundled model on noisy English. On one clip it
looped ("4x4, 3x3, 3x4, 3x4…") instead of transcribing, in every repetition.

### Quiet speech loses its first word

The voice detector found speech in every very quiet clip. Moonshine's own
detector had returned nothing on some of them. But it fires late, and both
models then miss the first word on 11–12 of 20 quiet clips, against 3–4 at
normal level.

---

## Verdict

**One model for both languages is viable, and it is Parakeet.** It is the
only arm that beats Arm A in Spanish while matching the Moonshine models in
English, including on noisy speech. The price is 670 MB on disk, 1 GB of
memory, and first text that takes about two seconds to appear.

| | Verdict |
|---|---|
| Spanish | **Parakeet** (6.98%), ahead of Arm A (8.14%). |
| Noisy English | **Moonshine medium or Parakeet** (6.3% / 7.1%), then Moonshine small (8.7%). |
| One model for both | **Parakeet.** Nothing else is competitive in both. |
| Memory | **Fits.** 1.03 GB peak, no failures. |
| Responsiveness | **Final text good, first text slow** (1.6–2.4 s). |
| Very quiet audio | **Arm A** is still the most robust. |
| Whisper | **Calibration only.** Base is not competitive. Small is pending. |

The alternative is two stacks: Moonshine small for English (224 MB) plus Arm A
for Spanish (0 MB). That is smaller and shows text faster, but it depends on a
Spanish language pack the user may not have installed. Phase 4 prices the
cheap Spanish option (Moonshine `base-es`) before the final call.

---

## Known limits of these numbers

- **Whisper small is not measured.** It is fetched, pinned and validated on
  the emulator, and waits for a charged phone.
- **Per clip, not sustained.** No 5–10 minute sessions yet. Parakeet's worst
  clip used 85% of real time with partials.
- **The partial cadence is one setting.** Every 500 ms, matching Moonshine.
  The final-only latency is estimated from the logs, not measured.
- **Temperatures lag.** The only temperature the phone exposes can be minutes
  old ([finding](../README.md#the-battery-temperature-an-app-can-read-can-be-minutes-old)).
- **One phone.** Timing and memory are properties of this handset.
