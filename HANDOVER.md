# Handover — next phase to run

**Currently: Phase 4, Arm C (Moonshine `base-es`). Not started.**

This file carries whatever phase is next. It is rewritten as each phase
completes; finished phases are written up in [`summaries/`](summaries/).

**For:** a fresh session picking this up with no prior context.

Phases 0–3 are complete and pushed. Phase 3 found that Parakeet (Arm D)
serves both languages well. Phase 4 prices the cheap Spanish option, Arm C,
before the final call on PLAN.md §1: *one model for both languages, or one per
language?*

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for current state
> and exact commands, and the three summaries in `summaries/` for what Phases
> 1–3 established. `README.md` has the full results and findings.
>
> Phases 0–3 are complete. Phase 4 integrates Arm C (Moonshine `base-es`,
> VAD-segmented), validates it on the emulator, and measures it on the phone
> on the 100-clip Spanish set. Then compare Spanish across Arms A, C, D and E and answer the
> PLAN.md §1 question. Update the README results as numbers come in.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phase 0 (corpus, scorer) | ✅ complete; scorer now reads English years as years |
| Phase 1 (Arm A) | ✅ complete, revised in Phases 2 and 3 — [summary](summaries/phase-1-arm-a.md) |
| Phase 2 (Arm B) | ✅ complete, revised in Phase 3 — [summary](summaries/phase-2-arm-b.md) |
| Phase 3 (Arms D, E) | ✅ complete — [summary](summaries/phase-3-arms-d-e.md) |
| Spanish on 100 clips (Arm A, Parakeet) | ✅ done 2026-09-24 |
| Phase 4 (Arm C) | ⬜ **next** |
| Phase 5 (analysis, sessions, mic check) | ⬜ not started |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**The bar, after Phase 3** (WER; latency is time to final text after the audio ends):

| | Arm A (0 MB) | Moonshine small (224 MB) | Whisper small (375 MB) | Parakeet (670 MB) |
|---|---|---|---|---|
| Spanish, 100 clips | 7.62% | — | — | **4.64%** |
| Spanish, original 20 clips | 7.78% | — | 11.28% | **6.61%** |
| English noisy | 29.31% | 8.72% | 14.43% | **7.05%** |
| English clean, level-matched | 12.60% | 7.71% | **5.31%** | 6.88% |
| Final text, noisy / Spanish | **68 / 0 ms** | 442 / — | 2424 / 2750 ms | 353 / 0 ms |
| First text, noisy / Spanish | **1.0 / 2.0 s** | 1.2 / — | 2.2 / 3.0 s | 1.6 / 2.4 s |

Parakeet is the one-model-for-both candidate, and on 100 Spanish clips it
beats Arm A measurably (3.0 points, 95% interval +0.6 to +5.9). The two-stack
alternative is Moonshine small (English) plus Arm A (Spanish). Arm C is
interesting if it serves Spanish near Arm A's 7.62% at 65 MB, which would make
a small two-stack option without depending on the platform's language pack.
Judge it with `scripts/compare.py`, not by the two pooled numbers.

---

## Done before Phase 4 — Spanish on 100 clips

Twenty Spanish clips could not separate the arms, so the corpus gained 80
Spanish clips (`fleurs-es-short-020` to `-099`, one recording per unused
sentence, 3–10 s). Arm A (3 reps) and Parakeet (2 reps) were re-measured on
all 100 on 2026-09-24: 7.62% and 4.64%. Results are in
`results/*-armA-es100` and `results/*-armD-es100`. Whisper was not
re-measured: it is calibration, and Whisper small costs ~40 battery points a
run. Comparisons involving it use the original 20 clips, which
`compare.py` picks automatically as the shared set.

Two scorer changes came with it, both PC-side and self-tested. Each clip now
counts once however many rows it has (`score.score_clips`), and on-the-hour
clock times read as the hour ("12:00" had been "doce cero"). Both are in the
README findings.

## Phase 4 — Arm C (Moonshine `base-es`)

`fetch_models.py moonshine-base-es` (64.8 MB). ⚠️ **Non-commercial licence**,
experiment only; do not redistribute (models/MODELS.md).

It is a legacy **non-streaming** Moonshine model, so it needs VAD segmentation
like Arms D and E. The integration is an open design choice:

- **Moonshine SDK, non-streaming.** `MoonshineArm`'s KDoc notes that
  `loadFromFiles(path, 0)` selects the non-streaming layout, which looks for
  `encoder_model.ort` + `decoder_model_merged.ort`, exactly what `base-es`
  ships. Whether the SDK's `Transcriber` then segments by itself, or needs
  audio fed per segment, is not established. PLAN.md §5 lists Arm C's runtime
  as the Moonshine SDK.
