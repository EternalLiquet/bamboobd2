# Project status

Snapshot: 2026-10-29 at master `debe02d1fe77182eff548938428d8f4f45a6370d`. Check [Issues](https://github.com/EternalLiquet/bamboobd2/issues) and [PRs](https://github.com/EternalLiquet/bamboobd2/pulls) for live state.

| Level | Current evidence |
| --- | --- |
| Planned | [Overnight epic #27](https://github.com/EternalLiquet/bamboobd2/issues/27) covers build preflight [#29](https://github.com/EternalLiquet/bamboobd2/issues/29), one-shot supported readings [#30](https://github.com/EternalLiquet/bamboobd2/issues/30), saved summaries [#31](https://github.com/EternalLiquet/bamboobd2/issues/31), comparison [#32](https://github.com/EternalLiquet/bamboobd2/issues/32), and integrated delivery [#33](https://github.com/EternalLiquet/bamboobd2/issues/33). |
| Implemented | [PR #26](https://github.com/EternalLiquet/bamboobd2/pull/26) merged the L2 codes-only LIVE software path. The core has generic OBD-II decoders, fake/sanitized replay, bounded request policy, capture and saved sessions; Android has Bluetooth Classic connection, read-only LIVE codes, pending-result guards and save/reopen. [BUILD.md](docs/BUILD.md) names host checks. |
| Owner-operated observations | The [public evidence note](docs/evidence/2026-10-28-hardware-readiness.md) records an earlier phone fake/replay smoke test, an adapter-local check, and two codes-only attempts on a 2020 Subaru BRZ that received no ECU reply. These do not validate the overnight features or the target car. [Issue #14](https://github.com/EternalLiquet/bamboobd2/issues/14) retains the L2 evidence closeout; [#15](https://github.com/EternalLiquet/bamboobd2/issues/15) retains the separate L3 hardware gate, while #30 is only a software slice. |
| Released | No diagnostic product release. A debug APK from #29 is a build baseline, not a vehicle-validated release. |

**Current milestone:** Build and CI preflight [#29](https://github.com/EternalLiquet/bamboobd2/issues/29), followed by the linked tasks in #27. The 2026-10-29 handoff authorizes only that bounded batch as detailed in [AGENTS.md](AGENTS.md).

**Blockers:** Exact vehicle ECU, adapter firmware and target-specific protocol evidence remain unknown. No vehicle writes, resets or guessed requests are authorized. Owner-operated hardware validation needs the separate [live-test gate](docs/LIVE_TEST_GATE.md).
