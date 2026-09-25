# Summaries

One short write-up per completed phase: what was measured, what it means, and
the verdict. Problems hit along the way live in the [main README](../README.md)
findings section, not here.

| Phase | Document | Subject |
|---|---|---|
| 1 | [Arm A](phase-1-arm-a.md) | The platform on-device recognizer (0 MB), both languages, clean and noisy. Revised in Phases 2, 3 and 4. |
| 2 | [Arm B](phase-2-arm-b.md) | Moonshine streaming English, tiny / small / medium (78–416 MB), against Arm A. Revised in Phases 3 and 4. |
| 3 | [Arms D and E](phase-3-arms-d-e.md) | Parakeet (670 MB) and Whisper base / small (161 / 375 MB), both languages, via sherpa-onnx with Silero VAD. Revised before and in Phase 4. |
| 4 | [Spanish, and the answer](phase-4-spanish.md) | Arm C (Moonshine `base-es`, 65 MB) and Moonshine's Spanish streaming model (122 MB) against Arm A and Parakeet; English re-measured on 100 clips; the answer to PLAN.md §1. |

For the full method and reproduction steps see the [main README](../README.md);
for the experiment design and the reasoning behind each decision see
[`PLAN.md`](../PLAN.md). The next phase to run is described in
[`HANDOVER.md`](../HANDOVER.md).
