# PR65 handoff for vehicle-aware startup

> Historical. Epic #66 has since been implemented; the delivered architecture is in [VEHICLE_STARTUP.md](VEHICLE_STARTUP.md) and `AGENTS.md`. The evidence boundary and experiment contracts below still apply.

Baseline checked 2026-11-01: [PR65](https://github.com/EternalLiquet/bamboobd2/pull/65) merged to master as `ffe9d207ddce04cdb452e161e03ed6899920fd6f`. Version `0.2.2-part-number.20261031`, code 5. [Project status](../PROJECT_STATUS.md) is the current capability summary; older plans preserve their historical scope. Epic #66 and children #67 through #70 are open planning work, not implemented architecture.

PR65 delivers an Android Bluetooth Classic identification experiment, separate from normal OBD scanning. It reads F187 once on each fixed route (`729→793`, `714→77E`) and conditionally sends one manual flow-control frame per route to collect a 14-byte response. It does not implement epic #66 or a manufacturer registry.

## Evidence boundary

- Owner-provided v4 vehicle capture: Bluetooth startup and adapter setup succeeded; both routes returned positive F187 first frames announcing 14 bytes with the ASCII prefix `7P5`. One route first returned response-pending. These are private owner observations, not development hardware runs.
- v5 software: strict multipart reassembly and one conditional manual FC, with host request/response recording. Software checks use constructed identities/sockets. A later owner-provided v5 transcript shows setup succeeded and route A returned a positive first frame. The host attempted manual FC approximately 517 ms after receiving that frame, then received `NO DATA`; no complete identity was collected and B was not attempted. Successful completion remains unverified.
- Manual FC waits for the adapter prompt. The v4 FF-to-prompt delay was about 524 ms; in the supplied v5 run it was about 516 ms; the ECU deadline is unknown. Failure may be timing/adapter compatibility, not absent ECU support. No automatic-FC fallback.
- Complete identity, physical module attribution, Porsche maintenance definitions and reset support remain unverified. The phase can close without resolving those research questions.

## Integration contracts to preserve

- `IdentificationExperiment.runLive` accepts LIVE only; `run` accepts FAKE only. Imported REPLAY is never executable. Fixed Cayenne requests stay outside `RequestPolicy` and `ElmObdReader`.
- `AppViewModel.startExperiment` owns the connection exclusively. Ordinary bench/live/recording starts respect busy and persisted adapter-reuse state. Cancellation/back/background close the transport; no automatic reconnect or cleanup commands.
- The persisted `identification-safety/power-cycle-required` flag survives restart. After any attempted command, require unplug/reinsert acknowledgment before normal use. Zero-write completion clears it after closure; process death/uncertain writes retain it. A remembered scanner is not evidence that settings or vehicle are unchanged.
- Reports stay app-private; explicit export requires review. They carry plan/app versions, attempted-write times, raw chunks and honest outcome. Raw identifiers/captures must not enter routine logs or Git.
- The main experiment screen uses plain outcome text. Exact setup and raw technical reports remain behind details controls. “Attempt finished” means execution ended, not verified module or maintenance support.

## Next phase, owned by Claude

Start from merged master and recheck live issue state. Follow #67 → #68 → #69 → #70 under epic #66. Reuse the Bluetooth transport and serial owner; do not create competing command streams. Keep generic OBD available for unknown vehicles, bind identity/services to a fresh connection, and never use these Cayenne routes as universal discovery. This handoff authorizes no implementation of that epic in PR65.

PR metadata and CI record the final reviewed SHA and merge result. Run the repository's core/app checks after future behavior changes; do not treat fake responses as new vehicle evidence.

Start #67 in `AppViewModel.kt`, `bluetooth/BluetoothClassic.kt`, `MainActivity.kt` and the existing live/home screens. Define connection ownership and teardown before adding identity or services. #68 must keep generic functionality usable for unknown vehicles. #69's service construction must send no commands, and its capability checks must retain module/variant uncertainty. #70 must invalidate vehicle-scoped state on connection changes and update `AGENTS.md` against the structure actually delivered. Background initialization means preparing code/state, never competing adapter queries.

The issue bodies were written against earlier snapshots and may still call PR65 a draft. Use the merged baseline above and recheck master before implementing. Known separate runtime wording/timing follow-up: after the v5 FC attempt, `NO DATA` is reported as malformed CAN/unexpected text. That message obscures an incomplete/no-continuation outcome; it does not prove a bad vehicle or incompatible hardware. An ECU flow-control timeout is plausible but unproven. Keep any fix separately scoped.

Resolved by epic #66: `app/src/main/res/values/strings.xml`'s `live_explainer` now describes advertised support-page discovery and its 180-second limit.
