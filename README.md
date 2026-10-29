# BambooOBD2

An evidence-led diagnostic research project. The first target is the owner's 2014 Porsche Cayenne S. No Porsche-specific protocol, ECU address, service-reset procedure, or compatibility has been verified for this car.

The Kotlin/JVM core and Android app implement read-only generic OBD-II decoding, fake and sanitized replay, and saved sessions. The Mode 01 catalog has 27 scalar readings. Discovery follows advertised support-page continuations through at most eight pages, retaining responder-specific maps and uncertainty. The Bluetooth Classic LIVE path defaults to codes only; the snapshot choice reads each implemented, positively advertised sensor once. Live recording selects one to four distinct supported PIDs and polls sequentially. It preserves timestamps, responder IDs, missing and failed outcomes, pauses and stop reasons. Finished and partial recordings can be saved, reopened, inspected and compared without treating a series as a simultaneous snapshot. See [protocol details](docs/PROTOCOL.md) and [recording limits](docs/LIVE_RECORDING.md).

The expanded catalog, broad snapshot, polling and saved history have offline test evidence. Earlier owner-operated observations in [#7](https://github.com/EternalLiquet/bamboobd2/issues/7), [#14](https://github.com/EternalLiquet/bamboobd2/issues/14) and [#15](https://github.com/EternalLiquet/bamboobd2/issues/15) concern the original four readings and generic codes only. A responder ID does not identify a vehicle module. Empty codes do not establish that all modules are healthy. Exact brake-switch signals for the BRZ and Cayenne remain [unknown](https://github.com/EternalLiquet/bamboobd2/issues/52). Software checks in [BUILD.md](docs/BUILD.md) do not validate this APK on a phone or vehicle.

## Use and stop

On the Live screen, choose codes only, a one-pass sensor snapshot or Live recording, then select an already paired adapter to connect. Recording prepares support maps and codes once, then offers implemented PIDs positively advertised as supported. Choose up to four and press Start. Pause finishes an in-flight reply and admits no next write until Resume; Stop ends the session. Backgrounding, disconnect, cancellation or a limit ends it and leaves completed samples available for Save or Discard. Leaving an unsaved finished result asks before discarding it. There is no automatic reconnect. Further vehicle operation follows the separate [owner-operated live-test gate](docs/LIVE_TEST_GATE.md).

## Read first

1. [Safety and evidence boundaries](docs/SAFETY_AND_EVIDENCE.md)
2. [Research and candidate stack](docs/RESEARCH.md)
3. [Workflow and review gates](docs/WORKFLOW.md)
4. [Future Jev adapter proposal](docs/JEV_PROPOSAL.md)
5. [Live recording](docs/LIVE_RECORDING.md) and [live-test gate](docs/LIVE_TEST_GATE.md)

For current scope and evidence, start with [project status](PROJECT_STATUS.md). [Issues](https://github.com/EternalLiquet/bamboobd2/issues) hold the live planning backlog; [pull requests](https://github.com/EternalLiquet/bamboobd2/pulls) show proposed changes. This public repository is sufficient to understand the project.

## Current engineering work

The earlier [#33 debug checkpoint](https://github.com/EternalLiquet/bamboobd2/actions/runs/40824886574/artifacts/12876715107) predates the research additions. [#51 delivery](https://github.com/EternalLiquet/bamboobd2/issues/51) awaits review, required checks, merge and verification of the artifact from the actual master commit. There is no #51 master APK or source ZIP claimed here. [Public evidence](docs/evidence/2026-10-28-hardware-readiness.md) records earlier owner-operated work, and the [workflow](docs/WORKFLOW.md) describes review requirements.
