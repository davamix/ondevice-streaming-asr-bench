# Phase 5 — Six-minute sessions, the microphone, and the verdict

**What was measured:** the stacks that survived Phase 4, on a Snapdragon 870
(Xiaomi `M2012K11AG`, Android 13):

- **Continuous 6-minute sessions**, one per language: Parakeet (English and
  Spanish), Moonshine small-en and small-es, and Moonshine medium-en. This
  is `rtf_sustained` as PLAN.md §6 defines it, over minutes of speech rather
  than per clip, the test Phases 2–4 could not give.
- **The real-microphone check** (§7): the phone's owner read corpus sentences
  aloud while a stack transcribed the microphone live, then the same
  recording went through the same stack file-fed. Parakeet and Moonshine
  small, English and Spanish, four takes of 60 s.

**Why it matters:** Phase 4 answered PLAN.md §1 from per-clip runs. A stack
can keep up clip by clip and still fall behind the microphone after a few
minutes of heat, and every measured run was file-fed, never checked against
a live microphone. Either would undo the answer.

Measured 2026-09-25. Full method in the
[README](../README.md#six-minute-sessions-does-anything-fall-behind).

---

## Results

| 6-minute session | Compute over the session (per clip) | Worst 30 s | Final text, median / p90 | Drift | Temperature | WER on the session |
|---|---|---|---|---|---|---|
| Parakeet, English | **0.59** (0.62) | 0.73 | 0 / 366 ms | none | 32.5 → 35.0 °C | 11.14%¹ |
| Parakeet, Spanish | **0.61** (0.56) | 0.73 | 23 / 416 ms | none | 34.2 → 35.7 °C | **6.26%** |
| Moonshine small-en | **0.70** (0.73) | 0.77 | 0 / 646 ms | none | 33.0 → 35.2 °C | 7.25% |
| Moonshine small-es | **0.45** (0.43) | 0.51 | 0 / 178 ms | none | 33.2 → 33.5 °C | 8.62% |
| Moonshine medium-en | **0.78** (0.81) | 0.91 | 220 / 919 ms | none | 33.2 → 36.7 °C | **6.74%** |

Compute is the share of real time spent computing; above 1.0 a stack falls
behind the microphone. ¹ One sentence came back empty; 8.3% had it been
transcribed. Session sentences differ from the short clips', so session WER
compares stacks on the same audio, not with the per-clip tables.

| Microphone check: live against file-fed | Parakeet en | Parakeet es | Moonshine en | Moonshine es |
|---|---|---|---|---|
| Text | identical | identical | 2 of 82 words differ | identical |
| Compute | 0.39 / 0.37 | 0.34 / 0.33 | 0.54 / 0.54 | 0.31 / 0.31 |
| Final text later live, per segment | +210 ms | +208 ms | +132 ms | +256 ms |

---

## What the numbers say

### Nothing falls behind

Six continuous minutes cost what the per-clip runs said, within 0.05 of
real time, and no stack's worst half-minute came near real time. Moonshine
stalls its feeder for up to 1.6 s while it decodes, but the delay does not
accumulate.

### No throttling in six minutes

Final-text lag shows no trend over any session. Parakeet's English compute
rises through its session, but its sentences get longer and its decode speed
per second of audio got slightly faster. Its Spanish session, run next on a
warmer phone, is flat. The phone ended sessions at 33.5–36.7 °C, and the
platform never reported a thermal status above "none".

### Memory creeps up

Every stack ended a session 35–170 MB above its level at 30 s; Parakeet
reached 1.11 GB across its two sessions, above its per-clip 1.03 GB. It is
not a steady leak, and what grows was not established.

### Parakeet must be padded

One of its 55 English segments, 6.9 s of clean speech, came back empty: a
whole 22-word sentence. With half a second of silence in front, the same
audio decodes word for word (PC diagnostic). This is the failure Phase 4
found on short clips, now in continuous speech.

### The file-fed method is ~0.2 s optimistic, and exact on text

From the microphone, Parakeet cut identical segments and produced identical
text, and each segment's text arrived ~0.21 s later. About 0.1 s is the
method: the paced feeder releases each 100 ms frame at the start of its slot,
where a microphone delivers it at the end. The rest is the phone's capture
path. Every arm gets the same head start, so no comparison changes, but
every first-text and final-text figure is ~0.2 s early for a live
microphone. The published numbers are left as measured.

### This microphone is quiet, and Moonshine hears it

Speech reached the model at about −40 dBFS: 17 dB below the level-matched
corpus, 20 dB above the very quiet FLEURS English on which Moonshine drops
utterances. Moonshine produced text for every line.

---

## Verdict

**One model can serve both languages live on this phone, and it is
Parakeet.** Ship it, and pad every VAD segment with ~0.5 s of leading
silence. It has the best Spanish (4.47%) and near-best English, and it holds
0.6 of real time over continuous speech. It costs 670 MB of disk and ~1.1 GB
of memory, and it shows first text 1.0–1.5 s after speech starts (plus
~0.2 s live).

If size, memory or heat matter more than Spanish accuracy, ship **Moonshine
small-en + small-es**: 346 MB, MIT, 0.45–0.70 of real time, Spanish at the
platform recognizer's level. **Moonshine medium-en** is the best English
model for 192 MB more.

**Arm A did not make bundling unnecessary.** It misses one word in four on
noisy English and depends on a language pack the user may not have.

---

## Known limits of these numbers

- **One session per language per stack, one repetition.** Indicative, not
  an interval. Six minutes sits inside the safety policy's 10-minute cap; a
  longer session could still find throttling or more memory growth.
- **The phone is the owner's.** Two session runs were lost to its use: one
  overlapped a video call, and "clear recent apps" killed another. Both
  were discarded, and the log shows the phone idle during every run
  reported. The harness now records, and waits out, a call in progress.
- **Padded Parakeet was not measured on the phone.** The recommendation to
  pad rests on a PC diagnostic and on the sentence the session dropped.
- **The microphone check is one reader, in one room, on one phone.** It
  validates the method; it does not measure accuracy on real voices, and the
  takes are not scored in public (they are the owner's voice).
- **Temperatures lag**, and this phone reports no thermal headroom.
- **One phone.** Timing and memory are properties of this handset.
