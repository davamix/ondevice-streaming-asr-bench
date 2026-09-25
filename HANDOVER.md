# Handover — where things stand

**Currently: the experiment is complete.** Phases 0–5 are done, written up
and tagged. No phase is pending. This file records the final state, what is
left open, and how to pick the work up again in a fresh session.

**For:** a fresh session picking this up with no prior context.

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for current state,
> the five summaries in `summaries/` for what each phase established, and
> `results/README.md` for the final table. `README.md` has the full results
> and findings.
>
> The experiment is complete (Phases 0–5, tagged). Any further work is a
> follow-up from "Open questions" below, agreed with the owner first.
>
> The test device is the owner's only phone, and in daily use. `PLAN.md` §11
> is a hard safety policy — read it before touching the device. The
> pre-flight gate in `scripts/run_bench.py` enforces most of it; do not bypass
> it. Agree phone time with the owner before every run.

---

## Where things stand

| | State |
|---|---|
| Phases 0–4 | ✅ complete — summaries [1](summaries/phase-1-arm-a.md), [2](summaries/phase-2-arm-b.md), [3](summaries/phase-3-arms-d-e.md), [4](summaries/phase-4-spanish.md) |
| Phase 5 (sessions, microphone, write-up, release) | ✅ complete — [summary](summaries/phase-5-sessions-and-verdict.md) |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public)

The whole study is also a printable paper, `docs/paper/paper.pdf`, built from
`docs/paper/paper.html` by `scripts/build_paper.py` (headless Chrome or Edge;
its figures are print variants from `plot_tradeoffs.py`). Keep it in step
with the README if a number changes.

**The answer** (PLAN.md §1): one model can serve both languages live on this
phone, and it is Parakeet, padded with ~0.5 s of leading silence per VAD
segment. Moonshine small-en + small-es is the smaller, cooler alternative.
Arm A did not make bundling unnecessary. The final table and recommendation
are in [`results/README.md`](results/README.md).

---

## Open questions

Stated in the write-up; none blocks the verdict.

- **Padded Parakeet on the phone.** Recommended, on a PC diagnostic and on
  the sentence the English session dropped. Never measured on the phone.
  ~17 battery points for the two English sources; the harness change is a
  few lines in `SherpaOfflineArm` (prepend silence to each final and partial
  decode), and it must become a distinct variant so it never pools.
- **Memory growth in sessions.** Every stack ended a 6-minute session
  35–170 MB above its level at 30 s; Parakeet reached 1.11 GB across two
  sessions. Not a steady leak; cause not established. A 10-minute session is
  the longest the safety policy allows; longer use would need the owner's
  agreement to a different cap.
- **The feeder's one-frame head start** (README finding 33). File-fed timing
  is ~0.2 s optimistic for live input. A new harness should release each
  frame at the end of its slot; changing it would make new runs incomparable
  with the published ones.
- **Compound spelling** ("reelected" vs "re elected") moves each arm by
  0.2–0.6 points and makes Moonshine small-vs-medium on noisy English
  unresolved. The scorer was not changed.
- **Moonshine's shipped vs loaded size.** English variants ship
  `decoder_kv_with_attention.ort`, loaded only for word timestamps: 78 / 224 /
  416 MB shipped, 45 / 142 / 269 MB used.
- **Why Arm C decodes so slowly** (2.2 s per final on a 58M-parameter model)
  and why its memory climbs to 872 MB.
- **Moonshine `small-es` scores 7.24%**, not the 4.9% Moonshine publishes.
- Not done and not needed for the verdict: the 2/4/6/8 thread sweep, a
  measured partials-off run, the Moonshine update-interval sweep.

---

## Lessons that apply directly

- **Agree phone time with the owner, and read the log after every run.** Two
  Phase 5 runs were lost to ordinary use of the phone: a video call through a
  session, and "clear recent apps" (`OneKeyClean`) killing the process. The
  row looked plausible both times. `adb logcat -b all` shows foreground apps
  (`wm_set_resumed_activity`), calls and clean-ups.
- **Validate the method against the thing it simulates.** The microphone
  check found the feeder's one-frame head start after five phases of
  file-fed numbers.
- **Measure from the event the metric is named after.** First text from
  speech onset; final text per utterance from that utterance's end.
- **Look at a new arm's worst clips before trusting its pooled WER.** It
  found the years bias, Parakeet's empty segments (twice), and the accent
  share of `small-es`'s errors.
- **Check a corpus file's level before measuring on it.** The English session
  sat unfixed at −60 dBFS for four phases.
- **Report an interval, not two numbers.** `compare.py --standard`; for a
  drift, `session_report.py`'s rank correlation.
- **Budget before a long run.** A 10-minute checkpoint tells you the rate.

---

## Budget and constraints

**Measured cost per run on the phone:**

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
| **Parakeet, sessions en + es** | 2 × 6 min | 14 min | 5 points | 36.0 °C | 0 |
| **Moonshine small, sessions en + es** | 2 × 6 min | 14 min | 4 points | 35.0 °C | 0 |
| **Moonshine medium, session en** | 6 min | 6 min | 3 points | 36.7 °C | 0 |
| **Microphone check, 4 takes** | 4 × 60 s | 15 min | 3 points | 34.7 °C | 0 |

