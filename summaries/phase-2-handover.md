# Phase 2 handover — Arm B (Moonshine streaming English)

**For:** a fresh session picking this up with no prior context.

Everything for Phase 2 is **built and validated on the emulator**. What remains
is running it on the phone and writing up the numbers.

---

## Initial prompt

Paste this to start:

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `summaries/phase-2-handover.md` for
> current state and exact commands, and `summaries/phase-1-arm-a.md` for what
> Phase 1 established. `README.md` has the full findings and method.
>
> Phase 1 is complete. Phase 2 is Arm B — Moonshine streaming English — which
> is implemented and working on the emulator but has never run on the phone.
>
> Run Arm B on the physical device, **one model variant at a time** starting
> with `moonshine-tiny-en`, and update the README results table as numbers come
> in.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phase 0 (corpus, scorer) | ✅ complete |
| Phase 1 (Arm A) | ✅ complete — see [phase-1-arm-a.md](phase-1-arm-a.md) |
| Phase 2 (Arm B) | 🔨 built, emulator-validated, **no phone numbers yet** |
| Phases 3–5 (Arms C/D/E) | ⬜ not started |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**Arm B is known to work.** On the emulator: 40 rows, 0 errors, correct English
transcripts. Emulator timings are meaningless by design (PLAN.md D1) — that run
proved plumbing only.

---

## The one thing that will waste your time if you miss it

`Transcriber.loadFromFiles(path, int)`'s second parameter is the **model
architecture**, not a flags bitfield — despite sitting next to
`setTranscribeFlags()`. Passing `0` selects the non-streaming layout and fails
looking for `encoder_model.ort`, which streaming variants do not ship.

**The streaming value is `5`** (`MoonshineArm.STREAMING_ARCH`). It is not in any
public constant; it was read out of `MicTranscriber`'s bytecode. This is already
set correctly — do not "fix" it back to 0.

---

## Running it

### 1. Connect the phone

Wireless debugging, so the phone is **not charging** during runs (PLAN.md §11.3
forbids measuring while charging, and this phone has no charge limiter).

```bash
adb connect <ip>:<port>        # from Settings > Developer options > Wireless debugging
.venv/Scripts/python scripts/devicelib.py --device physical
```

The address changes between sessions. If `adb devices` shows the phone twice
(once by IP, once by mDNS) that is normal — `devicelib` dedupes by hardware
identity.

### 2. Push models

```bash
.venv/Scripts/python scripts/run_bench.py --device physical \
    --push-models moonshine-tiny-en
```

Models are already downloaded locally (`models/`, gitignored):
`moonshine-tiny-en` 75 MB · `moonshine-small-en` 214 MB · `moonshine-medium-en` 397 MB.

If any are missing: `scripts/fetch_models.py --arm B`.

### 3. Pilot, then the matrix

```bash
# one rep first -- confirms it transcribes on real hardware
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-tiny-en --langs en --buckets short \
    --reps 1 --label armB-tiny-pilot --no-push

# then the real run
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-tiny-en --langs en --buckets short \
    --reps 4 --label armB-tiny --no-push
```

`--arms B` with no variant runs every variant found on the device. Prefer naming
one explicitly — see the battery note below.

### 4. Score and write up

```bash
.venv/Scripts/python scripts/summarize.py            # all results
.venv/Scripts/python scripts/summarize.py --markdown # README table
```

Then update the README results table and findings, and push.

**Long runs:** `adb am instrument` can outlive a host-side timeout. If the
wrapper dies, the run usually continues on the device — check
`adb logcat -s AsrBench:*` and wait for the JSON rather than re-running.

---

## Budget and constraints

**Battery is the binding constraint, not heat.** The phone was at **62%** at
handover; the floor is 30% (PLAN.md §11.3). Thermals have never been close to a
problem — the phone has stayed under 31 °C all through Phase 1, against a 43 °C
abort threshold.

A 4-rep English run over 40 clips costs roughly **8 points and ~55 minutes**.
All three Moonshine variants in one sitting would not fit in the remaining
budget.

**Run one variant at a time.** Tiny is the interesting one first: at 78 MB, if
its accuracy approaches Arm A's, the size question gets very interesting. Charge
the phone between variants, but note it must fall back below 80% before the
pre-flight gate will allow a run.

---

## What Phase 2 has to answer

1. **Does Moonshine beat Arm A on noisy English?** Arm A scores **33.45% WER**
   on LibriSpeech `test-other`. That failure is the main reason to bundle a
   model at all. If Moonshine does not substantially beat it, the megabytes buy
   nothing and Arm A wins by default.
2. **What does the accuracy-vs-size curve look like** across tiny (78 MB),
   small (224 MB) and medium (416 MB)? This is the open budget question — no
   size limit was set in advance, deliberately.
3. **Does streaming-native actually feel better?** Arm A takes 1.2–2.6 s to show
   any text. Moonshine's `streaming_config.json` implies ~80 ms of algorithmic
   lookahead. If that holds, it is a structural advantage no amount of tuning
   gives a chunked model.
4. **Is `rtf_sustained` under 1.0?** Arm B is the first arm where this is
   measurable at all — it runs in our process, unlike Arm A. Above 1.0 the model
   cannot keep up with a microphone.

Arm B is **English only**: Moonshine publishes no Spanish streaming `.ort`
assets. That gap is a documented finding, not an oversight.

---

## Things to know about the harness

- **Emulator first.** Any new adb path, teardown or cleanup gets exercised on
  `Medium_Phone_API_36.0` before the phone (§11.5). The APK includes `x86_64`
  precisely so native model loading can be debugged there.
- **Nothing is bundled in the APK.** Models are pushed to
  `/sdcard/Android/data/io.github.davamix.asrbench/files/models/` and loaded
  from disk at pinned revisions. The Moonshine SDK's own downloader is
  deliberately bypassed so a silent upstream change cannot alter what was
  measured.
- **The app must create its own directories** before anything is pushed into
  them (`prepareDirs`). `adb push` writes as `shell`, and a shell-created
  directory inside the app's external files dir is not readable by the app —
  the push succeeds and the app sees nothing. `run_bench.py` handles this.
- **`results/superseded/`** holds runs from a harness with known measurement
  bugs. Kept as evidence, excluded from every aggregate. Do not move them back.
- **Session runs use `--reps 1`** and are marked
  `[SINGLE REPETITION -- indicative, not statistical]` in the summary output.
- **Scoring happens on the PC**, never on the device. The device emits
  hypothesis text only, so a scoring bug never costs a re-run.

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
43 °C and pauses 2 minutes every 10 minutes of continuous inference. There is a
`--skip-preflight` flag; treat needing it as a signal to stop and think.

`RECORD_AUDIO` is **not** declared until Phase 5. Every measured run is
file-fed and needs no microphone.

Nothing personal enters the repo: no device serial, no self-recorded audio, no
account identifiers. Check before every push.
