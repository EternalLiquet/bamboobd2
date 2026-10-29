# BambooOBD2

An evidence-led, AI-assisted diagnostic research project. The first target is the owner's 2014 Porsche Cayenne S, but the research scope includes European and VAG diagnostic stacks. No protocol, ECU address, service-reset procedure, or vehicle compatibility has been verified for this car.

This repository contains a Kotlin/JVM diagnostic core and Android app. Fake and sanitized replay paths, a read-only generic OBD-II decoder, saved sessions, and a Bluetooth Classic path with bounded LIVE codes reads have been implemented. The repository has no verified target-car integration, reset recipe, ECU flashing support, proprietary dataset, credentials, or raw VIN. Software checks are documented in [BUILD.md](docs/BUILD.md); no phone or vehicle test is established by those checks.

## Read first

1. [Safety and evidence boundaries](docs/SAFETY_AND_EVIDENCE.md)
2. [Research and candidate stack](docs/RESEARCH.md)
3. [Workflow and review gates](docs/WORKFLOW.md)
4. [Future Jev adapter proposal](docs/JEV_PROPOSAL.md)
5. [Android milestone 1 design (proposed)](docs/ANDROID_MILESTONE_1.md) and [live-test gate](docs/LIVE_TEST_GATE.md)

For current scope and evidence, start with [project status](PROJECT_STATUS.md). [Issues](https://github.com/EternalLiquet/bamboobd2/issues) hold the live planning backlog; [pull requests](https://github.com/EternalLiquet/bamboobd2/pulls) show proposed changes. This public repository is sufficient to understand the project.

## Current engineering work

The Android foundation and codes-only software path are merged; [overnight epic #27](https://github.com/EternalLiquet/bamboobd2/issues/27) tracks the next offline-tested features and APK. The [live-test gate](docs/LIVE_TEST_GATE.md) keeps any owner-operated hardware checks separate. See [workflow](docs/WORKFLOW.md) for review and evidence requirements.
