# Superseded runs

Kept as evidence for findings documented in the main README, but **excluded
from every aggregate** by `scripts/summarize.py`.

These were produced by a harness with known measurement bugs, or under
conditions that confound them, so their numbers describe the harness or the
conditions rather than the arm:

| Run | Why superseded |
|---|---|
| `*-armA-es-pilot` | Single-rep smoke test, superseded by the full 4-rep run. |
| `*-armA-es-session-pilot` | Device dozed mid-feed: a 371 s clip took 1046 s of wall clock and WER inflated to 22.11%. |
| `*-armA-es-session-v2` | Wake lock fixed, but segmented partial accumulation still counted every update as a full rewrite (12136 revisions on 712 words). |
| `*-armB-tiny-pilot` | Single-rep pilot confirming Moonshine tiny transcribes on the phone. Superseded by the 4-rep `armB-tiny` run. It also exposed the empty-hypothesis scoring bug. |
| `*-armB-medium-pilot` | Single-rep pilot of Moonshine medium, run when the battery was too low for a full run. Superseded by the 4-rep `armB-medium` run. |
| `*-armB-small-session` (2026-09-25 15:08 UTC) | Phase 5 session run made while the phone was in use: a WhatsApp video call ran through the whole Spanish session (camera at ~25 fps), and a "clear recent apps" killed the process during the cooling pause, before the English session. The Spanish row is confounded by the call's CPU load and heat (0.57 of real time against 0.43 per clip, 38.0 °C); the file is a checkpoint. Re-run with the phone idle. |

Exclusion is by name as well as by location. The device keeps every result
file, so a superseded run's JSON gets pulled again into later directories.
`summarize.py` excludes the file each directory here is named for, wherever it
appears.

Deleting them would hide the evidence for two of the more useful findings in
this repo. Averaging them in would be worse. So they live here.
