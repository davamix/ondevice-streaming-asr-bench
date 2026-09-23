# Handover — next phase to run

**Currently: Phase 3, Arms D and E via sherpa-onnx. Not started.**

This file carries whatever phase is next. It is rewritten as each phase
completes; finished phases are written up in [`summaries/`](summaries/).

**For:** a fresh session picking this up with no prior context.

Phases 0–2 are complete and pushed. Phase 3 brings in the two arms that can
serve **both** English and Spanish, and so bear directly on the central
question in PLAN.md §1: *one model for both languages, or one per language?*

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for current state
> and exact commands, and the two summaries in `summaries/` for what Phases 1
> and 2 established. `README.md` has the full results and findings.
>
> Phases 0–2 are complete. Phase 3 integrates sherpa-onnx with Silero VAD
> segmentation, then measures Arm E (Whisper base and small, int8) and Arm D
> (Parakeet TDT 0.6b v3 int8), in English and Spanish. Build and validate on
> the emulator first, then measure on the physical device, one model at a
> time, and update the README results as numbers come in.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phase 0 (corpus, scorer) | ✅ complete; corpus includes level-matched FLEURS English |
| Phase 1 (Arm A) | ✅ complete, re-measured in Phase 2 — [summary](summaries/phase-1-arm-a.md) |
| Phase 2 (Arm B) | ✅ complete — [summary](summaries/phase-2-arm-b.md) |
| Phase 3 (Arms D, E) | ⬜ **next** |
| Phases 4–5 (Arm C, analysis) | ⬜ not started |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**The bar Phase 3 has to clear:**

| | Arm A (0 MB) | Moonshine small (224 MB) |
|---|---|---|
| WER, English noisy | 33.33% | 8.72% |
| WER, English clean (level-matched) | 12.60% | 7.71% |
| WER, Spanish clean | 8.14% | — (no Spanish model) |
| First / final text, English noisy | 1012 / 68 ms | 1191 / 442 ms |

Arm D or E is interesting if it serves Spanish better than Arm A's 8.14%, or
serves both languages well enough to replace two stacks with one.

---

## Phase 3 steps (PLAN.md §10)

1. **Integrate sherpa-onnx** (Android AAR) and Silero VAD segmentation into
   `bench/`. Neither model is streaming-native: VAD cuts the paced audio into
   utterances and each segment is transcribed offline. Mirror `MoonshineArm`'s
   structure. Load from `files/models/<dir>`, never bundle weights, never let a
   library self-download (D8).
2. **Arm E, Whisper base then small** (int8, 161 / 375 MB). This is the
   calibration baseline, in both languages. Expect heavy
   `partial_instability`, because chunked Whisper rewrites text.
3. **Arm D, Parakeet TDT v3 int8** (670 MB). **Watch `peak_rss_mb`.** On a
   6 GB phone with ~2 GB free, this is where memory is expected to bite, and
   Moonshine medium already reached 933 MB. If it fails to load or is killed,
   record that honestly: it is a result.

Models are fetched with `scripts/fetch_models.py --arm D` / `--arm E`. Only
Moonshine and Silero VAD are downloaded locally so far. Sizes and pinned
revisions are in `models/MODELS.md`. Note that `fetch_models.py --list` and
`models/MODELS.md` give the int8 ONNX sizes. The 180 / 57 MB figures in
PLAN.md §5 are whisper.cpp sizes and do not apply (README finding 4).

---

## Lessons from Phase 2 that apply directly

- **Score every row.** An empty hypothesis counts as all-deletions.
  `summarize.py` reports `no text:` per group; a non-zero count deserves a
  look.
- **Final latency uses `final_after_audio_end_ms`,** measured from feed start
  + audio duration. Any new arm must record it the way `MoonshineArm` and
  `PlatformRecognizerArm` do (`final_slip_ms`, `speech_end_lag_ms`,
  `final_after_audio_end_ms`). The old `latency_final_ms` was off by up to
  ~260 ms either way.
- **Check whether inference runs inside the feed call.** Moonshine's
  `addAudio()` blocks for whole model passes, which is why its slip is large.
  With VAD segmentation, the offline decode may run inline or on another
  thread. Know which before interpreting slip or `rtf_sustained`.
- **Quiet audio breaks VAD-gated models.** Moonshine returned no text at all
  on some of the very quiet FLEURS English clips. Silero VAD may behave the
  same. The `fleurs_en_norm` copies exist to separate level from accuracy;
  compare on them.
- **Report WER pooled, timing per run.** Arm A's Phase 1 English timing did
  not reproduce, although its accuracy did.

---

## Budget and constraints

**Heat now matters, as well as battery.** Moonshine medium was the first run
to reach the 35 °C start gate (the harness paused ~4 minutes to cool).
Parakeet's encoder is larger still. Start each run cool (under ~32 °C), not
straight after another heavy run, and expect cooling pauses.

**Measured cost per 240 clips** (60 English clips × 4 reps). Spanish is 20
clips, so a third of this:

