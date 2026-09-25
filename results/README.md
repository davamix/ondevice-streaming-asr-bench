# Results — the final table

What PLAN.md §13 asks for: per arm and per language, `latency_final_ms`,
`partial_instability`, `rtf_sustained`, `peak_rss_mb`, `disk_size_mb` and
WER, a recommendation answering §1, and whether Arm A made bundling a model
unnecessary. Everything here was measured on one Snapdragon 870 phone (Xiaomi
`M2012K11AG`, Android 13), file-fed and paced to the wall clock. The method,
the caveats and every finding are in the [main README](../README.md).

Each directory here is one run as pulled from the phone
(`<model>-<utc>-<label>`); it also holds copies of every earlier result
file, which the scripts ignore by name. [`superseded/`](superseded/) holds
runs kept as evidence and excluded from every aggregate.

## Per clip

Medians over every valid run (`scripts/summarize.py`), repetition 0
discarded. WER pools every run, each clip counted once. Final text is from
the actual end of the audio, clamped at zero. Revisions are words already on
screen that were later rewritten, per clip. Compute is the share of real time
the stack spent computing, partials included, per clip. The README's tables
take timing from particular runs, so its figures can differ from these by a
few tens of milliseconds.

| Arm | Stack | Audio | WER | Final text | Revisions | Compute | Peak RSS | Disk |
|---|---|---|---|---|---|---|---|---|
| A | Platform recognizer | Spanish | 7.39% | 0 ms | 6 | — | —¹ | 0 MB |
| A | Platform recognizer | English, clean | 9.72% | 72 ms | 8 | — | —¹ | 0 MB |
| A | Platform recognizer | English, noisy | 27.09% | 75 ms | 7 | — | —¹ | 0 MB |
| B | Moonshine tiny | English, clean² | 11.25% | 0 ms | 16 | 0.42 | 435 MB | 78 MB |
| B | Moonshine tiny | English, noisy² | 14.43% | 84 ms | 11 | 0.40 | 391 MB | 78 MB |
| B | Moonshine small | English, clean | 7.36% | 199 ms | 10 | 0.73 | 730 MB | 224 MB |
| B | Moonshine small | English, noisy | 8.92% | 478 ms | 8 | 0.73 | 730 MB | 224 MB |
| B | Moonshine medium | English, clean | **4.71%** | 515 ms | 8 | 0.81 | 967 MB | 416 MB |
| B | Moonshine medium | English, noisy | **7.02%** | 749 ms | 8 | 0.81 | 967 MB | 416 MB |
| B | Moonshine small-es | Spanish | 7.24% | 0 ms | 14 | 0.43 | 640 MB | 122 MB |
| C | Moonshine base-es³ | Spanish | 5.09% | 2504 ms | 9 | 1.16 | 872 MB | 65 MB |
| D | **Parakeet** | Spanish | **4.47%** | 0 ms | 11 | 0.56 | 1002 MB | 670 MB |
| D | **Parakeet** | English, clean | 4.97% | 211 ms | 13 | 0.62 | 1034 MB | 670 MB |
| D | **Parakeet** | English, noisy | 9.34% | 349 ms | 9 | 0.56 | 1034 MB | 670 MB |
| E | Whisper base | Spanish² | 14.73% | 471 ms | 15 | 0.81 | 572 MB | 161 MB |
| E | Whisper base | English, clean² | 11.88% | 618 ms | 9 | 0.88 | 572 MB | 161 MB |
| E | Whisper base | English, noisy² | 22.82% | 678 ms | 16 | 0.83 | 572 MB | 161 MB |
| E | Whisper small | Spanish² | 11.24% | 2751 ms | 2 | 1.24 | 997 MB | 375 MB |
| E | Whisper small | English, clean² | 5.31% | 2683 ms | 4 | 1.36 | 997 MB | 375 MB |
| E | Whisper small | English, noisy² | 14.43% | 2424 ms | 5 | 1.35 | 997 MB | 375 MB |

