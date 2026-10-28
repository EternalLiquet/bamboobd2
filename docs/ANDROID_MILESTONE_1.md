# Android milestone 1: offline, read-only diagnostic app

Status: **proposed**, pending owner approval on [issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2). Nothing here is implemented, adapter-tested or car-tested. Decision record: [ADR 0001](adr/0001-kotlin-core-android-first.md). Live-test prerequisites: [LIVE_TEST_GATE.md](LIVE_TEST_GATE.md).

## Scope

**In (owner-approved direction, 2026-10-13):** Android is the first delivery target: a native app in Kotlin + Jetpack Compose, no server, direct phone-to-adapter connection later. The Veepeak OBDCheck BLE is the **first adapter, not a product-wide Bluetooth requirement**. Veepeak documents Bluetooth LE and Classic Bluetooth for this model; Classic first remains the plan, and the owner's unit is untested ([evidence note](evidence/2026-10-13-veepeak-bluetooth-mode.md)). Windows and Linux must remain viable later targets, so everything except the UI shell and platform connections sits in a platform-independent Kotlin/JVM core; see [Portability](#portability-and-reuse). No desktop code is planned or built in this milestone. Features:

1. Read engine fault codes: generic OBD-II stored (Mode 03) and pending (Mode 07) codes.
2. A few live readings (Mode 01): engine load `04`, coolant temperature `05`, RPM `0C`, vehicle speed `0D`.
3. Save a diagnostic session locally.

Slices 1-5 use **simulated and replayed data only**. Python may be used separately for offline research; it is not a runtime dependency.

**Out:** any adapter or vehicle traffic, Bluetooth or USB code, desktop app, clearing codes (Mode 04), freeze frame, VIN (Mode 09), manufacturer-specific modules, UDS/ISO-TP beyond what the adapter exposes, routines, security access, service/oil resets, coding, flashing, network access, cloud. Porsche service reset is a later milestone; nothing here verifies it for this adapter or car.

**What "fault codes" means here:** generic emissions-related OBD-II codes from ECUs that answer the standard request. It does not cover Porsche body, ABS, airbag or other modules. "No codes" means none reported by the responders, not that the car is fault-free. The UI must say so.

## Architecture (five parts, one portable core)

```
 platform shell (:app today)            portable core (:core, plain Kotlin/JVM)
 ───────────────────────────            ───────────────────────────────────────────────────────────
 Compose UI ──────────────────────────► SessionController ──► DiagnosticReader (interface)
                                              │                  ├─ FakeReader       slice 1
                                              │                  └─ ElmObdReader     slices 2-4:
                                              ▼                       RequestPolicy + ElmFraming + ObdDecoder
                                        SessionStore (interface)                    │
 provides a directory ───────────────► FileSessionStore (JSON)          AdapterTransport (interface: write(Request) + byte stream)
                                                                        ├─ FakeTransport / ReplayTransport (tests)
 Bluetooth transport (later, gated) ─────────────────────────────────►  └─ platform transports implement it
```