| Run | Time | Battery | Peak temp |
|---|---|---|---|
| Arm A | 39 min | 3 points | 29.2 °C |
| Moonshine tiny | 39 min | 6 points | 30.7 °C |
| Moonshine small | 39 min | 10 points | 33.5 °C |
| Moonshine medium | 44 min | 11 points | 35.0 °C |

The gate needs battery 30–80% and not charging, and it checks the level only
before starting, not during the run. After charging, the phone must drift
below 80% before a run can start, and it may also be warm from charging.

---

## Running things

### Connect the phone

Wireless debugging, so the phone is **not charging** during runs (PLAN.md §11.3
forbids measuring while charging, and this phone has no charge limiter).

```bash
adb connect <ip>:<port>        # from Settings > Developer options > Wireless debugging
.venv/Scripts/python scripts/devicelib.py --device physical
```

The address changes between sessions. If `adb devices` shows the phone twice
(once by IP, once by mDNS) that is normal — `devicelib` dedupes by hardware
identity. The emulator is often attached too, so always pass `--device`.

### Emulator first

```bash
D:/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_36.0 &
.venv/Scripts/python scripts/run_bench.py --device emulator --push-models <dir>
.venv/Scripts/python scripts/run_bench.py --device emulator \
    --arms <arm> --langs en --buckets short --reps 1 --label emu<arm> \
    --session-cap-s 180
```

`--session-cap-s` lowers the continuous-inference cap (it can never raise
it), so the per-break results checkpoint gets exercised in minutes. Emulator
results are gitignored and never published. The emulator has only ~0.7 GB
free, so push one model at a time there.

### Measure, score, write up

```bash
.venv/Scripts/python scripts/run_bench.py --device physical --push-models <dir>
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms <arm> --langs en,es --buckets short --reps 4 --label <label> \
    --no-push --timeout 7200

.venv/Scripts/python scripts/summarize.py            # all results, per variant
.venv/Scripts/python scripts/summarize.py results/<dir>/<file>.json   # one run
```

`run_bench.py --arms` currently knows `A` and `B[:variant]`; Phase 3 extends
the parser in `BenchmarkTest.benchmark()`. `summarize.py` groups by
`extra.variant`, so give each new arm's rows a `variant` in `extra`.

**Pilots:** after a full run supersedes a one-rep pilot, move the pilot's
directory into `results/superseded/` and add a row to its README.
`summarize.py` excludes superseded runs by file name, so the copies that
later pulls bring back are excluded too.

**Long runs:** `adb am instrument` can outlive a host-side timeout. If the
wrapper dies, the run usually continues on the device — check
`adb logcat -s AsrBench:*` and wait for the JSON. If the run itself dies, the
last checkpoint is in the device's results dir, marked `complete: false`.

---

## Open from Phase 2 (not blocking)

- **Moonshine update-interval sweep.** First text is paced by the SDK's 0.5 s
  interval (`setUpdateInterval()` is public). A sweep on tiny would price
  first-text latency against `rtf_sustained`.
- **Moonshine session runs** (5–10 minutes continuous), especially medium,
  for thermals. PLAN.md places sustained runs in Phase 5, for surviving arms.
- **Why Arm A's Phase 1 English timing differed.** The run began one minute
  after the English language pack installed. Plausible, unconfirmed.

---

## Things to know about the harness

- **Nothing is bundled in the APK.** Models are pushed to
  `/sdcard/Android/data/io.github.davamix.asrbench/files/models/`. All three
  Moonshine variants are currently on the phone (~720 MB). Remove them from
  that directory if storage gets tight (the gate needs ≥5 GB free; ~74 GB is
  free now).
- **The app must create its own directories** before anything is pushed into
  them (`prepareDirs`, with `model_dirs`). `run_bench.py` handles this.
- **Moonshine's `loadFromFiles(path, int)` second argument is the model
  architecture** (5 = streaming), not flags. Already set; do not "fix" it.
- **Moonshine has no thread-count setting.** Check whether sherpa-onnx does;
  if so, hold it at 4 as the plan says (§6).
- **Scoring happens on the PC**, never on the device, so a scoring bug never
  costs a re-run.

---

## Safety — read `PLAN.md` §11 before touching the device

The phone is the owner's only handset, personal and irreplaceable.

**Never:** root, bootloader unlock, `disable-verity`, fastboot, factory reset,
disabling thermal throttling or forcing a CPU governor, `pm uninstall/clear` on
anything but this app, or writes outside the two paths in §11.2.

Disabling throttling is what benchmarking guides suggest constantly. It is
refused here twice over: it risks hardware that cannot be replaced, and it is
methodologically wrong, because live transcription runs hot for minutes and
throttling is part of the phenomenon being measured.

**The gate** (`scripts/devicelib.py`) refuses to start a run unless: ≥5 GB free,
battery 30–80%, temperature <35 °C, **not charging**. It aborts mid-run at
43 °C, pauses before any clip that would start at or above 35 °C, and pauses
2 minutes every 10 minutes of continuous inference. There is a
`--skip-preflight` flag; treat needing it as a signal to stop and think.

`RECORD_AUDIO` is **not** declared until Phase 5. Every measured run is
file-fed and needs no microphone.

Nothing personal enters the repo: no device serial, no self-recorded audio, no
account identifiers. Check before every push.
