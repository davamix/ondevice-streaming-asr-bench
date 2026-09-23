# Handover — next phase to run

**Currently: Phase 2, Arm B (Moonshine streaming English). Tiny and small are
measured; medium has a one-repetition pilot. One full run remains.**

This file carries whatever phase is next. It is rewritten as each phase
completes; finished phases are written up in [`summaries/`](summaries/).

**For:** a fresh session picking this up with no prior context.

What remains for Phase 2: a full 4-repetition `moonshine-medium-en` run once
the phone is recharged, then the Phase 2 summary. The two methodology fixes
that were open (quiet FLEURS audio, the final-latency stamp) are done, and
both arms were re-run with them.

---

## Initial prompt

> Continue the on-device ASR experiment in `F:\Development\Samples\android-transcription-sample`.
>
> Read `PLAN.md` for the experiment design, `HANDOVER.md` for current state
> and exact commands, and `summaries/phase-1-arm-a.md` for what Phase 1
> established (including the Phase 2 revisions at its top). `README.md` has
> the full results and findings.
>
> Phase 2 is Arm B — Moonshine streaming English. Tiny and small are measured.
> Medium has only a one-repetition pilot. Run the full 4-repetition medium run
> on the physical device, move the medium pilot into `results/superseded/`,
> update the README results, then write `summaries/phase-2-arm-b.md`.
>
> The test device is the owner's only phone. `PLAN.md` §11 is a hard safety
> policy — read it before touching the device. The pre-flight gate in
> `scripts/run_bench.py` enforces most of it; do not bypass it.

---

## Where things stand

| | State |
|---|---|
| Phase 0 (corpus, scorer) | ✅ complete; corpus now includes level-matched FLEURS English |
| Phase 1 (Arm A) | ✅ complete, re-measured in Phase 2 — see [summaries/phase-1-arm-a.md](summaries/phase-1-arm-a.md) |
| Phase 2 (Arm B) | 🔨 tiny and small measured; **medium piloted only** |
| Phases 3–5 (Arms C/D/E) | ⬜ not started |

Repo: https://github.com/davamix/ondevice-streaming-asr-bench (public, push after each phase)

**Not pushed yet.** Everything since `fdcaaa0` is committed locally only. It
includes corrections to published Phase 1 numbers, and the owner had not yet
approved pushing them. Check before pushing.

**What Phase 2 established so far** (full detail in the README, *Results*):

| English | Arm A (0 MB) | tiny (78 MB) | small (224 MB) | medium (416 MB, 1 rep) |
|---|---|---|---|---|
| WER, noisy `test-other` | 33.33% | 15.77% | 8.72% | 6.71% |
| WER, clean, level-matched | 12.60% | 11.25% | 7.71% | 5.94% |
| Final text, noisy | 68 ms | 84 ms | 442 ms | 634 ms |
| `rtf_sustained` | — | 0.40 | 0.72 | 0.77 |
| Peak RSS | — | 363–475 MB | 605 MB | ~920 MB |

---

## Next: the full medium run

```bash
.venv/Scripts/python scripts/devicelib.py --device physical     # check the gate first

.venv/Scripts/python scripts/run_bench.py --device physical \
    --arms B:moonshine-medium-en --langs en --buckets short \
    --reps 4 --label armB-medium --no-push --timeout 7200
```

`moonshine-medium-en` and the full corpus (including `fleurs_en_norm`) are
already on the phone. Drop `--no-push` only if the corpus changed. Add
`--no-build --no-install` if nothing in `bench/` changed since the last
install.

**Then move the pilot out of the aggregate:** move
`results/M2012K11AG-20260923-151948-armB-medium-pilot/` into
`results/superseded/`, and add a row to `results/superseded/README.md` as for
`armB-tiny-pilot`. Until the full run exists, the pilot is the only medium
data and stays in, marked single-repetition.

**Expect heat.** Medium started right after small took the phone from 32.7
to 34.7 °C in eight minutes. The start gate is 35 °C, and the in-run check
pauses before any clip that would start above it. Start medium cool, not
straight after another Moonshine run, and expect cooling pauses to lengthen
the run.

---

## Budget and constraints

**Battery.** The phone was at **46%** at handover, too low for medium: a full
run should cost ~12 points, and the harness checks the 30% floor only before
starting, not during the run. Charge it, unplug it, and let it fall below 80%
before starting (the gate refuses above 80%, and while charging).

**Measured cost per 240 clips (60 English clips × 4 reps):**

