# Repository instructions

Read `docs/SAFETY_AND_EVIDENCE.md` and `docs/WORKFLOW.md` before planning changes. `CLAUDE.md` adds Claude-specific roles. The project-files companion records intent; the code and verified behavior live here.

**Future work approval gate:** For every edit request after the 2026-10-13 documentation bootstrap, inspect first, present the proposed file-by-file diff or a precise patch for owner review, and wait for the owner's explicit **“go”** before editing files. The 2026-10-13 authorization covered documentation and agent scaffolding only. A general request, plan approval, or tool availability is not the future “go.” If the proposal changes, show the revised diff and wait again.

Default to offline and read-only. Never transmit to a vehicle, reset service state, clear DTCs, perform routines or actuator tests, request security access, code an ECU, or flash firmware as an ordinary software task. Do not store raw VINs, private details, credentials, keys, proprietary diagnostic datasets, or unapproved raw vehicle captures in Git. Do not infer Porsche support from generic OBD-II or VAG support.

Separate observation, hypothesis, and verified interpretation. Every protocol or signal claim needs a source, capture offset or timestamp when applicable, confidence, and a competing explanation. Unknown ECU or transport means unknown capability. Use deterministic code for bytes, bounds, permissions, retries, and timing; model outputs never grant safety permission.

For behavioral changes, prefer a failing regression test, sanitized golden traces, offline replay, structural properties, and independent diff review. Report the exact commit and checks actually run. Do not invent a working integration, protocol fact, test command, or validation result.
