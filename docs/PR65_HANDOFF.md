# PR65 handoff for vehicle-aware startup

PR65 delivers an Android Bluetooth Classic identification experiment, separate from normal OBD scanning. It reads F187 once on each fixed route (`729→793`, `714→77E`) and conditionally sends one manual flow-control frame per route to collect a 14-byte response. It does not implement epic #66 or a manufacturer registry.

## Evidence boundary

- Owner-provided v4 vehicle capture: Bluetooth startup and adapter setup succeeded; both routes returned positive F187 first frames announcing 14 bytes. One route first returned response-pending. These are private owner observations, not development hardware runs.
- v5 software: strict multipart reassembly and one conditional manual FC, with host request/response recording. Software checks use constructed identities/sockets. No owner capture proving successful v5 completion has been supplied at this handoff.
- Manual FC waits for the adapter prompt. Prior FF-to-prompt delay was about 524 ms; the ECU deadline is unknown. Failure may be timing/adapter compatibility, not absent ECU support. No automatic-FC fallback.
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
