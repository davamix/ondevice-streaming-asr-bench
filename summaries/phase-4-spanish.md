# Phase 4 — Spanish, and the answer to the central question

**What was measured:** the two remaining Spanish options, against the two
already measured, on 100 Spanish clips, on a Snapdragon 870 (Xiaomi
`M2012K11AG`, Android 13):

- **Arm C, Moonshine `base-es`** (65 MB, non-commercial licence), the cheap
  Spanish option PLAN.md planned: a non-streaming model made live with Silero
  VAD, decoded by the Moonshine SDK.
- **Moonshine `small-streaming-es`** (122 MB, MIT), Moonshine's Spanish
  streaming model. The plan said it did not exist in a deployable form. It
  does, on the vendor's CDN rather than HuggingFace, and was added to Arm B.

Before that, the English models were re-measured on 100 clips per source,
because 20 could not rank them.

**Why it matters:** this is where PLAN.md §1 gets its answer. *Can one model
serve both English and Spanish at acceptable live latency on a mid-range
phone, or do we ship a different model per language?*

Measured 2026-09-24 and -25. Full method in the
[README](../README.md#spanish-across-the-matrix-and-the-answer-to-1).

> **Confirmed in Phase 5.** Over six continuous minutes Parakeet used 0.6 of
> real time with no drift, and Moonshine small and medium held their
> per-clip figures too. A real microphone gave identical text; timing figures
> here are ~0.2 s optimistic for live input, for every arm alike. Padding is
> now part of the recommendation, not an aside: unpadded, Parakeet dropped a
> whole sentence mid-session. See the
> [Phase 5 summary](phase-5-sessions-and-verdict.md).

---

## Results

Audio is file-fed and paced to the wall clock, so every arm hears identical
input. Repetition 0 is discarded. WER counts each clip once; latency is the
median. First text is given from the moment speech starts in the clip (see
below for why).

| Spanish, 100 clips | Arm A (0 MB) | Moonshine `small-es` (122 MB) | Arm C `base-es` (65 MB) | **Parakeet (670 MB)** |
|---|---|---|---|---|
| WER | 7.39% | 7.24% | 5.09% | **4.47%** |
| Time to **final** text | **0 ms** | **0 ms** | 2.5 s | **0 ms** |
| Time to **first** text, from speech onset | **0.71 s** | 0.74 s | 1.33 s | 0.98 s |
| Share of real time spent computing | — | **0.43** | 1.16 | 0.58 |
| Peak memory | — | 640 MB | 872 MB | 1027 MB |
| Battery per 240 clips | 4 points | 9 points | 28 points | 13 points |
| Licence | platform | MIT | **non-commercial** | CC-BY-4.0 |

Paired comparisons (95% intervals): Parakeet beats Arm A by 2.9 points (+0.6
to +5.8) and `small-es` by 2.8 (+1.3 to +4.3); Arm C beats `small-es` by 2.2
(+0.5 to +3.8). Arm C against Parakeet, `small-es` against Arm A, and Arm C
against Arm A are not resolved, the last only just.

| English, 100 clips per source | Arm A | Moonshine small (224 MB) | Moonshine medium (416 MB) | Parakeet (670 MB) |
|---|---|---|---|---|
| WER, clean (level-matched) | 9.72% | 7.36% | **4.71%** | 4.97% |
| WER, noisy | 27.09% | 8.92% | **7.02%** | 9.34% |

Medium beats small on both English sources (noisy only just). Parakeet beats
small on clean speech and is level with it on noisy; medium and Parakeet are
level on clean, and medium leads on noisy by 2.3 points, not resolved.

---

## What the numbers say

### Parakeet is the only Spanish option that is both accurate and live

It beats the platform recognizer and Moonshine's Spanish model by about three
points each, both resolved, and its final text is ready when the speaker
stops. That is about 40% fewer errors than any per-language option.

### Moonshine's Spanish streaming model is only as good as the free recognizer

7.24% against Arm A's 7.39%, not the 4.9% Moonshine publishes for it (on
different data, decoding whole utterances). It is the cheapest bundled stack
to run: under half of real time, 9 battery points per 240 clips, never above
31.7 °C. But in Spanish it buys nothing over the platform recognizer except
independence from a language pack.

### Arm C is accurate, but not live

Level with Parakeet at a tenth of the disk. But each final decode takes 2.2 s
and each partial 1.1 s, so the phone computes for longer than the audio
lasts, final text lands 2.5 s after the speaker stops, and the run paused to
cool eight times. Its licence forbids shipping it anyway. It was planned at
four repetitions and stopped after two: it cost far more battery than
expected, and its text was identical in both.

### Spanish is not slower to show text

Every arm looked 0.6–0.9 s slower to show text in Spanish, and Phase 1 read
that as a property of the Spanish recognizer. It is the audio: the Spanish
clips open with a median 1.3 s of silence, the English ones with 0.3–0.5 s,
and the harness timed first text from the start of the clip. Measured from
when speech starts, Arm A shows text in ~0.7 s in both languages.

### Parakeet's English is better than its number

On some tightly cut VAD segments Parakeet returns no text at all, which is
most of its noisy-English gap to Moonshine medium. Half a second of silence
before each segment recovers about two points in a PC diagnostic; on the
phone, the published number is the stack as sherpa-onnx ships it.

---

## Verdict

**One model can serve both languages live on this phone, and it is
Parakeet.** What one model buys is Spanish accuracy. What two models buy is
size, heat and slightly faster first text, not Spanish accuracy.

| Live stack | Disk | Spanish | English, clean / noisy | First text | Verdict |
|---|---|---|---|---|---|
| **Parakeet** | 670 MB | **4.47%** | 4.97 / 9.34% | 1.0–1.5 s | **One model for both.** Best Spanish, competitive English; ~1 GB of memory. |
| Moonshine small-en + small-es | 346 MB | 7.24% | 7.36 / 8.92% | 0.7–1.0 s | The small, cool, MIT option. Spanish no better than the platform's. |
| Moonshine medium-en + small-es | 538 MB | 7.24% | **4.71 / 7.02%** | 0.7–1.0 s | Best English, same Spanish. |
| Moonshine small-en + Arm A | 224 MB | 7.39% | 7.36 / 8.92% | 0.7–1.0 s | Depends on a language pack the user may not have. |

Arm C (accurate, not live, non-commercial) and Whisper (calibration only) are
out.

On this phone: ship Parakeet if ~670 MB of disk and ~1 GB of memory are
acceptable, and pad each VAD segment with leading silence. If size or heat
matters more than Spanish accuracy, ship Moonshine small-en + small-es.
Phase 5 checks this against 5–10 minute continuous sessions and a real
microphone before it becomes the final recommendation.

---

## Known limits of these numbers

- **Per clip, not sustained.** No continuous sessions yet for any bundled
  model. Parakeet uses ~0.6 of real time with partials; a hot 10-minute
  session is the obvious next test.
- **The padded Parakeet was not measured on the phone.** The two-point gain
  on noisy English comes from a PC reproduction of the pipeline.
- **One corpus per language.** Spanish is FLEURS read speech only; there is
  no noisy Spanish source to match LibriSpeech.
- **Speech onset is estimated** from each clip's energy, on the PC.
- **Arm C has two repetitions, not four**, and its result file is the
  checkpoint written when it was stopped. Its accuracy is unaffected; its
  timing rests on 106 rows.
- **One phone.** Timing and memory are properties of this handset.