| Run | Time | Battery |
|---|---|---|
| Arm A | 39 min | 3 points |
| Moonshine tiny | 39 min | 6 points |
| Moonshine small | 39 min | 10 points |
| Moonshine medium | ~40 min (est.) | ~12 points (est. from pilot) |

Arm A was far slower in Phase 1 (22.8 s per clip against 8.5 s now). See
*Reproducibility* in the README: that session's timing did not reproduce.

---

## What changed in this session (read before trusting older notes)

- **Corpus:** `fleurs_en_norm` is a copy of the 20 FLEURS English clips lifted
  by a static gain to −23 dBFS. The originals are ~40 dB quieter than the
  other sources, and that confounded Phase 1's English-vs-Spanish comparison
  and Moonshine's clean-English numbers. `--langs en --buckets short` now
  means 60 clips, not 40.
- **Final latency:** both arms record `final_after_audio_end_ms`, measured
  from feed start + audio duration. The old `latency_final_ms` stamped
  "speech end" when the feeder returned: up to one frame early with no slip,
  and late when the consumer stalls. The README headline uses the new field.
  `summarize.py` prints it as *from actual audio end*.
- **Scoring:** empty hypotheses are scored as all-deletions. They used to be
  skipped, which flattered arms that heard nothing. Variants are grouped
  separately, and superseded runs are excluded by file name.
- **Harness:** results checkpoint at each mandatory break (`complete: false`
  until the final write). `--session-cap-s` can lower the 10-minute
  continuous-inference cap, never raise it; it exists to exercise the
  checkpoint on the emulator.
- **Phase 1 revisions:** Spanish WER is 8.14% pooled, not 7.73%. "Spanish is
  more accurate than English" is not established. Phase 1's English timing
  did not reproduce. See the top of `summaries/phase-1-arm-a.md`.

---

## Open questions (not blocking)

- **Update interval vs first-text latency.** First text is paced by the SDK's
  0.5 s `DEFAULT_UPDATE_INTERVAL`, and `setUpdateInterval()` is public. A
  short sweep would price first-text latency against `rtf_sustained`. Given
  that small and medium already spend 72–77% of real time, a shorter interval
  may not be affordable above tiny.
- **Why Arm A's Phase 1 English timing was different.** The run began one
  minute after the English language pack installed. Plausible, unconfirmed.
- **Arm B session runs** (5–10 minutes continuous) for `rtf_sustained` and
  thermals. PLAN.md places sustained runs in Phase 5, for surviving arms only.

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

### Score and write up

```bash
.venv/Scripts/python scripts/summarize.py            # all results, per variant
.venv/Scripts/python scripts/summarize.py --markdown # table
.venv/Scripts/python scripts/summarize.py results/<dir>/<file>.json   # one run
```

Check the `no text:` count. A non-zero count deserves a look at which clips
produced it. Arm A's timing is best read per run rather than pooled, because
its Phase 1 English run differed.

**Long runs:** `adb am instrument` can outlive a host-side timeout. If the
wrapper dies, the run usually continues on the device — check
`adb logcat -s AsrBench:*` and wait for the JSON rather than re-running. If
the run itself dies, the last checkpoint is in the device's results dir,
marked `complete: false`.

---

## Things to know about the harness

- **Emulator first.** Any new adb path, teardown, cleanup or harness change
  gets exercised on `Medium_Phone_API_36.0` before the phone (§11.5). The APK
  includes `x86_64` precisely so native model loading can be debugged there.
  Emulator results are gitignored and never published.
- **Nothing is bundled in the APK.** Models are pushed to
  `/sdcard/Android/data/io.github.davamix.asrbench/files/models/` and loaded
  from disk at pinned revisions. All three Moonshine variants are on the phone.
- **The app must create its own directories** before anything is pushed into
  them (`prepareDirs`). `run_bench.py` handles this.
- **Moonshine has no thread-count setting.** Arm B rows say `threads: 4`
  because the harness passes it; ONNX Runtime's default pool actually applies.
- **`results/superseded/`** holds pilots and runs from a harness with known
  measurement bugs. Excluded from every aggregate, by file name. Do not move
  them back.
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
43 °C and pauses 2 minutes every 10 minutes of continuous inference. It does
**not** check battery level mid-run, so size runs to the charge you start
with. There is a `--skip-preflight` flag; treat needing it as a signal to stop
and think.

`RECORD_AUDIO` is **not** declared until Phase 5. Every measured run is
file-fed and needs no microphone.

Nothing personal enters the repo: no device serial, no self-recorded audio, no
account identifiers. Check before every push.
