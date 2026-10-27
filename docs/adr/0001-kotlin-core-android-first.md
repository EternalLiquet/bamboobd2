# ADR 0001: Platform-independent Kotlin/JVM core, Android first, connections behind interfaces

- Status and date: **Proposed**, 2026-10-13 (revised the same day after the owner's portability clarification). Direction approved in chat; design and acceptance criteria await approval on [issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2).
- Context and safety scope: first milestone is read-only generic OBD-II (fault codes, a few live readings, local session save) delivered as an Android app. Windows and Linux must stay viable later. The Veepeak OBDCheck BLE is the first adapter, not a product-wide Bluetooth requirement. No vehicle writes, no manufacturer-specific diagnostics, no reset. Details in [ANDROID_MILESTONE_1.md](../ANDROID_MILESTONE_1.md).
- Alternatives:
  - Kotlin Multiplatform core or Compose Multiplatform UI: more targets and a bigger build; not needed while Android, Windows and Linux all run JVM code. Deferred, with a conversion path if `:core` stays pure.
  - Cross-platform UI frameworks (Flutter, React Native): extra toolchain; Bluetooth still needs per-platform native code.
  - Web app with Web Bluetooth: no Classic Bluetooth, limited permission behavior; unproven for this adapter.
  - Python on the phone or a companion server: contradicts "no required server".
  - Fork an existing open-source OBD app (for example AndrOBD, GPL): licensing and architecture constraints; reference only, per [RESEARCH.md](../RESEARCH.md).
- Decision and evidence links:
  - `:core` is a plain Kotlin/JVM module holding protocol framing, request allowlist, decoding, session logic, session model and `FileSessionStore`. It has no Android, Bluetooth or UI imports (checks G1-G4).
  - Platform shells implement `AdapterTransport`, supply a storage directory (and a `Clock` only if the core default is unsuitable), and render state. `:app` is the only shell in this milestone.
  - Per-adapter facts live in an `AdapterProfile`. **Classic first only if** the owner's adapter is confirmed to support it; retailer snippets suggest the original OBDCheck BLE may be BLE-only (unverified; [evidence note](../evidence/2026-10-13-veepeak-bluetooth-mode.md)). That choice belongs to the Android Bluetooth transport, not to the core.
  - Android permission model from the [official docs](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) (checked 2026-10-13).
  - Python stays optional offline research tooling.
- Trade-offs, license and data implications: two Gradle modules; a hand-written ELM327-dialect parser instead of a library, to keep licensing clear and the allowlist enforceable; a restricted JDK surface in `:core`; desktop users would need a Java runtime. The JVM-vs-Multiplatform comparison is reasoned from general knowledge, not measured. Dependencies are chosen per slice with licenses recorded.
- Reversal condition: adapter evidence that no available medium carries a stable byte stream; a required non-JVM target (revisit Kotlin Multiplatform); or an unacceptable license/permission cost in a chosen dependency.
