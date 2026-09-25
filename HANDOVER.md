# Handover — next phase to run

**Currently: Phase 4 — Arm C is measured; measure Moonshine
`small-streaming-es` on the phone, then compare Spanish across A / B-es / C /
D / E and answer PLAN.md §1.**

This file carries whatever phase is next. It is rewritten as each phase
completes; finished phases are written up in [`summaries/`](summaries/).

**For:** a fresh session picking this up with no prior context.

Phases 0–3 are complete. English has been re-measured on 100 clips per
source. Phase 4: Arm C was measured on the phone on 2026-09-25; one phone
run remains (`small-streaming-es`), then the write-up.

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for current state
> and exact commands, and the three summaries in `summaries/` for what Phases
> 1–3 established. `README.md` has the full results and findings.
>
> Phases 0–3 and the English re-measure are complete, and Phase 4's Arm C
> (Moonshine `base-es`) is measured. Run Moonshine `small-streaming-es` on
> the phone on the 100-clip Spanish set (command in HANDOVER.md). Then compare Spanish across Arms A, B-es, C, D and E with
> `scripts/compare.py --standard`, answer the PLAN.md §1 question, and write
> the Phase 4 summary. Update the README results as numbers come in.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phase 0 (corpus, scorer) | ✅ complete |
| Phase 1 (Arm A) | ✅ complete, revised since — [summary](summaries/phase-1-arm-a.md) |
| Phase 2 (Arm B) | ✅ complete, revised since — [summary](summaries/phase-2-arm-b.md) |
| Phase 3 (Arms D, E) | ✅ complete, revised since — [summary](summaries/phase-3-arms-d-e.md) |
| Spanish on 100 clips (Arm A, Parakeet) | ✅ 2026-09-24 |
| English on 100 clips (Moonshine small, medium, Parakeet, Arm A) | ✅ 2026-09-24/25 |
| Phase 4 code (Arm C, Moonshine `small-streaming-es`) | ✅ written, emulator-validated, installed on the phone |
| Phase 4: Arm C on the phone | ✅ 2026-09-25, 2 reps (stopped early by design, see below) |
| Phase 4: `small-streaming-es` on the phone | ⬜ **next** |
| Phase 4 write-up (§1 verdict, `summaries/phase-4-spanish.md`) | ⬜ after it |
| Phase 5 (analysis, sessions, mic check) | ⬜ not started |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**The bar now** (WER; latency is time to final text after the audio ends):

| | Arm A (0 MB) | Moonshine small (224 MB) | Moonshine medium (416 MB) | Parakeet (670 MB) |
|---|---|---|---|---|
| Spanish, 100 clips | 7.39% | — (small-es next) | — | **4.47%** (Arm C, 65 MB: 5.09%) |
| English noisy, 100 clips | 27.09% | 8.92% | **7.02%** | 9.34% |
| English clean, level-matched, 100 clips | 9.72% | 7.36% | **4.71%** | 4.97% |
| Final text, clean / noisy (100-clip runs) | **69 / 81 ms** | 222 / 486 ms | 539 / 772 ms | 250 / 349 ms |
| First text, clean / noisy | **1.16 / 1.11 s** | 1.32 / 1.19 s | 1.29 / 1.24 s | 1.87 / 1.58 s |

What 100 English clips resolved (`compare.py --standard`): medium beats small
on both sources (noisy only just: lower bound +0.03, and a compound-spelling
adjustment makes it unresolved); Parakeet beats small on clean speech and is
level with it on noisy; medium and Parakeet are level on clean, and medium
leads on noisy by 2.3 points, just short of resolved. Arm A loses to every
bundled model on noisy speech by 18–20 points.

Parakeet's noisy figure is held down by a failure mode, not by mishearing
(README finding 21): on some tightly cut VAD segments it returns no text. Half
a second of leading silence recovers ~2 points in a PC diagnostic. **The owner
chose not to re-measure a padded Parakeet**: it stays a finding, and the
published numbers are the stack as sherpa-onnx ships it (its own
simulate-streaming app does not pad either).

---

## Phase 4 — Arm C result (2026-09-25)

**5.09% WER** on the 100 Spanish clips: level with Parakeet (+0.6, CI −0.5 to
+1.8) and ahead of Arm A by 2.3 points (CI −0.04 to +5.1, just short of
resolved). **But not live on this phone:** a final decode takes 2.2 s, a
partial 1.1 s (longer than the 500 ms cadence), compute is 1.16× real time
with partials (0.45 finals only), and final text lands 2.5 s after the
speaker stops. 8 cooling pauses in 78 minutes; 24 battery points for 206
rows; peak RSS 872 MB (283 after load). Written up in the README
("Arm C: Moonshine `base-es`").