- **Reuse the Phase 3 pipeline.** `SherpaOfflineArm` already does VAD
  segmentation, partial re-decoding and timing. sherpa-onnx has an
  `OfflineMoonshineModelConfig`, but its fields (`preprocessor`, `encoder`,
  `uncachedDecoder`, `cachedDecoder`, `mergedDecoder`) describe sherpa-onnx's
  own ONNX exports, not obviously these two `.ort` files. Check before
  assuming it can load them. Using it would also change Arm C's runtime from
  the one PLAN.md names (D6).

Either way, keep what Phase 3 established: decode off the feed thread, 500 ms
partials, record `last_final_wait_ms` and `final_after_audio_end_ms`.
Spanish only, on all 100 Spanish clips (4 reps: 400 clips). Then compare
Spanish across A / C / D with `scripts/compare.py` on the shared 100 clips
(E only on the original 20),
which is PLAN.md §10 step 18.

---

## Lessons from Phase 3 that apply directly

- **Partials cost more than the model.** Re-decoding open speech every 500 ms
  multiplies compute and heat two to three times. Whisper small needed 0.53 of
  real time for finals and 1.3 with partials. Report both; `summarize.py`
  prints `rtf, finals only`. For a slow model, finals also queue behind a
  running partial (up to 3.6 s for Whisper small).
- **Check claims against the library source before writing them down.** Two
  first-draft statements were wrong: the Kotlin VAD's 5 s
  `maxSpeechDuration` does not hard-cut (it tightens the VAD), and the stale
  battery temperature is not tied to charge-level changes.
- **Report an interval, not two numbers.** Twenty clips could not tell
  Parakeet from Arm A in Spanish; 100 could. Offline arms give identical text
  every rep, so reps add no accuracy evidence. `scripts/compare.py --standard`
  re-checks every comparison the README makes. English rankings among the
  bundled models are still within noise on 20 clips.
- **A scorer rule can favour one output style.** Years written as digits cost
  Arm A four WER points on noisy English. When a new arm formats text
  differently (numbers, names, punctuation), look at its worst clips before
  trusting the pooled WER.
- **Silero VAD finds quiet speech but late.** Both D and E lose the first word
  on about half the very quiet clips. Compare on `fleurs_en_norm`.
- **WER is deterministic for these arms.** Greedy decoding behind a
  deterministic VAD gives byte-identical text every repetition. Timing still
  varies with heat, so it is still reported per run.
- **The emulator has ~0.75 GB free.** Swap models there one at a time.
  Parakeet (670 MB) left 0.1 GB, below the emulator gate's 0.3 GB floor.
  `--skip-preflight` was used **once, on the emulator only**, for that
  validation run; never on the phone.

---

## Budget and constraints

**Measured cost per full run on the phone:**

| Run | Clips | Time | Battery | Peak temp (as read) | Cooling pauses |
|---|---|---|---|---|---|
| Arm A | 240 | 39 min | 3 points | 29.2 °C | 0 |
| Moonshine tiny | 240 | 39 min | 6 points | 30.7 °C | 0 |
| Moonshine small | 240 | 39 min | 10 points | 33.5 °C | 0 |
| Moonshine medium | 240 | 44 min | 11 points | 35.0 °C | 1 |
| Whisper base | 320 | 87 min | 20 points | 35.2 °C | 7 |
| Parakeet | 320 | 53 min | 14 points | 34.5 °C | 0 |
| Whisper small | 320 | 156 min | 39 points | 35.5 °C | 15 |
| Arm A, Spanish 100 clips | 300 | 61 min | 5 points | 30.2 °C | 0 |
| Parakeet, Spanish 100 clips | 200 | 41 min | 11 points | 33.2 °C | 0 |

The gate needs battery 30–80% and not charging, and it checks the level only
at start. §11.3 asks for 30–80% *throughout*, so run this watchdog alongside
any long run. It force-stops only this app, and the run's last checkpoint
survives:

```bash
A=D:/Android/Sdk/platform-tools/adb.exe; D=<ip:port>
while MSYS_NO_PATHCONV=1 $A -s $D shell pidof io.github.davamix.asrbench >/dev/null 2>&1; do
  lvl=$(MSYS_NO_PATHCONV=1 $A -s $D shell dumpsys battery | grep "level:" | tr -dc '0-9')
  echo "$(date +%H:%M) level=$lvl"
  [ "$lvl" -lt 30 ] && MSYS_NO_PATHCONV=1 $A -s $D shell am force-stop io.github.davamix.asrbench && break
  sleep 60
done
```

**Temperatures lag.** The battery temperature the gate reads can be minutes
old (README finding). A cooling pause can therefore run long, and a clip can
start slightly above 35 °C. `dumpsys battery` has the same lag. The thermal
HAL exposes no sensors on this phone, and the sysfs node is denied to shell.

