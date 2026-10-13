# Project status

Snapshot: 2026-10-13. Check [Issues](https://github.com/EternalLiquet/bamboobd2/issues) and [PRs](https://github.com/EternalLiquet/bamboobd2/pulls) for live state.

| Level | Current evidence |
| --- | --- |
| Proposed | [Issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2) asks for a first offline, read-only Android diagnostic milestone. Native Kotlin/Compose, Veepeak OBDCheck BLE and a Classic-first connection path are candidates, not selected architecture. Python remains optional offline research tooling; no server is required by the proposal. |
| Implemented | [PR #1](https://github.com/EternalLiquet/bamboobd2/pull/1) merged documentation, project Claude settings, read-only research agents, and evidence templates. There is no diagnostic app, transport, replay harness, or Jev adapter. |
| Car-tested | None. No exact ECU, transport, adapter behavior, oil/service reset procedure, or target-vehicle compatibility has been verified here. |
| Released | No diagnostic product release. |

**Current milestone:** define a bounded first Android milestone and offline acceptance checks in [issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2). Fake transport, sanitized replay and read-only generic diagnostic behavior come before any live test.

**Blockers:** exact hardware/firmware, connection mode and vehicle-specific evidence are missing. A live adapter or vehicle test needs a separate reviewed plan and authorization. Veepeak [lists oil and other maintenance reset as unsupported for OBDCheck BLE](https://veepeak.com/products/obdcheck-ble); this is not evidence for a Cayenne reset or a blanket claim about other tools. No vehicle writes or guessed requests are authorized.

**Next task:** resolve the proposed architecture and evidence checklist in issue #2. Future repository edits still require a proposed diff and explicit owner “go” under `AGENTS.md`.
