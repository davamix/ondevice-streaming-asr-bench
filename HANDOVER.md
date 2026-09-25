# Handover — next phase to run

**Currently: Phase 5 — sustained sessions for the surviving stacks, the
real-microphone check, and the final write-up.**

This file carries whatever phase is next. It is rewritten as each phase
completes; finished phases are written up in [`summaries/`](summaries/).

**For:** a fresh session picking this up with no prior context.

Phases 0–4 are complete and pushed. Phase 4 answered PLAN.md §1: **one model
can serve both languages live on this phone, and it is Parakeet** (Arm D).
Phase 5 checks that answer against what per-clip runs cannot show: minutes of
continuous speech, and a real microphone.

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for current state
> and exact commands, and the four summaries in `summaries/` for what Phases
> 1–4 established. `README.md` has the full results and findings.
>
> Phases 0–4 are complete. Phase 5 (PLAN.md §10): validate session runs for
> Moonshine and Parakeet on the emulator, run 5–10 minute sessions on the
> phone for the surviving stacks, then the single real-microphone sanity
> check (the only step that needs `RECORD_AUDIO`), then the final write-up,
> README verdict and a tagged release.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phases 0–3 | ✅ complete, revised since — summaries [1](summaries/phase-1-arm-a.md), [2](summaries/phase-2-arm-b.md), [3](summaries/phase-3-arms-d-e.md) |
| Phase 4 (Spanish, English on 100 clips, §1 answer) | ✅ complete — [summary](summaries/phase-4-spanish.md) |
| Phase 5 (sessions, microphone, write-up, release) | ⬜ **next** |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**The answer so far** (100 clips per source; first text from speech onset):

| Live stack | Disk | Spanish WER | English WER, clean / noisy | Final text | First text | Peak RSS |
|---|---|---|---|---|---|---|
| **Parakeet** | 670 MB | **4.47%** | 4.97 / 9.34% | 0–350 ms | 1.0–1.5 s | 1.03 GB |
| Moonshine small-en + small-es | 346 MB | 7.24% | 7.36 / 8.92% | 0–490 ms | 0.7–1.0 s | 0.64–0.76 GB |
| Moonshine medium-en + small-es | 538 MB | 7.24% | **4.71 / 7.02%** | 0–770 ms | 0.7–1.0 s | 0.97 GB |
| Moonshine small-en + Arm A | 224 MB + pack | 7.39% | 7.36 / 8.92% | 0–490 ms | 0.7–1.0 s | 0.76 GB |

Out: Arm C (5.09% Spanish, but 2.5 s to final text, 1.16× real time, and a
non-commercial licence), Whisper (calibration only), Moonshine tiny.

---

## Phase 5 — what to do

### 1. Sessions (PLAN.md §6, §10 step 19)

`rtf_sustained` is defined over 5–10 minutes of continuous speech, and no
bundled model has had one. The corpus has one ~6-minute session per language
(`bucket: session`, clips disjoint from the short ones), built for this.
Only Arm A has run them (Phase 1, segmented mode).

**Untested for B and D. Validate on the emulator first (§11.5)**:
`--buckets session` with `B:moonshine-small-en` / `B:moonshine-small-es` /
`D:parakeet-tdt-v3-int8`, `--reps 1`. Things to check: the feeder, the
Moonshine stream and the VAD arm all run for 6 minutes without a stall; the
harness's hypothesis joins every line/segment; the drain timeout
(`DRAIN_TIMEOUT_MS`, 60 s in `SherpaOfflineArm`) is not hit at the end;
`summarize.py` scores the session against its concatenated reference.
The 10-minute continuous-inference cap is per session and a 6-minute session
fits under it.

Then on the phone, one session per language per stack, `--reps 1`
(`summarize.py` keeps rep 0 for single-rep runs and flags it indicative):

| Stack | Sessions |
|---|---|
| Parakeet | en, es |
| Moonshine small-en / small-es | en / es |
| Moonshine medium-en | en (optional: it ran hottest per clip) |

What to read: `rtf_sustained` over the whole session (above 1.0 means it falls
behind the microphone), final latency late in the session against early,
battery temperature before and after, and cooling pauses. Parakeet used ~0.6
of real time per clip with partials; medium 0.8.

