# BambooOBD2

An evidence-led diagnostic research project. The first target is the owner's 2014 Porsche Cayenne S. Owner-operated identification reads returned partial positive F187 replies on two fixed routes; complete module identity, maintenance definitions and service-reset support remain unverified.

The Kotlin/JVM core and Android app implement read-only generic OBD-II decoding, fake and sanitized replay, and saved sessions. The Mode 01 catalog has 27 scalar readings. Discovery follows advertised support-page continuations through at most eight pages, retaining responder-specific maps and uncertainty. The Bluetooth Classic LIVE path defaults to codes only; the snapshot choice reads each implemented, positively advertised sensor once. Live recording selects one to four distinct supported PIDs and polls sequentially. It preserves timestamps, responder IDs, missing and failed outcomes, pauses and stop reasons. Finished and partial recordings can be saved, reopened, inspected and compared without treating a series as a simultaneous snapshot. See [protocol details](docs/PROTOCOL.md) and [recording limits](docs/LIVE_RECORDING.md).

The expanded catalog, broad snapshot, polling and saved history have offline test evidence. Earlier owner-operated observations in [#7](https://github.com/EternalLiquet/bamboobd2/issues/7), [#14](https://github.com/EternalLiquet/bamboobd2/issues/14) and [#15](https://github.com/EternalLiquet/bamboobd2/issues/15) concern the original four readings and generic codes only. A responder ID does not identify a vehicle module. Empty codes do not establish that all modules are healthy. Exact brake-switch signals for the BRZ and Cayenne remain [unknown](https://github.com/EternalLiquet/bamboobd2/issues/52). Software checks in [BUILD.md](docs/BUILD.md) do not validate this APK on a phone or vehicle.

## Use and stop

On first launch, choose your paired scanner; the app remembers it and tries it once on each launch. After connecting it identifies the car with standard reads (VIN manufacturer code; model year remains unknown) and shows any bundled manufacturer section; see [vehicle startup](docs/VEHICLE_STARTUP.md). Unknown cars keep generic scanning. On the Scan screen, choose codes only, a one-pass sensor snapshot or Live recording, then Start. Recording prepares support maps and codes once, then offers implemented PIDs positively advertised as supported. Choose up to four and press Start. Pause finishes an in-flight reply and admits no next write until Resume; Stop ends the session. Backgrounding, disconnect, cancellation or a limit ends it and leaves completed samples available for Save or Discard. Leaving an unsaved finished result asks before discarding it. There is no automatic reconnect. Further vehicle operation follows the separate [owner-operated live-test gate](docs/LIVE_TEST_GATE.md).

## Read first

1. [Safety and evidence boundaries](docs/SAFETY_AND_EVIDENCE.md)
2. [Research and candidate stack](docs/RESEARCH.md)
3. [Workflow and review gates](docs/WORKFLOW.md)
4. [Future Jev adapter proposal](docs/JEV_PROPOSAL.md)
5. [Live recording](docs/LIVE_RECORDING.md) and [live-test gate](docs/LIVE_TEST_GATE.md)

For current scope and evidence, start with [project status](PROJECT_STATUS.md). [Issues](https://github.com/EternalLiquet/bamboobd2/issues) hold the live planning backlog; [pull requests](https://github.com/EternalLiquet/bamboobd2/pulls) show proposed changes. This public repository is sufficient to understand the project.

## Current engineering work

[#51 delivery](https://github.com/EternalLiquet/bamboobd2/issues/51) is closed. [PR #65](https://github.com/EternalLiquet/bamboobd2/pull/65) merged the separate, owner-triggered [Cayenne identification experiment](docs/IDENTIFICATION_EXPERIMENT.md): real Bluetooth reads on `729 -> 793` and `714 -> 77E`, with one conditional manual flow-control frame per route. The owner's v4 capture showed positive first frames with the ASCII prefix `7P5`; a later v5 owner run attempted manual flow control on route A but received `NO DATA`, with no complete identity and no route B attempt. Successful multipart completion remains unverified. After any attempted experiment command, the app requires acknowledgment of an adapter unplug/reinsert before reuse. Imported recordings cannot execute these requests.

[Epic #66](https://github.com/EternalLiquet/bamboobd2/issues/66) adds the remembered scanner, connection-bound vehicle identification and lazily created bundled manufacturer services (Porsche, Subaru) described in [VEHICLE_STARTUP.md](docs/VEHICLE_STARTUP.md). It has host software checks only; no phone or car has run it yet. The current app version is `0.3.1-diagnostic-log.20261101` (code 7), which adds the [diagnostic log export](docs/DIAGNOSTIC_LOG.md); [BUILD.md](docs/BUILD.md) describes checks and artifact identity.