**Planned 4 reps, stopped after 2 by the owner's choice**: it would have
needed ~48 battery points. Rep 0 and rep 1 text were byte-identical on all
100 clips (and matched the emulator), so more reps add timing only. The
stop was a force-stop during the break right after the checkpoint at 206
rows; the result file is that checkpoint (`complete: false`), and
`summarize.py` prints a warning for it. That is expected.

## Phase 4 — what was built

### Arm C: Moonshine `base-es` (64.8 MB, ⚠️ non-commercial)

Integrated into `SherpaOfflineArm` as `Model.MoonshineNonStreaming`, so it
shares everything Phase 3 established: Silero VAD (sherpa-onnx), decode on a
worker thread, 500 ms partials, `last_final_wait_ms`,
`final_after_audio_end_ms`. Only the decode call differs:
`Transcriber.transcribeWithoutStreaming()` from the Moonshine SDK, the
runtime PLAN.md §5 names. Decisions, each checked against the Moonshine
v0.1.5 C++ source (`core/transcriber.cpp`, `core/moonshine-model.cpp`):

- **`vad_threshold=0`.** `transcribeWithoutStreaming` runs Moonshine's own
  VAD over its input before decoding. With 0 it only chunks at 15 s, so each
  call decodes exactly the segment Silero cut. Moonshine's own FLEURS eval
  script does the same. Every row records `max_lines_per_decode`; it must be 1
  (it was, on all 100 emulator clips).
- **Architecture 1 (`MOONSHINE_MODEL_ARCH_BASE`).** It sets the decoder's layer
  count. Nothing checks it at load: 0 (tiny) loads and fails at the first decode.
- **`max_tokens_per_second` left at 6.5.** Moonshine's documented value for
  Latin scripts, and what its Spanish eval uses. An emulator ablation at 13
  changed 3 of 100 clips (a clipped last word), one word in 1,767. The harness
  can run such ablations: `--moonshine-options key=value,...` (arm C only;
  rows get a distinct variant, e.g. `moonshine-base-es+max_tokens_per_second=13`).
- **`return_audio_data=false`**, so segment audio is not copied back to Java.
- Rows record `num_threads: null` for C: the SDK has no thread setting.

### Moonshine `small-streaming-es` (121.8 MB, MIT) — new, added to Arm B

README finding 1 was wrong: Moonshine's Spanish streaming models exist, on
its CDN (`download.moonshine.ai`), where the SDK's own catalog fetches them,
not on HuggingFace. The owner chose to add `small-streaming-es` (not tiny).
`fetch_models.py moonshine-small-es` downloads it and pins every file by
SHA-256 (the CDN has no revisions). `MoonshineArm` now takes a variant's
language from its suffix. Arm B, variant `moonshine-small-es`.

With it, the §1 question has a third answer to price: **Moonshine for both
languages**, two MIT models on one runtime (small-en 224 MB + small-es
122 MB), against Parakeet (670 MB) and against Moonshine-en + Arm A.

### Emulator validation (not published)

`results/sdk_gphone64_x86_64-*-emuC` and `-emuBC-es`, 100 Spanish clips each:
no errors, no empty rows, one line per decode for C. Emulator WER is a sanity
check only (D1).

---

## Phase 4 — commands

Arm C is done (it was run with `--no-build --push-models
moonshine-base-es,moonshine-small-es`, which installed the Phase 4 APK and
pushed both models and the Spanish corpus). What remains needs no install
and no push:

```bash
# ~18 battery points expected; start at up to 80%, cooled below ~33 °C.
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-small-es --langs es --buckets short --sources fleurs_es \
    --reps 3 --label armB-small-es100 --no-build --no-install --no-push --timeout 10800

.venv/Scripts/python scripts/compare.py --standard
.venv/Scripts/python scripts/summarize.py
```

3 reps for small-es, since Moonshine's streaming output varies a little
between repetitions. Estimate ≈ 18 points (small-en cost 10.4 per 240 clips,
and Spanish clips run ~40% longer).

Run each with the battery watchdog (see Budget). Wait for the phone to cool
below ~33 °C between runs. `compare.py --standard` already holds the Phase 4
Spanish pairs: A, C, D, E and B-es against each other.

Then: README results (a Phase 4 section and the "against the bar" table),
`summaries/phase-4-spanish.md`, the §1 verdict, and this file for Phase 5.

---

## Lessons that apply directly

- **Look at a new arm's worst clips before trusting its pooled WER.** It found
  the years bias (Phase 3), and on the English re-measure it found Parakeet's
  empty segments. For Arm C and small-es, check for clipped endings (the
  token cap), empty finals, and formatting (numbers, "%", clock times).
- **Report an interval, not two numbers.** Twenty clips misranked Parakeet
  against Moonshine small in both English sources. `compare.py --standard`.