The phone ended the Spanish re-measure at 62%. Arm C is Spanish only (400
clips for 4 reps) and `base-es` is small, so a run should cost less than
Parakeet's, but it still needs 30–80% at the start.

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

In Git Bash, prefix raw `adb shell` / `adb pull` calls that take device paths
with `MSYS_NO_PATHCONV=1`. Otherwise `/sdcard/...` is rewritten to a Windows
path. `run_bench.py` is unaffected.

### Build prerequisite

```bash
.venv/Scripts/python scripts/fetch_runtime.py   # sherpa-onnx AAR -> bench/app/libs/, SHA-256 checked
```

The Gradle build fails with a clear message if the AAR is missing.

### Emulator first

```bash
D:/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_36.0 &
.venv/Scripts/python scripts/run_bench.py --device emulator --push-models <dir>
.venv/Scripts/python scripts/run_bench.py --device emulator \
    --arms <arm> --langs en,es --buckets short --reps 1 --label emu<arm> \
    --session-cap-s 180
```

Emulator results are gitignored and never published. The emulator currently
holds `silero-vad` and `whisper-small-int8`; remove the latter before pushing
anything large.

### Measure, score, write up

```bash
.venv/Scripts/python scripts/run_bench.py --device physical --push-models <dir>
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms <arm> --langs en,es --buckets short --reps 4 --label <label> \
    --no-push --timeout 10800

.venv/Scripts/python scripts/summarize.py            # all results, per variant
.venv/Scripts/python scripts/summarize.py results/<dir>/<file>.json   # one run
```

`--arms` knows `A`, `B[:variant]`, `D[:variant]` and `E[:variant]`. For D and E
the variant is the model directory name (`parakeet-tdt-v3-int8`,
`whisper-base-int8`, `whisper-small-int8`). `--partial-ms N` sets the partial
re-decode interval for D and E (default 500; 0 = final text only).
`summarize.py` groups by `extra.variant`.

**Pilots:** after a full run supersedes a one-rep pilot, move the pilot's
directory into `results/superseded/` and add a row to its README. Phase 3 ran
no phone pilots.

**Long runs:** `adb am instrument` can outlive a host-side timeout. If the
wrapper dies, the run usually continues on the device — check
`adb logcat -s AsrBench:*` and wait for the JSON. If the run itself dies, the
last checkpoint is in the device's results dir, marked `complete: false`.

---

## Open (not blocking)

- **Partials-off run.** Final latency without partials is only derived
  (measured minus the wait behind a running partial). One run with
  `--partial-ms 0` on Parakeet would measure it, and the compute and heat
  saved.
- **The 2/4/6/8 thread sweep** PLAN.md §6 asks for. It was impossible on
  Moonshine (no thread setting). sherpa-onnx's `numThreads` is real, so
  Parakeet can host it. `threads` is already an instrumentation argument.
- **Session runs** (5–10 minutes continuous) for the surviving arms: PLAN.md
  places them in Phase 5. Parakeet's worst clip used 0.85 of real time with
  partials.
- **A better thermal signal.** `PowerManager.getThermalHeadroom()` /
  `getCurrentThermalStatus()` are app-visible (API 30+). Recording them per
  row would show throttling directly, rather than through a lagging battery
  temperature.
- **Cooling time counts toward the 10-minute session cap**, so a run that
  cooled for eight minutes then takes a two-minute break. Conservative, so it
  was left alone.
- From Phase 2: the Moonshine update-interval sweep, and why Arm A's Phase 1
  English timing differed.

---

## Things to know about the harness

- **Nothing is bundled in the APK.** Models are pushed to
  `/sdcard/Android/data/io.github.davamix.asrbench/files/models/`. On the phone
  now: all three Moonshine variants, `silero-vad`, both Whisper variants and
  `parakeet-tdt-v3-int8` (~1.9 GB), and the 162-clip corpus. ~73 GB free, so
  no need to remove any.
- **The app must create its own directories** before anything is pushed into
  them (`prepareDirs`, with `model_dirs`). `run_bench.py` handles this.
- **Two ONNX Runtimes live in the APK**, Moonshine's and sherpa-onnx's
  (statically linked). Do not switch to the regular sherpa-onnx AAR: its
  `libonnxruntime.so` collides with Moonshine's (README finding).
- **`SherpaOfflineArm` decodes on its own thread.** The feed does only the
  VAD, so slip stays under 50 ms and is not a proxy for anything.
  `rtf_sustained` there is VAD time plus all decode time over audio time.
- **Moonshine's `loadFromFiles(path, int)` second argument is the model
  architecture** (5 = streaming, 0 = non-streaming), not flags.
- **Scoring happens on the PC**, never on the device, so a scoring bug never
  costs a re-run. Phase 3's year fix proved it again.

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
account identifiers, no device IP address. Check before every push.