A 6-minute session costs about what 60 short clips do, and heats the phone
2.5–3.5 °C; after one, expect 5–10 minutes of cooling before the 35 °C gate
passes again.

The gate needs battery 30–80% and not charging, and checks the level only at
start. §11.3 asks for 30–80% *throughout*, so run a watchdog alongside every
long run. This wrapper runs one `run_bench.py` command with a watchdog that
polls every minute and force-stops only this app below 30% battery or at
43 °C; the run's last checkpoint survives. Save it outside the repo (it
needs the device address):

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
    if [ -n "$t" ] && [ "$t" -ge 430 ]; then
      MSYS_NO_PATHCONV=1 $A -s "$D" shell am force-stop io.github.davamix.asrbench; break
    fi
    sleep 60
  done ) &
WD=$!
.venv/Scripts/python -u scripts/run_bench.py "$@" > "$LOG" 2>&1; rc=$?
kill $WD 2>/dev/null; tail -25 "$LOG"; exit $rc
```

**Charging leaves the phone hot**, and **temperatures lag** (README finding
28): the reading can drop 2 °C in one minute after sitting still for five.

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

### Emulator first

```bash
D:/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_36.0 &
.venv/Scripts/python scripts/run_bench.py --device emulator --push-models <dir>
```

The emulator has ~0.5 GB free and also holds the owner's other app; it now
holds `silero-vad` and `whisper-base-int8`, and Parakeet no longer fits.
Swap models one at a time, within this app's files dir only. Its virtual
microphone records silence (host audio input is off); its test takes were
deleted. Emulator results are gitignored and never published.

### Measure, score, write up

```bash
.venv/Scripts/python scripts/summarize.py            # all results, per variant
.venv/Scripts/python scripts/session_report.py       # inside each 6-minute session
.venv/Scripts/python scripts/mic_report.py           # microphone takes (local only)
.venv/Scripts/python scripts/compare.py --standard   # paired bootstrap
.venv/Scripts/python scripts/plot_tradeoffs.py       # docs/figures/, light and dark
```

Sessions: `--buckets session --sources fleurs_en_norm,fleurs_es --reps 1`.
The microphone check: `--mic <arm> --langs en --no-push`, with the owner at
the phone. See README "Reproducing" for the full commands.

---

## Things to know about the harness

- **The harness is uninstalled from the phone** (2026-09-25, the clean exit
  of §11.2): no app, no models, no corpus, results or microphone takes on
  it, and nothing in `/data/local/tmp`. ~75 GB free, as before the
  experiment. Every result is in `results/`; the four microphone takes (the
  owner's voice) are only in the gitignored `corpus/self-recorded/`. The
  emulator's copy is uninstalled too. **Nothing is bundled in the APK**, so
  a follow-up starts by installing and pushing again: `run_bench.py` builds
  and installs, and `--push-models` pushes the pinned weights (~2.1 GB for
  everything, ~20 minutes over wireless; push only what the run needs).
- **The APK declares `RECORD_AUDIO`**, and only the microphone check's
  screen asks for it; the owner grants it on the phone.
- **Session-length clips carry a timeline** (`SessionTimeline`): every 30 s
  of audio, compute, slip, temperature, memory, screen and call state, and
  one event per final segment or line. It also aborts at 43 °C mid-clip.
- **The 10-minute cap looks ahead**: the break comes before a clip that
  would end past it.
- **The runner waits out a call in progress** before a clip (up to 30 min).
  It cannot stop "clear recent apps", which kills the run.
- **The mic check's screen is opened through the shell** (`am start` from
  the test's UiAutomation): MIUI refuses an app opening its own activity
  from the background. The activity is exported but guarded by `DUMP`.
- **Two ONNX Runtimes live in the APK**, Moonshine's and sherpa-onnx's
  (statically linked). `SherpaOfflineArm` decodes on its own thread for C, D
  and E; Moonshine decodes inside `addAudio()`.
- **`pull_results` pulls every file in the device's results dir**, so each run
  directory holds all earlier results. `summarize.py` and `compare.py` dedupe
  by file name, and exclude `results/superseded/` by name.
- **Scoring happens on the PC**, never on the device.

---

## Safety — read `PLAN.md` §11 before touching the device

The phone is the owner's only handset, personal and irreplaceable, and in
daily use.

**Never:** root, bootloader unlock, `disable-verity`, fastboot, factory reset,
disabling thermal throttling or forcing a CPU governor, `pm uninstall/clear` on
anything but this app, `pm grant` (it needs MIUI's security toggle, which
§11.1 rules out), or writes outside the two paths in §11.2.

**The gate** (`scripts/devicelib.py`) refuses to start a run unless: ≥5 GB free,
battery 30–80%, temperature <35 °C, **not charging**. It aborts at 43 °C,
before a clip and every 30 s during a long one, pauses before any clip that
would start at or above 35 °C, waits out calls, and pauses 2 minutes before
any clip that would take continuous inference past 10 minutes. There is a
`--skip-preflight` flag; treat needing it as a signal to stop and think.

Nothing personal enters the repo: no device serial, no self-recorded audio or
its transcripts, no account identifiers, no device IP address. Check before
every push.
