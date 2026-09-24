# Phase 1 — Arm A: the platform on-device recognizer

**What was measured:** Android's built-in `SpeechRecognizer.createOnDeviceSpeechRecognizer()`
on a Snapdragon 870 (Xiaomi `M2012K11AG`, Android 13), in English and Spanish,
on clean and noisy audio.

**Why it matters:** Arm A bundles **zero megabytes**. It is the bar every other
arm has to beat. If it is good enough, shipping a model is unjustified.

Measured 2026-09-23. Full method and reproduction steps in the [README](../README.md).

> **Revised in Phase 2.** Arm A was re-run later the same day on a corrected
> harness and corpus, and several conclusions below changed. The figures are
> left as Phase 1 measured them, except where the scoring was wrong. Current
> numbers are in the [README](../README.md#results).
>
> - **Spanish WER** 7.73% → **8.27%**. This is a scoring fix: a row that
>   returned no text was skipped instead of counted as missed. Pooled with the
>   Phase 2 run it is 8.14%.
> - **"Spanish is more accurate than English" is not established.** The
>   English clips were ~40 dB quieter than the Spanish ones. At matched level,
>   the gap depends on a single clip.
> - **English timing did not reproduce; English accuracy did, exactly.** The
>   English run started one minute after the English language pack finished
>   installing. Re-run, noisy English showed 6 ms of slip (not 196), first text
>   at 1012 ms (not 1276), and final text 68 ms after the audio ended (not
>   315 ms). So slip does not work as a proxy for difficulty after all.
> - **"The latency profiles are opposite, by language" holds only for first
>   text.** Spanish is slower to show text (~2.0 s vs 1.0–1.3 s), and that
>   reproduced across three runs. Final text is fast in both languages once
>   measured from the real end of the audio (0–76 ms).
> - **Final latency was overstated throughout.** "End of speech" was stamped
>   up to one frame early, by 50–75 ms for this arm.
>
> **Revised again in Phase 3.** Noisy-English WER is **29.42%**, not 33.45%
> (pooled with the Phase 2 run, 29.31%). The recognizer writes years as
> digits, and the scorer read "1848" as "one thousand eight hundred forty
> eight" where the reference says "eighteen forty eight". The English session
> moves from 10.17% to 10.10%. "One word in three" is closer to one in four;
> the verdict stands. See
> [the finding](../README.md#years-written-as-digits-cost-four-wer-points).
>
> **Spanish, revised in Phase 3.** The scorer also read "12:00" as "doce
> cero", which no one says, and stripped the "%" this recognizer writes where
> FLEURS says "por ciento". Fixed, the 20 Spanish clips score 7.75% and the
> Spanish session 6.54% (was 6.68%). Spanish has since grown to 100 clips,
> on which this recognizer scores **7.39%**. "Clean Spanish: strong" stands,
> but Parakeet (Phase 3) beats it measurably: 4.47% on the same 100 clips.

---

## Results

Audio is file-fed and paced to the wall clock, so every arm sees identical
input. Medians over all rows; repetition 0 discarded.

### Short utterances (~5 s, 4 repetitions)

| Lang | Audio | WER | CER | Time to **final** text | Time to **first** text | Revisions | Peak RSS |
|---|---|---|---|---|---|---|---|
| **es** | FLEURS, clean | **8.27%** | 3.09% | **27 ms** | 2010 ms | 6 | 103 MB |
| **en** | FLEURS, clean (very quiet†) | **9.69%** | 4.36% | 123 ms | 1260 ms | 8 | 107 MB |
| **en** | LibriSpeech, **noisy** | **33.45%** | 26.02% | 315 ms | 1276 ms | 7 | 107 MB |

*Spanish corrected from 7.73% in Phase 2: the scorer had skipped one utterance
that returned no text, instead of counting its words as missed. † 18 of the 20
English clips are recorded ~40 dB quieter than the Spanish ones. Both are
explained in the [README](../README.md#an-empty-transcript-is-every-word-missed-not-a-row-to-skip).*

### Continuous speech (6 min sessions, 1 repetition — indicative)

| Lang | WER | CER | Final | First | Revisions / word | Temperature |
|---|---|---|---|---|---|---|
| **es** | **6.68%** | 2.39% | 29 ms | 2607 ms | 0.45 | 29.2 → 29.2 °C |
| **en** | **10.17%** | 4.92% | 40 ms | 1214 ms | 0.50 | 28.7 → 29.2 °C |

---

## What the numbers say

### Spanish is more accurate than English

8.27% vs 9.69% on the **same corpus, same recognizer** — FLEURS exists
precisely so this comparison is not confounded by domain or speaker.

It was first stated as "same recording conditions" as well. On level that turned
out to be false: the English clips sit at a median of −63 dBFS, the Spanish at
−22 dBFS. So the margin is not clean evidence of a language effect.

This was the opposite of the expectation going in. Spanish was assumed to be
the hard language to serve, because it is the one with no good open streaming
models. For the free arm it is the strong one.

The accuracy is real, not a scoring artifact: stripping accents before scoring
moves Spanish only from 8.27% to 7.88%, so 95% of the error is genuine
recognition rather than missing diacritics.

### Accuracy collapses on noisy audio

**33.45% WER** on LibriSpeech `test-other` — 3.5× worse than clean English,
with CER rising from 4.4% to 26%.

This is the single most important result in Phase 1. Clean read speech flatters
this arm badly; a judgement based on FLEURS alone would have been wrong about
ordinary use. Roughly one word in three is wrong in conditions resembling a
normal room.

### The latency profiles are opposite, by language

| | Spanish | English |
|---|---|---|
| Time to first text | 2010 ms | 1260 ms |
| Time to finalise after speech ends | **27 ms** | 123 ms |

Spanish shows nothing for two seconds and then commits almost instantly.
English shows text sooner but takes ~5× longer to settle.

These are different models with different buffering, so *"Android's on-device
recognizer has latency X"* is not a meaningful claim without naming the
language.

### Continuous speech is not a problem

Six minutes of continuous audio produced **no thermal drift** (29.2 → 29.2 °C),
no pacing slip, and Spanish accuracy actually *improved* over short clips
(6.68% vs 8.27%) because longer context helps.

The startup cost is paid **once per session**, not per utterance: session
first-text latency matches the short-clip figure in each language. So the
two-second Spanish delay is a per-session cost for continuous dictation, and a
per-utterance cost only for voice-command style use.

### Language packs are not guaranteed

The test handset shipped with **only `es-ES` installed**. English was listed as
*supported* but was absent, and Arm A could not transcribe English at all until
the pack was downloaded.

Two consequences for any product built on this:

- **"Supported" is not "installed."** Checking `supportedOnDeviceLanguages`
  passes and then fails at runtime with `ERROR_LANGUAGE_UNAVAILABLE`. Only
  `installedOnDeviceLanguages` answers the real question.
- The fix is an API, not a settings screen. `SpeechRecognizer.triggerModelDownload()`
  installed `en-US` in 15 seconds; no UI path on this build offered it.

### It is reliable, but not always in a tidy way

On roughly 3% of clips the recognizer ends a session with `ERROR_CLIENT` while
having already delivered a complete, correct transcript through partial
results. One clip produced byte-identical output on all four repetitions while
raising the error on only two.

Anything built on `SpeechRecognizer` should treat *"error with partial text"*
as a usable result rather than discarding it.

---

## Verdict

**Arm A is a serious option for Spanish and a weak one for noisy English.**

| | Verdict |
|---|---|
| Clean Spanish | **Strong.** 8.27% WER and 27 ms to final text, for 0 MB. |
| Clean English | **Adequate.** 9.69% WER. |
| Noisy English | **Disqualifying.** 33.45% WER. |
| Continuous use | **Fine.** No thermal or sustained-throughput problem. |
| Responsiveness | **Mixed.** 1.2–2.6 s before any text appears. |

**The decision gate stays open, and arms C and D remain in the matrix.** Not
because Arm A is bad — on clean speech it is genuinely good and free — but
because 33% WER in ordinary noise is exactly the failure a bundled model would
exist to fix.

What Phase 2 onward has to establish is whether a bundled model actually fixes
it, and at what size. If Moonshine or Parakeet cannot substantially beat 33% on
`test-other`, then Arm A wins by default and the megabytes buy nothing.

---

## Known limits of these numbers

Stated so they are not over-read:

- **Sessions are a single repetition.** Four repetitions of a 6-minute clip per
  language would be 48 minutes of phone time. The session figures are
  indicative, not statistical.
- **Per-utterance latency within a session is not measured.** Only the first
  utterance of a session is timed. The manifest already stores per-utterance
  boundaries, so this is a scoring-side addition rather than a re-run.
- **`rtf_sustained` is blank for Arm A by design.** Recognition happens inside
  Google's process, so the fraction of real time it consumes is not observable
  from ours. Schedule slip is reported instead as the closest available proxy,
  and it tracks difficulty as expected: 4 ms on clean Spanish, 196 ms on noisy
  English.
- **One device, one build.** Language availability in particular is a property
  of the individual handset, not of Android.