### 2. The real-microphone check (§7, §10 step 20)

Validation, not measurement: confirm that live microphone input behaves like
the paced file feed. This is the **only** step that needs `RECORD_AUDIO`; it
is not declared in the manifest yet (§11.4). It needs the owner speaking, so
plan it with them. Self-recorded audio is the owner's voice: keep it local
and gitignored, never in the repo (§11.7).

### 3. Write-up and release (§10 steps 21–22)

Latency / WER / size / RSS trade-off curves, the recommendation, the final
README verdict (the §1 answer is already in the README, "Spanish across the
matrix, and the answer to §1"), and a tagged release so the published
numbers correspond to a fixed commit. PLAN.md §13 lists what "done" means.

---

## Open questions the write-up should settle or state

- **Padded Parakeet on the phone.** The owner chose not to re-measure it in
  Phase 4. A PC diagnostic says 0.5 s of leading silence per VAD segment
  recovers ~2 points on noisy English (README finding 21), which would put it
  level with Moonshine medium. The recommendation says "pad"; the phone has
  not confirmed it. ~17 battery points for the English sources.
- **Compound spelling** ("reelected" vs "re elected") moves each arm by
  0.2–0.6 points and makes small-vs-medium on noisy English unresolved. The
  scorer was not changed.
- **Moonshine's shipped vs loaded size.** English variants ship
  `decoder_kv_with_attention.ort`, loaded only for word timestamps: 78 / 224 /
  416 MB shipped, 45 / 142 / 269 MB used. Spanish small ships without it.
- **Why Arm C decodes so slowly** (2.2 s per final on a 58M-parameter model)
  and why its memory climbs to 872 MB were not established.
- **Moonshine `small-es` scores 7.24%**, not the 4.9% Moonshine publishes
  (different data, whole utterances, its own normaliser). Accents are 9% of
  its errors.
- Not done and probably not needed for the verdict: the 2/4/6/8 thread sweep
  (possible on Parakeet), a partials-off run, the Moonshine update-interval
  sweep, a better thermal signal (`PowerManager.getThermalHeadroom()`).

---

## Lessons that apply directly

- **Measure from the event the metric is named after.** First text was timed
  from the start of the clip; the Spanish clips open with ~1 s more silence,
  and "Spanish is slower" survived three phases. `summarize.py` now prints
  first text from speech onset too. Sessions start with silence as well.
- **Look at a new arm's worst clips before trusting its pooled WER.** It found
  the years bias, Parakeet's empty segments, and the accent share of
  `small-es`'s errors.
- **Report an interval, not two numbers.** `compare.py --standard`.
- **Check claims against the library source.** It settled Arm C's design and
  exposed the CDN.
- **Deterministic arms need 2 repetitions** (rep 0 discarded); Moonshine
  English streaming varies a little between reps (3), Moonshine `small-es`
  did not. Verify with rep-to-rep text before cutting reps.
- **Budget before a long run.** Arm C was estimated at 8–15 points and cost 24
  for half its plan. A 10-minute checkpoint tells you the rate; stopping at a
  checkpoint (force-stop during the 2-minute break after it) loses nothing.

---

## Budget and constraints

**Measured cost per run on the phone** (all runs so far):

| Run | Clips | Time | Battery | Peak temp (as read) | Cooling pauses |
|---|---|---|---|---|---|
| Arm A | 240 | 39 min | 3 points | 29.2 °C | 0 |
| Moonshine tiny | 240 | 39 min | 6 points | 30.7 °C | 0 |
| Moonshine small | 240 | 39 min | 10 points | 33.5 °C | 0 |
| Moonshine medium | 240 | 44 min | 11 points | 35.0 °C | 1 |
| Whisper base | 320 | 87 min | 20 points | 35.2 °C | 7 |
| Parakeet | 320 | 53 min | 14 points | 34.5 °C | 0 |
| Whisper small | 320 | 156 min | 39 points | 35.5 °C | 15 |
| Arm A, Spanish 100 | 300 | 61 min | 5 points | 30.2 °C | 0 |
| Parakeet, Spanish 100 | 200 | 41 min | 11 points | 33.2 °C | 0 |
| Moonshine medium, English 100 | 600 | 132 min | 33 points | 35.0 °C | 7 |
| Parakeet, English 100 | 400 | 81 min | 17 points | 35.0 °C | 3 |
| Moonshine small, English 100 | 600 | 111 min | 26 points | 35.0 °C | 2 |
| Arm A, English 100 | 400 | 64 min | 4 points | 28.2 °C | 0 |
| Arm C, Spanish 100 (stopped after 2 reps) | 206 | 78 min | 24 points | 35.2 °C | 8 |
| Moonshine small-es, Spanish 100 | 300 | 61 min | 11 points | 31.7 °C | 0 |

A 6-minute session is ~6 minutes of audio plus load, so a session run costs
roughly what 60 short clips do.

The gate needs battery 30–80% and not charging, and checks the level only at
start. §11.3 asks for 30–80% *throughout*, so run a watchdog alongside every
long run. This wrapper runs one `run_bench.py` command with a watchdog that
polls every minute for the whole run and force-stops only this app below
30%; the run's last checkpoint survives. Save it outside the repo (it needs
the device address):

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