Spanish is FLEURS `es_419`; clean English is FLEURS `en_us`, level-matched;
noisy English is LibriSpeech `test-other`. 100 clips per source, except
where marked. ¹ The recognizer runs in Google's process; the harness alone
peaks at ~115 MB. ² The original 20 clips per source only. ³ Non-commercial
licence: an experiment, not a shipping option.

## Sustained: 6-minute sessions (`rtf_sustained`)

One continuous ~6-minute session per language (34 English utterances,
level-matched; 27 Spanish), one repetition, one stack per run
(`scripts/session_report.py`). The session sentences are not the short
clips' sentences, so session WER compares stacks on the same audio, not with
the table above.

| Stack | Session | `rtf_sustained` | Worst 30 s | Final text per utterance, median / p90 | Drift | Temperature | Peak RSS | WER |
|---|---|---|---|---|---|---|---|---|
| Parakeet | English | 0.59 | 0.73 | 0 / 366 ms | none | 32.5 → 35.0 °C | 1075 MB | 11.14%⁴ |
| Parakeet | Spanish | 0.61 | 0.73 | 23 / 416 ms | none | 34.2 → 35.7 °C | 1114 MB | 6.26% |
| Moonshine small-en | English | 0.70 | 0.77 | 0 / 646 ms | none | 33.0 → 35.2 °C | 1033 MB⁵ | 7.25% |
| Moonshine small-es | Spanish | 0.45 | 0.51 | 0 / 178 ms | none | 33.2 → 33.5 °C | 854 MB⁵ | 8.62% |
| Moonshine medium-en | English | 0.78 | 0.91 | 220 / 919 ms | none | 33.2 → 36.7 °C | 1004 MB | 6.74% |
| Platform (Arm A) | Spanish | — | — | — | — | 29.2 → 29.2 °C | — | 6.54% |

"Drift" is a rank correlation of final-text lag against position in the
session, tested by permutation; none reached p < 0.05. ⁴ One 22-word
sentence came back empty, 2.8 points of it
([finding](../README.md#parakeet-can-return-nothing-for-a-tightly-cut-segment)).
⁵ Both Moonshine small models were loaded in that run; an app would hold one.

All first-text and final-text figures are for file-fed audio. From a live
microphone on this phone, text arrives about 0.2 s later, for every arm
alike ([finding](../README.md#the-paced-feeder-hands-audio-over-one-frame-early)).

## Recommendation (PLAN.md §1)

**One model can serve both languages live on this phone, and it is
Parakeet.** Ship it as the one model, and pad every VAD segment with about
half a second of leading silence. It is the most accurate Spanish option
measured (4.47%, about 40% fewer errors than any per-language option), level
with the best English model on clean speech (4.97% against 4.71%) and within
reach on noisy speech (9.34% against 7.02%; a PC diagnostic puts padded
Parakeet level with it).
Over six continuous minutes it used 0.6 of real time, with no drift and no
throttling. Its costs are 670 MB of disk, ~1.1 GB resident, and first text
1.0–1.5 s after speech starts. Unpadded, it silently drops whole sentences.

If size, memory or heat matter more than Spanish accuracy, ship Moonshine
small-en + small-es: 346 MB, MIT-licensed, one runtime, 0.45–0.70 of real
time, and Spanish at the platform recognizer's level. Moonshine medium-en is
the most accurate English model, for 192 MB more than small.

## Did Arm A make bundling a model unnecessary?

**No.** The platform recognizer is free, fast, and level with Moonshine's
Spanish model (7.39% against 7.24%). But it misses one word in four on noisy
English (27.09%, against 7–9% for every bundled English model), it depends
on an on-device language pack the user may not have (English had to be
installed on this phone before it could be measured), and it is Google's
component, not the app's. Bundling is justified by noisy English alone.
