# Project status

Snapshot: 2026-10-27. Check [Issues](https://github.com/EternalLiquet/bamboobd2/issues) and [PRs](https://github.com/EternalLiquet/bamboobd2/pulls) for live state.

| Level | Current evidence |
| --- | --- |
| Proposed | [Issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2): first offline, read-only Android milestone. The owner approved the direction on 2026-10-13 (native Kotlin/Compose on Android first, a platform-independent Kotlin/JVM core so Windows and Linux stay viable, Veepeak OBDCheck BLE as the first adapter rather than a product-wide Bluetooth requirement, Classic Bluetooth first, no required server; provisional Android baseline `minSdk` 26). The design, offline pass/fail checks and slice plan are in [ANDROID_MILESTONE_1.md](docs/ANDROID_MILESTONE_1.md) and [ADR 0001](docs/adr/0001-kotlin-core-android-first.md); they await owner approval on issue #2. Python stays optional offline research tooling. |
| Implemented | [PR #1](https://github.com/EternalLiquet/bamboobd2/pull/1) merged documentation, project Claude settings, read-only research agents, and evidence templates. There is no diagnostic app, transport, replay harness, or Jev adapter. |
| Car-tested | None. No exact ECU, transport, adapter behavior, oil/service reset procedure, or target-vehicle compatibility has been verified here. |
| Released | No diagnostic product release. |

**Current milestone:** get owner approval of the first Android milestone design and acceptance criteria on [issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2). The first coding slice is an offline app skeleton with a simulated session (proposed slices S1a, portable core, and S1b, Android shell). Fake transport, replay fixtures and read-only generic behavior come before any live test; see [LIVE_TEST_GATE.md](docs/LIVE_TEST_GATE.md).

**Blockers:** exact hardware/firmware, connection mode and vehicle-specific evidence are missing. A live adapter or vehicle test needs a separate reviewed plan and authorization. Veepeak documents Bluetooth LE and Classic Bluetooth for the OBDCheck BLE; the owner's unit and firmware are untested ([evidence note](docs/evidence/2026-10-13-veepeak-bluetooth-mode.md)). Veepeak [lists oil and other maintenance reset as unsupported for OBDCheck BLE](https://veepeak.com/products/obdcheck-ble); this is not evidence for a Cayenne reset or a blanket claim about other tools. No vehicle writes or guessed requests are authorized.

**Next task:** owner review of issue #2 and the proposed slices S1a-S7 (drafted in the planning PR; not yet created as issues). No implementation starts until then. Future repository edits still require a proposed diff and explicit owner “go” under `AGENTS.md`.
