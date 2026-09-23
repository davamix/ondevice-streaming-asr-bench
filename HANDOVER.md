# Handover — next phase to run

**Currently: Phase 2, Arm B (Moonshine streaming English). Tiny is measured;
small and medium are next.**

This file carries whatever phase is next. It is rewritten as each phase
completes; finished phases are written up in [`summaries/`](summaries/).

**For:** a fresh session picking this up with no prior context.

Arm B works on the phone. `moonshine-tiny-en` has a full 4-rep run, scored and
written up in the README. **Two methodology questions are open**, and they
should be settled before small and medium are run (see
[Open before the next run](#open-before-the-next-run)). Otherwise each would
mean re-running those variants later, and battery is the binding constraint.

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for
> current state and exact commands, and `summaries/phase-1-arm-a.md` for what
> Phase 1 established. `README.md` has the full findings and method, including
> Arm B tiny's results.
>
> Phase 2 is Arm B — Moonshine streaming English. `moonshine-tiny-en` is
> measured on the phone. Before running `moonshine-small-en` and
> `moonshine-medium-en`, resolve the open questions in HANDOVER.md
> ("Open before the next run"). Ask the owner which fixes to apply if that is
> not already recorded there.
>
> Then run the remaining variants on the physical device, **one at a time**,
> and update the README results table as numbers come in.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phase 0 (corpus, scorer) | ✅ complete |
| Phase 1 (Arm A) | ✅ complete — see [summaries/phase-1-arm-a.md](summaries/phase-1-arm-a.md) |
| Phase 2 (Arm B) | 🔨 **tiny measured**; small and medium not run |
| Phases 3–5 (Arms C/D/E) | ⬜ not started |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**What tiny established** (full detail in the README, *Arm B* section):

| | Arm A (0 MB) | Arm B tiny (78 MB) |
|---|---|---|
| WER, noisy `test-other` | 33.45% | **15.77%** |
| WER, FLEURS en (very quiet audio) | **9.69%** | 26.56% |
| First text, noisy / FLEURS | 1276 / 1260 ms | 1059 / 1550 ms |
| Final text, noisy / FLEURS | 315 / 123 ms | 114 / 0 ms |
| `rtf_sustained` | unmeasurable | 0.38–0.40 |

---

## Open before the next run

### 1. FLEURS English is ~40 dB quieter than the rest of the corpus

18 of the 20 FLEURS `en_us` short clips sit at a median of −63 dBFS RMS.
FLEURS `es_419` and LibriSpeech sit near −23 dBFS. This is inherited from the
dataset, not the build. Arm A copes; Moonshine does not (it returns *no text*
on 2 of the 20 clips, on every pass). So Arm B's "clean English" number
confounds level with accuracy. It also weakens Phase 1's "Spanish beats
English" finding.

**Proposed fix:** add a loudness-normalised copy of the 20 FLEURS-en short
clips as an extra source (e.g. `fleurs_en_norm`, normalised to about −23 dBFS
to match the other sources). Run it alongside the originals, not instead of
them. Arm A would need a re-run on those 20 clips too, for a fair comparison.

### 2. `latency_final_ms` stamps "end of speech" late when the feeder slips

Both arms stamp speech end when the paced feeder *returns*, not at the
scheduled end of audio (`feedStart + duration`). Arm B's median slip is 250 ms,
because Moonshine runs inference inside `addAudio()`. So its final latency is
understated by up to the final slip, which rows do not record. Slip will grow
with model size, and the bias with it.

**Proposed fix:** additive, so no published metric changes meaning. Record
`final_slip_ms` and a latency against the scheduled end in `extra`, for both
arms. Exercise it on the emulator first (§11.5).

### 3. (Optional) Update interval vs first-text latency

First text is paced by the SDK's 0.5 s `DEFAULT_UPDATE_INTERVAL`, not by the
model. `setUpdateInterval()` is public. A small sweep (e.g. 0.5 / 0.25 / 0.1 s)
on tiny would price first-text latency against `rtf_sustained`. Not in the
plan; only worth doing if responsiveness turns out to decide the arm.

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
identity. The emulator is often attached too, so always pass `--device`.

### 2. Push models

```bash
.venv/Scripts/python scripts/run_bench.py --device physical \
    --push-models moonshine-small-en
```

`moonshine-tiny-en` is already on the phone. Models are downloaded locally
(`models/`, gitignored): tiny 75 MB · small 214 MB · medium 397 MB. If any are
missing: `scripts/fetch_models.py --arm B`.

### 3. Pilot, then the matrix

```bash
# one rep first -- confirms it loads and transcribes on real hardware
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-small-en --langs en --buckets short \
    --reps 1 --label armB-small-pilot --no-push --no-build

# then the real run
.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-small-en --langs en --buckets short \
    --reps 4 --label armB-small --no-push --no-build --no-install
```

**After a pilot, move its results directory into `results/superseded/`** and
add a row to `results/superseded/README.md`, as for `armB-tiny-pilot`. A
single-rep run keeps repetition 0 (the exception exists for sessions), so a
pilot left in place would pool its cold-start rows into the aggregate.
`summarize.py` excludes superseded runs by file name, so the copy the next run
pulls again is excluded too.

Drop `--no-build` if the harness code changed since the last build.

### 4. Score and write up

```bash
.venv/Scripts/python scripts/summarize.py            # all results, per variant
.venv/Scripts/python scripts/summarize.py --markdown # README table
```

Check the `no text:` count in the detailed output. Blank rows are scored as
every word missed (see the README finding *An empty transcript is every word
missed*), and a non-zero count deserves a look at which clips produced them.

Then update the README results table and findings, and push.

**Long runs:** `adb am instrument` can outlive a host-side timeout. If the
wrapper dies, the run usually continues on the device — check
`adb logcat -s AsrBench:*` and wait for the JSON rather than re-running.

---

## Budget and constraints

**Battery is the binding constraint, not heat.** The phone was at **56%** at
handover; the floor is 30% (PLAN.md §11.3). Thermals have never been close to a
problem: Arm B tiny ran 25 minutes at 29.7–30.7 °C, against a 43 °C abort
threshold.

**Measured cost of tiny:** the 4-rep run over 40 English clips took 25 minutes
(including two mandatory 2-minute breaks) and **4 battery points**. The
1-rep pilot took 5 minutes and 1 point. The earlier "8 points per run" estimate
came from Arm A and was pessimistic for Arm B. Small and medium do more compute
per pass, so expect more, but check rather than assume.

**Run one variant at a time.** The gate refuses to start above 80% battery, so
after charging the phone must drain back below 80% before a run.

---

## What Phase 2 has to answer

1. **Does Moonshine beat Arm A on noisy English?** Tiny: **yes**. 15.77% vs
   33.45% WER on `test-other`. The question for small and medium is how much
   further the size buys.
2. **What does the accuracy-vs-size curve look like** across tiny (78 MB),
   small (224 MB) and medium (416 MB)? Tiny is the first point.
3. **Does streaming-native actually feel better?** Tiny: at the *end* of an
   utterance, yes (final text 0–114 ms). At the *start*, no. First text takes
   1.0–1.5 s, because the SDK transcribes every 0.5 s. The model's 80 ms
   lookahead is not what a user sees.
4. **Is `rtf_sustained` under 1.0?** Tiny: 0.38–0.40 per clip. **This is the
   one to watch for medium**, whose weights are 5.3× tiny's. Above
   1.0 it cannot keep up with a microphone. It is also per clip, not over a 5–10
   minute session; the session bucket is still unrun for Arm B.

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
- **Moonshine has no thread-count setting.** Arm B rows say `threads: 4`
  because the harness passes it; ONNX Runtime's default pool actually applies.
  Documented in the README.
- **`summarize.py` groups by variant.** Every Moonshine size reports arm `B`;
  the grouping key includes `extra.variant`, so sizes never pool together.
- **`results/superseded/`** holds pilots and runs from a harness with known
  measurement bugs. Kept as evidence, excluded from every aggregate. Do not
  move them back.
- **Session runs use `--reps 1`** and are marked
  `[SINGLE REPETITION -- indicative, not statistical]` in the summary output.
- **Scoring happens on the PC**, never on the device. The device emits
  hypothesis text only, so a scoring bug never costs a re-run. (The Phase 2
  empty-hypothesis fix is an example: it corrected a published number without
  touching the phone.)

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
