# Phase 2 — Arm B: Moonshine streaming, English

**What was measured:** Moonshine v2's streaming English models, tiny (78 MB),
small (224 MB) and medium (416 MB), running on-device through
`ai.moonshine:moonshine-voice:0.1.5` on a Snapdragon 870 (Xiaomi `M2012K11AG`,
Android 13). Measured against Arm A, the platform recognizer, on the same
audio, the same afternoon, with the same harness.

**Why it matters:** Arm A is free but gets about one word in three wrong on
noisy English. Arm B is the question of whether bundling a model fixes that,
at what size, and at what cost in latency and memory.

Measured 2026-09-23. English only, because Moonshine publishes no Spanish
streaming model in a deployable format. Full method and reproduction steps are
in the [README](../README.md).

---

## Results

Audio is file-fed and paced to the wall clock, so every arm hears identical
input. Repetition 0 is discarded. WER is pooled over all valid runs; latency
is the median, measured from the moment the audio ends.

| English | Arm A (0 MB) | tiny (78 MB) | small (224 MB) | medium (416 MB) |
|---|---|---|---|---|
| WER, **noisy** (LibriSpeech `test-other`) | 33.33% | 15.77% | 8.72% | **6.26%** |
| WER, **clean** (FLEURS, level-matched) | 12.60% | 11.25% | 7.71% | **5.62%** |
| WER, clean but **very quiet** (FLEURS, original) | **9.69%** | 30.05% | 27.29% | 25.31% |
| Time to **first** text, noisy / clean | 1012 / 1259 ms | 1058 / 1064 ms | 1191 / 1655 ms | 1241 / 1285 ms |
| Time to **final** text, noisy / clean | **68 / 76 ms** | 84 / 0 ms | 442 / 144 ms | 662 / 414 ms |
| Share of real time spent computing | — | 0.40 | 0.72 | 0.80 |
| Peak memory (process high-water mark) | — | 363–475 MB | 605 MB | 933 MB |
| Battery per 240 clips | 3 points | 6 points | 10 points | 11 points |

Four repetitions per variant (tiny twice), 60 English clips each: 20 noisy,
20 clean, and the same 20 clean clips at their original, very quiet level.

---

## What the numbers say

### Every size fixes the noisy-audio problem

Arm A's weak spot is noise: 33% WER on LibriSpeech `test-other`. Tiny halves
it, small cuts it to about a quarter, and medium to under a fifth (6.3%). Small's
character error rate on noisy speech is 3.3%, against Arm A's 26%. If the app
will be used anywhere other than a quiet room, a bundled model earns its
megabytes.

### The size curve bends at small

From tiny to small, WER nearly halves on noisy audio and falls by a third on
clean audio, for 146 MB more. From small to medium the gains shrink to a
point or two, for another 192 MB, about 330 MB more memory, slower final
text, and enough heat to reach the phone's 35 °C gate. On a 6 GB phone with ~2 GB free, that memory matters.

### Bigger models do not fall behind; they finish later

None of the three variants falls behind a live microphone: even medium
spends just under 80% of real time computing (95% on its worst clip). The cost of size shows up somewhere
else. Each model pass takes ~200 ms for tiny, ~450 ms for small and ~550 ms
for medium, and final text on noisy speech arrives 84, 442 and 662 ms after the
speaker stops. Arm A takes 68 ms. For Moonshine, the constraints
that bind are latency and memory, not throughput.

### Streaming-native does not mean faster text

Moonshine's model has only ~80 ms of lookahead, which suggested it would show
words almost as they are spoken. It does not: first text takes 1.0–1.7 s, no
faster than Arm A. The SDK runs the model on a fixed 0.5 s schedule, and the
first words appear on the second or third tick. That schedule is a tunable
default, not a property of the model, and it has not been tuned here.

### Quiet audio is Moonshine's blind spot

18 of the 20 clean English clips are recorded about 40 dB quieter than
everything else in the corpus. Arm A handles them fine. Every Moonshine size
returns **no text at all** on some of them: no error, no partial, just
silence. Lifting the same clips to a normal level makes the problem vanish.
Tiny's clean-English WER falls from 30% to 11%.

A phone microphone with automatic gain usually delivers far louder audio than
these clips, so this may rarely happen in practice. But when it does (a quiet
voice, a phone on the table), the app has no way to tell *"nothing was said"*
from *"nothing was heard"*.

### Clean speech at normal level: small and medium lead

Level-matched, tiny and Arm A are close (11.25% vs 12.60%), and Arm A's
figure is inflated by one clip where it returned only the last clause of the
sentence. Small (7.71%) and medium (5.62%) are clearly ahead of both.

---

## Verdict

**Moonshine small is the English candidate to beat.** It fixes Arm A's
noisy-audio failure outright (8.7% vs 33% WER), leads on clean speech too, and
keeps up with real time with room to spare, for 224 MB.

| | Verdict |
|---|---|
| Noisy English | **Moonshine, any size.** The reason to bundle a model. |
| Clean English | **Small or medium.** Tiny is only level with Arm A. |
| Responsiveness | **No win.** First text ~1–1.7 s for every arm; final text slows with size. |
| Very quiet audio | **Arm A.** Moonshine can return nothing, silently. |
| Size | **Small is the knee.** Medium buys ~2 points for +192 MB and slower finals. |
| Spanish | **Not applicable.** No deployable Spanish streaming model exists. |

What stays open: whether a shorter update interval can bring Moonshine's
first text forward without breaking real time, how the larger variants behave
over minutes of continuous speech (heat), and the Spanish half of the
question, which Phases 3 and 4 take up.

---

## Known limits of these numbers

- **Per-clip, not sustained.** The share of real time is measured per ~6 s
  clip. No Moonshine run has covered 5–10 minutes of continuous speech.
  Medium is the one to watch: it was the only run of any arm to reach the
  35 °C start gate, and the harness paused once to let it cool. Its speed
  did not drift as it warmed, but a continuous session is a harder test.
- **Thread count not controlled.** The SDK has no thread setting, so ONNX
  Runtime's default applies, unlike the plan's fixed four threads.
- **Final text is measured from the end of the clip**, and clips end with
  varying amounts of silence. Comparing arms on the same clips is fair;
  comparing languages or corpora is not.
- **One phone.** Timing and memory are properties of this handset.
