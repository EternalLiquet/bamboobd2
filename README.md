# BambooOBD2

An evidence-led, AI-assisted diagnostic research project. The first target is the owner's 2014 Porsche Cayenne S, but the research scope includes European and VAG diagnostic stacks. No protocol, ECU address, service-reset procedure, or vehicle compatibility has been verified for this car.

This repository contains a Kotlin/JVM diagnostic core and Android app. Fake and sanitized replay paths, a read-only generic OBD-II decoder, and saved sessions are implemented. The bounded Bluetooth Classic LIVE path defaults to codes only; an explicit choice adds one pass of up to four confirmed generic sensor readings. Finished saved sessions can be reviewed as a privacy-limited text summary and compared as two distinct records of the same source type, with incomplete coverage and missing readings kept separate from numeric differences. Sensor support labels explain the existing capability map without claiming all responders. The repository has no verified target-car integration, reset recipe, ECU flashing support, proprietary dataset, credentials, or raw VIN. Software checks are documented in [BUILD.md](docs/BUILD.md); they do not validate this APK on a phone or vehicle.

## Read first

1. [Safety and evidence boundaries](docs/SAFETY_AND_EVIDENCE.md)
2. [Research and candidate stack](docs/RESEARCH.md)
3. [Workflow and review gates](docs/WORKFLOW.md)
4. [Future Jev adapter proposal](docs/JEV_PROPOSAL.md)
5. [Android milestone 1 design (proposed)](docs/ANDROID_MILESTONE_1.md) and [live-test gate](docs/LIVE_TEST_GATE.md)

For current scope and evidence, start with [project status](PROJECT_STATUS.md). [Issues](https://github.com/EternalLiquet/bamboobd2/issues) hold the live planning backlog; [pull requests](https://github.com/EternalLiquet/bamboobd2/pulls) show proposed changes. This public repository is sufficient to understand the project.

## Current engineering work

The [#41 continuation](https://github.com/EternalLiquet/bamboobd2/issues/41) adds a local saved-session finder for recorded code prefixes and source type, with unreadable files still visible. The bounded software features in [overnight epic #27](https://github.com/EternalLiquet/bamboobd2/issues/27) are merged; [integrated delivery #33](https://github.com/EternalLiquet/bamboobd2/issues/33) tracks the debug APK and exact-source handoff. This is a diagnostic research build, not a vehicle-validated release. Earlier owner-reported phone and adapter observations are recorded in [public evidence](docs/evidence/2026-10-28-hardware-readiness.md); [live-test gate](docs/LIVE_TEST_GATE.md) retains separate owner authorization for further hardware work. See [workflow](docs/WORKFLOW.md) for review and evidence requirements.