Wireless debugging, so the phone is **not charging** during runs.

```bash
adb connect <ip>:<port>        # from Settings > Developer options > Wireless debugging
.venv/Scripts/python scripts/devicelib.py --device physical
```

The address can change between sessions. `adb devices` shows the phone twice
(by IP and by mDNS); `devicelib` dedupes. The emulator is often attached too,
so always pass `--device`. In Git Bash, prefix raw `adb shell` / `adb pull`
calls that take device paths with `MSYS_NO_PATHCONV=1`.

### Build and fetch

```bash
.venv/Scripts/python scripts/fetch_runtime.py   # sherpa-onnx AAR -> bench/app/libs/, SHA-256 checked
.venv/Scripts/python scripts/fetch_models.py --list
```

### Emulator first

```bash
D:/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_36.0 &
.venv/Scripts/python scripts/run_bench.py --device emulator --push-models <dir>
.venv/Scripts/python scripts/run_bench.py --device emulator \
    --arms <arm> --langs en,es --buckets session --reps 1 --label emu<arm>
```

The emulator has ~0.5 GB free; it holds `silero-vad`, `moonshine-tiny-en`,
`moonshine-base-es` and `moonshine-small-es`. Swap models one at a time.
Emulator results are gitignored and never published.

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
wrapper dies, the run usually continues on the device; check
`adb logcat -s AsrBench:*`. If the run itself dies, the last checkpoint is
in the device's results dir, marked `complete: false`.

---

## Things to know about the harness

- **Nothing is bundled in the APK.** On the phone now: all Moonshine English
  variants, `moonshine-base-es`, `moonshine-small-es`, `silero-vad`, both
  Whisper variants and `parakeet-tdt-v3-int8` (~2.1 GB), and the 100 Spanish
  plus 200 English short clips of the 100-clip sets. The session clips are
  on it from Phase 1, but push them again (`--buckets session`) to be sure.
  ~73 GB free.
- **The phone runs the Phase 4 APK.**
- **The app must create its own directories** before anything is pushed into
  them (`prepareDirs`); `run_bench.py` handles this.
- **Two ONNX Runtimes live in the APK**, Moonshine's and sherpa-onnx's
  (statically linked). Arm C uses both.
- **`SherpaOfflineArm` decodes on its own thread** for C, D and E.
- **Moonshine's `loadFromFiles(path, int)` second argument is the model
  architecture** (`JNI.MOONSHINE_MODEL_ARCH_*`). Streaming models take their
  dimensions from `streaming_config.json`; non-streaming ones need the right
  value.
- **`pull_results` pulls every file in the device's results dir**, so each run
  directory holds all earlier results. `summarize.py` and `compare.py` dedupe
  by file name.
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

`RECORD_AUDIO` is **not** declared until the Phase 5 microphone check. Every
measured run is file-fed and needs no microphone.

Nothing personal enters the repo: no device serial, no self-recorded audio, no
account identifiers, no device IP address. Check before every push.