- **Check claims against the library source.** It settled Arm C's design
  (Moonshine's hidden VAD, the architecture value) and exposed the CDN.
- **The model hub is not necessarily where an SDK gets its models.** Check the
  SDK's own catalog.
- **Partials cost more than the model** (Phase 3). Report `rtf, finals only`
  from `summarize.py` alongside the total.
- **Silero VAD cuts tightly.** Parakeet loses segments with no leading
  silence. If Arm C shows empty finals, this is the first suspect; the PC
  diagnostic for Parakeet is in README finding 21.
- **The emulator has ~0.5 GB free now** (it holds `silero-vad`,
  `moonshine-tiny-en`, `moonshine-base-es`, `moonshine-small-es`). Swap models
  there one at a time.

---

## Budget and constraints

**Measured cost per run on the phone:**

| Run | Clips | Time | Battery | Peak temp (as read) | Cooling pauses |
|---|---|---|---|---|---|
| Arm A | 240 | 39 min | 3 points | 29.2 °C | 0 |
| Moonshine small | 240 | 39 min | 10 points | 33.5 °C | 0 |
| Moonshine medium | 240 | 44 min | 11 points | 35.0 °C | 1 |
| Whisper base | 320 | 87 min | 20 points | 35.2 °C | 7 |
| Parakeet | 320 | 53 min | 14 points | 34.5 °C | 0 |
| Whisper small | 320 | 156 min | 39 points | 35.5 °C | 15 |
| Arm A, Spanish 100 clips | 300 | 61 min | 5 points | 30.2 °C | 0 |
| Parakeet, Spanish 100 clips | 200 | 41 min | 11 points | 33.2 °C | 0 |
| Moonshine medium, English 100 | 600 | 132 min | 33 points | 35.0 °C | 7 |
| Parakeet, English 100 | 400 | 81 min | 17 points | 35.0 °C | 3 |
| Moonshine small, English 100 | 600 | 111 min | 26 points | 35.0 °C | 2 |
| Arm A, English 100 | 400 | 64 min | 4 points | 28.2 °C | 0 |
| Arm C, Spanish 100 (stopped after 2 reps) | 206 | 78 min | 24 points | 35.2 °C | 8 |

The gate needs battery 30–80% and not charging, and it checks the level only
at start. §11.3 asks for 30–80% *throughout*, so run a watchdog alongside
every long run. This wrapper runs one `run_bench.py` command with a watchdog
that polls every minute for the whole run (not only while the app is up, so
it cannot quit during `prepareDirs` or the corpus push) and force-stops only
this app below 30%. The run's last checkpoint survives. Save it outside the
repo (it needs the device address):

```bash
#!/usr/bin/env bash
# run_with_watchdog.sh <ip:port> <logname> -- <run_bench.py args...>
set -u
D="$1"; NAME="$2"; shift 3
A=D:/Android/Sdk/platform-tools/adb.exe
LOG="$(dirname "$0")/$NAME.log"; WLOG="$(dirname "$0")/$NAME.watchdog.log"
cd "F:/Development/Samples/android-transcription-sample"
( while true; do
    out=$(MSYS_NO_PATHCONV=1 $A -s "$D" shell dumpsys battery 2>/dev/null)
    lvl=$(echo "$out" | grep " level:" | tr -dc '0-9')
    t=$(echo "$out" | grep " temperature:" | tr -dc '0-9')
    echo "$(date +%H:%M:%S) level=$lvl temp=${t:0:2}.${t:2}" >> "$WLOG"
    if [ -n "$lvl" ] && [ "$lvl" -lt 30 ]; then
      MSYS_NO_PATHCONV=1 $A -s "$D" shell am force-stop io.github.davamix.asrbench; break
    fi
    sleep 60
  done ) &
WD=$!
.venv/Scripts/python -u scripts/run_bench.py "$@" > "$LOG" 2>&1; rc=$?
kill $WD 2>/dev/null; tail -25 "$LOG"; exit $rc
```

**Charging leaves the phone hot.** Straight off the charger it read 36.7 °C,
above the 35 °C gate, and took ~8 minutes to read 32 °C. Unplug a while
before a run.

**Temperatures lag.** The battery temperature the gate reads can be minutes
old (README finding). `dumpsys battery` has the same lag.

---

## Running things

### Connect the phone

Wireless debugging, so the phone is **not charging** during runs (PLAN.md §11.3
forbids measuring while charging, and this phone has no charge limiter).

```bash
adb connect <ip>:<port>        # from Settings > Developer options > Wireless debugging
.venv/Scripts/python scripts/devicelib.py --device physical
```

The address can change between sessions. `adb devices` shows the phone twice
(by IP and by mDNS); `devicelib` dedupes. The emulator is often attached too,
so always pass `--device`. In Git Bash, prefix raw `adb shell` / `adb pull`
calls that take device paths with `MSYS_NO_PATHCONV=1`.

### Build prerequisite

```bash
.venv/Scripts/python scripts/fetch_runtime.py   # sherpa-onnx AAR -> bench/app/libs/, SHA-256 checked
.venv/Scripts/python scripts/fetch_models.py moonshine-base-es moonshine-small-es
```

### Measure, score, write up

```bash
.venv/Scripts/python scripts/summarize.py            # all results, per variant
.venv/Scripts/python scripts/summarize.py results/<dir>/<file>.json   # one run
.venv/Scripts/python scripts/compare.py --standard   # paired bootstrap
```

`--arms` knows `A`, `B[:variant]`, `C[:variant]`, `D[:variant]` and
`E[:variant]`. `--sources` limits a run and its corpus push. `--partial-ms N`
sets the partial cadence for C, D and E (default 500; 0 = finals only).
`--moonshine-options k=v,...` overrides Moonshine options for arm C.

**Long runs:** `adb am instrument` can outlive a host-side timeout. If the
wrapper dies, the run usually continues on the device — check
`adb logcat -s AsrBench:*` and wait for the JSON. If the run itself dies, the
last checkpoint is in the device's results dir, marked `complete: false`.

---

## Open (not blocking)

- **Padded Parakeet on the phone.** Declined for now (see above). If the final
  recommendation hinges on Parakeet vs Moonshine medium in noisy English, it
  is the run that would settle it: ~17 points for English.
- **Compound spelling.** "reelected" vs "re elected" costs 0.2–0.6 points per
  arm; it makes small-vs-medium on noisy English unresolved. The scorer was
  not changed (a generic join rule forgives real errors). A curated list is
  possible but must not be tuned on these results.
- **Moonshine's hidden decoder file.** English variants ship
  `decoder_kv_with_attention.ort` (33 / 82 / 147 MB), loaded only for word
  timestamps, so shipped size overstates what runs: 45 / 142 / 269 MB. The
  README size figures still quote the shipped sizes; worth a line in the
  final write-up.
- **Partials-off run**, the **2/4/6/8 thread sweep** on Parakeet, **session
  runs** (Phase 5), a **better thermal signal**
  (`PowerManager.getThermalHeadroom()`), and the Moonshine update-interval
  sweep: as before.

---

## Things to know about the harness

- **Nothing is bundled in the APK.** Models are pushed to
  `/sdcard/Android/data/io.github.davamix.asrbench/files/models/`. On the phone
  now: all three English Moonshine variants, `moonshine-base-es`,
  `moonshine-small-es`, `silero-vad`, both Whisper variants and
  `parakeet-tdt-v3-int8` (~2.1 GB). ~73 GB free.
- **The phone runs the Phase 4 APK** (installed 2026-09-25 for Arm C). The
  English re-measure ran on the one before it.
- **The app must create its own directories** before anything is pushed into
  them (`prepareDirs`, with `model_dirs`). `run_bench.py` handles this.
- **Two ONNX Runtimes live in the APK**, Moonshine's and sherpa-onnx's
  (statically linked). Arm C uses both: sherpa-onnx for the VAD, Moonshine for
  the decode.
- **`SherpaOfflineArm` decodes on its own thread**, for C, D and E alike.
- **Moonshine's `loadFromFiles(path, int)` second argument is the model
  architecture** (`JNI.MOONSHINE_MODEL_ARCH_*`: 0 tiny, 1 base, 2–5
  tiny/base/small/medium streaming). For streaming models any streaming value
  works (dimensions come from `streaming_config.json`); for non-streaming it
  must match.
- **`pull_results` pulls every file in the device's results dir**, so each run
  directory holds all earlier results too. `summarize.py` and `compare.py`
  dedupe by file name.
- **Scoring happens on the PC**, never on the device.

---

## Safety — read `PLAN.md` §11 before touching the device

The phone is the owner's only handset, personal and irreplaceable.

**Never:** root, bootloader unlock, `disable-verity`, fastboot, factory reset,
disabling thermal throttling or forcing a CPU governor, `pm uninstall/clear` on
anything but this app, or writes outside the two paths in §11.2.

**The gate** (`scripts/devicelib.py`) refuses to start a run unless: ≥5 GB free,
battery 30–80%, temperature <35 °C, **not charging**. It aborts mid-run at
43 °C, pauses before any clip that would start at or above 35 °C, and pauses
2 minutes every 10 minutes of continuous inference. There is a
`--skip-preflight` flag; treat needing it as a signal to stop and think.

`RECORD_AUDIO` is **not** declared until Phase 5. Every measured run is
file-fed and needs no microphone.

Nothing personal enters the repo: no device serial, no self-recorded audio, no
account identifiers, no device IP address. Check before every push.