| Part | Responsibility | Module |
| --- | --- | --- |
| UI | Compose screens; renders state and provenance, never talks to transport | `:app` (Android shell) |
| Session controller | State machine, deadlines, retries, cancellation, builds the session record | `:core` |
| Diagnostic decoding | Request allowlist, ELM327-dialect framing, OBD-II decode into typed results (`ElmObdReader`) | `:core` |
| Adapter transport | Byte-stream interface plus fake/replay implementations; real connections later, one per platform/medium | `:core` (interface, fakes), shell (real) |
| Session storage | Versioned JSON files via `FileSessionStore(directory)`; the shell only supplies the directory. Write-once saving, see [Platform baseline](#platform-baseline-provisional) | `:core` (interface, model, file implementation) |

`:core` is a plain Kotlin/JVM module so checks run with `gradle test` and no emulator. `:app` is the Android module. No Room, DI framework or extra modules until a need is shown.

### Platform baseline (provisional)

Decided now, before slice 1, because slice 1 already builds storage and the Android shell. Android sources below were checked on 2026-10-27 through searches restricted to developer.android.com (result summaries, not full-page reads); recheck when slice 1 is built. It is a product baseline, not the owner's current phone; a phone below it means testing on another device, not lowering the baseline.

- **Android `minSdk` 26 (Android 8.0).** `java.nio.file.Files`, including `Files.move`, is available from API 26 ([Android reference](https://developer.android.com/reference/java/nio/file/Files)). Below 26, library desugaring emulates `java.nio` and some calls may return null or throw `UnsupportedOperationException` ([desugaring guide](https://developer.android.com/studio/write/java11-nio-support-table)), which would make the core's storage behave differently on old devices. Recent Compose releases are moving their own default `minSdk` from 21 to 23 ([Compose Runtime release notes](https://developer.android.com/jetpack/androidx/releases/compose-runtime)), so 26 is not the binding constraint for the UI. Newer `Files` methods such as `readString`/`writeString` were added after 26 ([API diff](https://developer.android.com/sdk/api_diff/36.1/changes/java.nio.file.Files)) and are not used. `compileSdk`/`targetSdk`: current stable when slice 1b is built. Revisit 26 before any release, with current device-distribution data (not checked here).
- **Write-once atomic saving.** Each save writes the JSON to a temporary file in the same directory, flushes it to disk, then moves it with `Files.move(temp, final, ATOMIC_MOVE)` to a final name that has never existed (session id plus save sequence; one writer per session, so names cannot collide). Nothing is overwritten in place, so the platform-specific behavior of an atomic move onto an existing file never comes up, on Android, Windows or Linux. If the file system cannot move atomically, the save fails visibly; there is no silent non-atomic fallback. Leftover temporary files are ignored when listing and cleaned up on start. Reopen picks the highest save sequence per session. The `Files.move` semantics are from general Java knowledge (the Java SE docs were not reachable from the planning session) and are checked by tests: on a plain JVM in slice 1a and on Android in slice 1b.

### Rules to be enforced in code, not prose (none exist yet)

- **Read-only allowlist, deny by default (from slice 2).** `AdapterTransport.write` accepts only a `Request`, whose constructor is `internal` to `:core`, so a platform shell in another module cannot hand the transport raw bytes. Only `RequestPolicy` builds a `Request`. Allowed vehicle requests: `0100` (supported PIDs 01-20; all four readings are in that range), `0104`, `0105`, `010C`, `010D`, `03`, `07`. Adapter setup uses an **exact, fixed, source-cited list** of `AT` commands decided in slice 2 (echo, linefeeds, spaces, headers). Everything else is rejected before the transport sees it, including empty or whitespace-only lines (some ELM327 adapters repeat the last command on a bare CR; unverified), commands that change addressing or protocol (`ATSH`, `ATCRA`, `ATSP`), write adapter memory (`ATPP`, `ATSD`) or start bus initiation (`ATSI`, `ATFI`). Any allowed command that makes the adapter itself transmit (for example protocol auto-search after `ATSP0`) is counted as vehicle traffic in the live gate.
- **Provenance.** Every session carries `SIMULATED`, `REPLAY` or `LIVE`. The controller derives it from the transport/reader's declared kind; callers and UI cannot set it. Every `AdapterTransport` implementation in any module declares its kind, and a root-level Gradle check (G2, added in slice 2 with the transport types, required before slice 7 enables any real transport) scans all modules and compares the implementations and declared kinds with a pinned file. Adding a transport, declaring `LIVE`, or declaring a non-live kind on real hardware fails the check until the live-gate review is done. No transport declares `LIVE` in slices 1-5. UI shows a persistent banner for non-live data. Files with missing or unknown provenance are rejected, never defaulted.
- **Unknown stays unknown.** A PID absent from the supported map is *Unsupported*. A PID whose map could not be read is *Unknown capability* and is not requested. `NO DATA` is not *Unsupported*.
- **No identifiers.** No VIN request, no device or location data, no free-text identifiers in saved sessions.
- **Core independence.** `:core` imports nothing from Android, Compose, Bluetooth or any UI toolkit, and keeps `java.*` use to the storage and fixture packages (G1). Timestamps come from a `Clock` with a default implementation in `:core` (epoch milliseconds, for records only). Deadlines and timeouts use coroutine delays, never wall-clock comparison, so tests use virtual time and clock jumps cannot fire or stall a deadline.
- **Adapter facts are data (from slice 4).** Per-adapter values (deadlines, expected chunk size, medium) move into an `AdapterProfile` record once there is something to put in it, not into decode or controller logic. The first-adapter profile is marked unverified until measured.

### Session record (schema v1, JSON)

`id` (random), `schemaVersion`, `startedAt`, `endedAt`, `provenance`, `sourceLabel` (scenario name or fixture id), `sourceSha256` (fixtures only, separate field), `complete` (bool), `outcome`, `supportedPids` (list or `unknown`), `dtcs[] {code, kind: stored|pending, ecu?}`, `readings[] {pid, value, unit, atMillis}`, `events[] {atMillis, kind, detail}`.

### UI states

Session: `Idle → Connecting → Initializing → Reading → Complete | CompletePartial | Failed | Disconnected | Cancelled`. Per reading: `Value | Unsupported | UnknownCapability | NoData | Timeout | Malformed | NegativeResponse | AdapterError(kind)`, where `kind` is one of `CanError`, `BufferFull`, `Stopped`, `UnableToConnect`, `Other`. A negative response with code `78` (response pending) is not final; it extends the deadline once, bounded (to be checked against J1979/ISO 15765-4 in slice 2).

## Offline pass/fail checks

Fixtures start as **synthetic**, hand-written from public ELM327/J1979 descriptions. They test our parser against our understanding; they say nothing about the real adapter or car until a sanitized capture exists ([evidence log](templates/evidence-log.md)). Timing uses a virtual clock; tests never sleep. Deadline and retry values are provisional parameters, not tuned to any adapter.

| ID | Check | Pass | Fail |
| --- | --- | --- | --- |
| F1 | Fragmented replies: every golden reply split at every byte offset, plus 200 seeded random chunkings (1-byte, 20-byte, prompt `>` alone), CR/CRLF variants, echo on and off, spaces on and off, and the line-numbered multi-frame form (`0:`/`1:`) | Parsed result identical to the unchunked one | Any difference, exception, or a test exceeding 5 s wall clock |
| F2 | Multiple ECUs answer one request (headers on), either order, overlapping and duplicate codes | Deduplicated by (ecu, code), stable order, every ECU kept | Lost or duplicated code, order-dependent output |
| F3 | Timeouts (deadlines on coroutine virtual time, not wall clock): no bytes; partial bytes without prompt; prompt never arrives | `Timeout` at the deadline on the virtual clock; at most 1 retry, sent only after the prompt (or after an explicit adapter reset), because sending early may interrupt the adapter; session follows continue/abort policy | Hang, unbounded retry, partial data shown as a value |
| F4 | Malformed: odd hex length, non-hex, truncated vs declared length, wrong service echo, wrong PID echo, DTC count mismatch, extra bytes, empty. Plus 10,000 seeded mutations in under 10 s | Typed `Malformed(reason)`; never an exception; never a `Value` for a non-matching service/PID. The DTC-count check applies to CAN-style replies only | Exception, crash, or a wrong `Value` |
| F5 | Adapter status text: `?`, `NO DATA`, `UNABLE TO CONNECT`, `CAN ERROR`, `BUFFER FULL`, `STOPPED`, stray `SEARCHING...` | Each maps to its own typed result (`NoData` or `AdapterError(kind)`); `NO DATA` ≠ `Unsupported` | Mis-mapped or merged results |
| F6 | Negative response `7F xx yy`, including `78` | `NegativeResponse(code)` recorded; `78` handled as pending, once, bounded | Treated as success, dropped, or waited on forever |
| F7 | Disconnect before connect, during init, between requests, mid-reply, during save | State `Disconnected`; data read so far kept; session saved with `complete=false`; no auto-reconnect; no UI exception | Lost data, silent retry, crash |
| F8 | Unsupported and unknown capability | Bit clear → not requested, shown *Unsupported*. Map unreadable → shown *Unknown*, PIDs not requested | Request sent for an unsupported/unknown PID; unknown shown as unsupported |
| F9 | Read-only enforcement (`write` takes only a `Request`; a test shows a raw-bytes call does not compile or is not reachable from outside `:core`): property test over arbitrary request strings; explicit rejects for Mode 04, 08, 09, `10`-`3E`, `22`, `27`, `2E`, `2F`, `31`, `34`-`36`, empty/whitespace lines, `ATSH`, `ATCRA`, `ATSP`, `ATPP`, `ATSD`, `ATSI`, `ATFI` | Accepted iff in the allowlist; a spy transport receives zero rejected bytes; the allowlist hash is pinned in the test and any change fails it until reviewed | Any non-allowlisted byte reaches the transport |
| F10 | Provenance (banner part is Android shell only): all screens render inside one `DiagnosticScaffold` that draws the banner for `SIMULATED`/`REPLAY` (Compose UI test in `:app`); storage round trip keeps provenance; missing/unknown provenance rejected | All hold; only the reviewed Bluetooth transport declares `LIVE`, and in slice 7 it runs the adapter-only bench check, never a session | Simulated data without a banner; defaulting to a real scan |
| F11 | Android shell only. Manifest: merged manifest, from a named Gradle task. Slices 1-5: no `BLUETOOTH*`. Revised for slice 7 (#12): exactly `BLUETOOTH` (`maxSdkVersion` 30) and `BLUETOOTH_CONNECT` ([BLUETOOTH_TRANSPORT.md](BLUETOOTH_TRANSPORT.md)) | No other `BLUETOOTH*`, no location, no `INTERNET` | Any of them present |
| F12 | Storage: write-once atomic save (temp file, flush, atomic move to a new name); crash between write and move; leftover temp file; truncated/corrupt file; unknown `schemaVersion`; VIN-shaped strings | No final file ever holds partial content; leftover temp files ignored; reopen returns the latest complete save; corrupt files listed as unreadable without crash; unknown version rejected; a delimited 17-character VIN-shaped token (letters I, O, Q excluded) is never written; the `sourceSha256` field is exempt | Crash, partial file visible, VIN-shaped data saved |
| F13 | Decode goldens (formulas cited from a named source in the slice-3 PR): `41 0C 1A F8` → 1726 rpm; `41 0D 32` → 50 km/h; `41 05 7B` → 83 °C; `41 04 80` → 50.2 %; CAN-style `43 01 01 33 00 00 00` → `P0133` (count byte `01`, padding ignored); the same bytes decoded as the non-CAN form (no count byte) are a *different* result, so each golden names its protocol form | Exact match | Any deviation |
| F14 | Cancellation mid-session | `Cancelled`, partial session saved with `complete=false` | Reader keeps running or data lost |
| F15 | Replay determinism with injected clock and id | Same fixture twice → byte-identical session JSON | Differences |
| F16 | Fixture hygiene: every fixture has metadata (`SYNTHETIC` or `SANITIZED_CAPTURE`, protocol form, original SHA-256 and evidence-log link for captures, expected output) | Lint passes | Lint fails or VIN-shaped content found |

Portability checks (added with the platform-independence requirement). G4 is a reviewer checklist item, not an automated check: the Android shell contains no parsing, allowlist, retry or provenance logic, and its tests cover wiring only.

| ID | Check | Pass | Fail |
| --- | --- | --- | --- |
| G1 | Core purity, one scan in `gradle test` (slice 1a): `:core` is a `kotlin("jvm")` module; no `android.*`, `androidx.*`, Compose or Bluetooth imports; dependencies limited to a pinned list (Kotlin stdlib, coroutines, serialization); `java.*` imports only inside the storage and fixture packages, plus `java.util.UUID` for ids (`java.lang`, e.g. `System.currentTimeMillis` for the default `Clock`, needs no import and is allowed). Plus an API-signature check of `:core` against the provisional Android baseline, API 26 (tool chosen and shown to catch a deliberate API-27+ call in slice 1a) | All pass | Any platform import, unlisted dependency, stray `java.*`, or API above 26 |
| G2 | Transport registry (added in slice 2; must pass before slice 7 enables any real transport): root-level Gradle check over all modules compares every `AdapterTransport` implementation and its declared provenance kind with a pinned file | Matches | Unlisted transport, `LIVE` declared without a `reviewed:<reference>` entry (added in slice 7), or a non-live kind on real hardware |
| G3 | Headless reuse: a `:core` test runs a complete simulated session with FakeReader (slice 1a; extended to ElmObdReader over ReplayTransport in slice 5), saves it with `FileSessionStore` into a temp directory and reopens it, with no Android classes on the classpath | Passes on a plain JVM | Needs Android, an emulator or a UI |

## Portability and reuse

**Why a plain Kotlin/JVM core.** Kotlin/JVM code can run on Android (as bytecode on ART, not on a JVM), Windows and Linux, so one module can serve all three with no new framework, provided the JDK surface stays small (G1). Options compared (reasoning from general knowledge; no benchmark or source was checked for this document):

| Option | Gain | Cost | Verdict |
| --- | --- | --- | --- |
| Plain Kotlin/JVM `:core` + thin platform shells | No new framework; plain `gradle test`; Windows/Linux reuse unchanged | JVM-only; desktop users need a Java runtime or a bundled one; JDK API surface must be policed (G1) | **Proposed** |
| Kotlin Multiplatform core | Adds iOS/native targets | `expect/actual`, more complex build, only valuable if a non-JVM target is wanted | Defer. If G1 holds, converting `:core` later should be mostly mechanical (expectation, not verified) |
| Compose Multiplatform for UI too | One UI codebase | Larger framework in the first slice; the UI here is thin | Defer; revisit when a desktop shell is actually scheduled |
| Rust/C++ core with bindings | Non-JVM reuse | More tooling, FFI, two languages | Reject for now |

**What a future desktop app would add** (not built, not scheduled): a shell module with its own UI (Compose Desktop, a CLI or other), a serial `AdapterTransport` for a compatible USB adapter, and a data directory for `FileSessionStore`. It would reuse unchanged: `RequestPolicy`, `ElmFraming`, `ObdDecoder`, `SessionController`, `FileSessionStore`, the session schema, fixtures, and the `:core` checks (F1-F9, F12-F16, G1-G3). F10's banner, F11 and G4 are Android-shell checks, so a desktop shell gets its own equivalents.

**What "compatible USB adapter" means here.** One that speaks the same ELM327-style text dialect over a serial byte stream (many USB adapters appear to the OS as a serial port; unverified for any specific model). Adapters with different protocols (for example J2534 pass-thru or SocketCAN) need a different reader behind `DiagnosticReader`; that is a new design, not a transport swap. Desktop Bluetooth and BLE use different platform APIs again and are not assumed to work.

**Rules for reuse to stay true.**
- Platform shells implement only `AdapterTransport`, supply a storage directory (and a `Clock` only if the core default is unsuitable), and render state.
- A new host platform or transport is a new live-gate stage; approval for Android does not carry to a desktop or a USB adapter ([LIVE_TEST_GATE.md](LIVE_TEST_GATE.md)).
- When a second host exists, the same replay fixtures and expected session JSON must pass on both.
- Windows serial drivers, Linux serial-port permissions and serial-library choice and license are unevaluated.

## Slices

Each is a separate issue with acceptance criteria. Order matters; none starts without owner go.

1. **First coding slice, kept lean**, two issues: 1a, `:core` with a FakeReader simulated session, cancellation, write-once save and reopen, and headless tests (G1, G3); 1b, Android shell with the persistent simulation banner, cancel, save and reopen. Timeout and disconnect scenarios wait for slice 4.
2. `Request`/`AdapterTransport` types, request policy, ELM327-style framing and parser over a fake transport, fixture format and hygiene lint, and the transport registry (G2).
3. Generic OBD-II decoders (`0100` supported-PID map, four PIDs, Mode 03/07).
4. Session controller hardening wired to `ElmObdReader` (timeouts, retries, disconnect, cancel).
5. Replay harness and the procedure for importing a sanitized capture.
6. Adapter and host-phone facts for the live gate, documentation only: label, vendor documentation, already-existing observations, host phone model and Android version. No hardware is powered or connected.
7. **Blocked:** Android Bluetooth transport for the first adapter. Implemented after slices 2-6 with G2 in place, and reviewed and pinned in G2. Running it on any hardware requires the separate L1 go. Classic first unless L1 shows the owner's unit does not work over Classic.

Not scheduled: a desktop shell and a USB/serial transport (see Portability).

## Open questions

- **Classic first vs BLE first.** Veepeak documents both modes for this model, but its Android guidance recommends Bluetooth LE (no system pairing) and says Classic may prompt for system pairing ([evidence note](evidence/2026-10-13-veepeak-bluetooth-mode.md)). Classic first stays the owner-approved plan; L1 shows how the owner's unit actually behaves. To be confirmed before slice 7.
- **`minSdk` 26** is provisional; revisit before any release with device-distribution data.
- Whether `ATSP0` auto-detect is acceptable for a first live test or the protocol should be fixed after a bench check.
