# Vehicle-aware startup (epic #66)

This page describes what [epic #66](https://github.com/EternalLiquet/bamboobd2/issues/66) and its slices [#67](https://github.com/EternalLiquet/bamboobd2/issues/67)–[#70](https://github.com/EternalLiquet/bamboobd2/issues/70) built. Everything here has been run only in host software checks with constructed sockets and replies. No phone, adapter or car has run this flow yet; see [What has and hasn't been exercised](#what-has-and-hasnt-been-exercised).

## The flow

1. **Generic code is ready at launch.** The catalog, decoders, reader and the bundled manufacturer list are plain classes loaded with the app. Nothing waits for identification, and practice and saved sessions work without a scanner.
2. **Saved scanner.** On a normal launch (`MainActivity` `ON_START`, once per ViewModel) the app tries the saved scanner once. With none saved, the home screen offers **Choose scanner**, which lists paired devices. Picking one saves it and starts the same connection flow. Bluetooth off, permission needed or denied, no Bluetooth, the saved scanner no longer paired, the experiment's power-cycle guard, and another tool using the scanner each show one plain message and one action. A startup attempt that was blocked runs once when the block clears (for example after granting permission). Nothing else reconnects by itself.
3. **Identify.** On a new connection the app runs one bounded, generic identification sequence (below) and shows what it learned: make and masked VIN, with model year explicitly unknown, plus the model if the user confirmed it.
4. **Manufacturer service.** If the VIN's manufacturer code maps to a bundled manufacturer, that manufacturer's service object is created and its capabilities are evaluated. Otherwise the car stays "not identified" and only generic scanning is offered.
5. **Every connection starts over.** Disconnect, connection loss, cancel, a new scanner, *Identify again* or backgrounding ends the connection's generation. Its identity, model confirmation, service and results are dropped, and a late reply from the old generation is ignored.

Home screen states: *Set up your scanner* → *Connecting* → *Identifying the car* → ready (identified, partly identified or *Car not identified*), *Connected, but the car didn't answer*, or one actionable problem. The identification transcript and technical reasons are behind **Show technical details**.

## Identification sequence and its limits

`core/.../vehicle/VehicleIdentifier.kt` runs on the shared connection, through the normal `ElmObdReader`, so it inherits that reader's quiet-before-write rule, deadlines and single retry:

| Step | Requests | Notes |
| --- | --- | --- |
| Adapter setup and generic discovery | `ATE0`, `ATL0`, `ATH1`, `ATSP0`, `0100`, `ATDPN`, then only advertised support pages | Exactly what a scan already does |
| Mode 09 support | `0900` once | 11-bit CAN only |
| VIN | `0902` once | Only if a responder advertises PID 02 in its `0900` bitmap |

Source for `0900`/`0902`: SAE J1979 / ISO 15031-5 Service 09. On ISO 15765-4 CAN, `0900` answers `49 00` plus a four-byte bitmap of PIDs 01–20 (PID 02 is bit 6 of the first byte) and `0902` answers `49 02 01` plus 17 ASCII characters, 20 bytes delivered as an ISO-TP first frame and two consecutive frames. With headers on, the ELM327 shows those frames as in its data sheet's multi-frame examples (ELM327DSJ pp42–45), and it sends the ISO-TP flow control itself (automatic flow control is the adapter default; the app never sets it). The decoder (`VehicleInfoDecoder.kt`) accepts only that exact layout and the VIN character set (ISO 3779, no I, O or Q).

Limits, chosen to be conservative rather than complete:

- Legacy protocols (J1850, ISO 9141-2, KWP) send the VIN in a different multi-message layout that is not implemented. Those cars get no `0900`/`0902` and stay unidentified unless the user chooses a vehicle.
- The whole identification has a 120 s limit (`VehicleIdentifier.BUDGET_MILLIS`); discovery keeps its own 180 s cap inside it. At most two Mode 09 requests per attempt, plus the reader's one retry after a missed deadline.
- Responders that return different valid VINs produce *Modules reported different VINs*; neither is chosen.
- Model year stays **Unknown**: the app has not established market and vehicle-class applicability of the North American position-7 year-cycle rule. Neither WMI nor a matching check digit establishes that applicability. No year-based profile exclusion is made from this unverified inference.
- A VIN supplies the manufacturer code; model year remains unknown. **Model, trim, market, engine, modules and supported commands are never derived from it.** The model is a user confirmation for the current connection.

## Privacy

The VIN stays in memory for the connection. `Vin.toString()` is masked; the screen shows only the manufacturer code (`WP1••••••••••••••`). It is never saved, logged, put in a session record, or included in a scan's raw capture: each scan's capture recorder is attached when that scan starts, after identification. A timeout or other interrupted identification closes the socket before a scan can attach a recorder; the user must reconnect. Prompt-terminated unsupported/no-data VIN replies still allow generic scanning. Cancelled or failed scans likewise close their socket. The identification transcript in technical details replaces the `0902` reply with its length.

The saved scanner's name and Bluetooth address are kept in app-private `SharedPreferences` (`saved-adapter`); `android:allowBackup="false"` keeps them out of backups. The address is left out of `SavedAdapter.toString()` and is never written to records, captures, reports or logs. **Forget scanner** clears both values.

## Architecture

| Layer | Location | Responsibility |
| --- | --- | --- |
| Generic OBD | `core/.../protocol/`, `reader/`, `session/`, `live/` | Request allowlist, framing, decoding, reads and recording. No manufacturer knowledge. |
| Generic vehicle evidence | `core/.../vehicle/` | `VehicleIdentifier`, `VehicleInfoDecoder`, `Vin`, `VehicleEvidence`. Standard requests only. |
| Manufacturer services | `core/.../manufacturer/` | `ManufacturerRegistry` (bundled entries and resolution), `ManufacturerService`, `VehicleProfile`, capability requirements and evaluation, `VehicleContext`. One subpackage per make: `porsche/`, `subaru/`. |
| Connection owner | `app/.../connection/VehicleConnection.kt` | Saved scanner, the single Bluetooth transport, generations, identification, leases for scans, teardown. |
| UI | `app/.../ui/HomeScreen.kt`, `VehicleCard.kt`, `ScannerSetupScreen.kt`, `LiveReadScreen.kt` | Status, actions, model confirmation, manufacturer section. |

**One transport, one user.** `VehicleConnection` owns the only `BluetoothClassicTransport`. Identification runs first; scans and recording then borrow it through a `Lease` and give it back. A second scan cannot start while one holds it. The bench check and the identification experiment keep their own reviewed connections, so the app closes the vehicle connection before they open theirs.

**Eager generic, lazy manufacturer.** `ManufacturerRegistry.BUNDLED` holds lightweight entries: make, WMIs, profiles and a factory. A service is constructed only when the current connection's evidence selects its make, at most once per connection (the cache is cleared with the generation). Construction sends nothing, and services hold no transport.

**Capabilities.** Each `CapabilityDefinition` lists requirements (`Make`, `Profile`, `Module`), an implementation (`None` with a reason, or `ResearchExperiment`) and its source. Evaluation gives `RESEARCH_ONLY`, `NEEDS_CONFIRMATION` or `UNAVAILABLE`, always with a reason. `Module` requirements are never met yet because the app collects no module identity, so manual selection cannot unlock them.

**Manual choice.** When no VIN was read, the user may choose a bundled vehicle; it is marked "your choice". When a VIN was read, that manufacturer's profiles are offered for manual confirmation, without excluding them using an unverified VIN year. A VIN with an unrecognized manufacturer code offers none. The choice lasts for the connection only.

## Bundled manufacturers today

- **Porsche** (WMIs WP0, WP1). Profiles: Cayenne 9PA (2003–2010), 92A (2011–2018), 9YA (2019 and later). Capabilities: the [module part-number experiment](IDENTIFICATION_EXPERIMENT.md), *Research only* and only with the 92A confirmed on this connection; oil service, intermediate inspection and main inspection counters, each listed separately as unavailable (no verified definition, issue #58, and module identity required); service resets, not supported.
- **Subaru** (WMIs JF1, JF2, 4S3, 4S4). Profiles: BRZ ZC6 (2013–2020), ZD8 (2022 and later). No Subaru-specific capability; generic scans cover the BRZ.

The experiment is unchanged from #64: same fixed requests, explicit review, separate connection, persisted power-cycle guard, and the deferred v5 `NO DATA` after manual flow control. What changed is how it is reached: only from the Porsche section, and only while the current connection's VIN says Porsche (or the user chose Porsche without a VIN) and the user confirmed the 92A. A remembered scanner or a previous car never enables it.

## Adding a manufacturer or capability

1. Add `core/.../manufacturer/<make>/<Make>.kt` with an object holding the make, WMIs, `VehicleProfile`s and a `ManufacturerEntry` whose factory builds the service. Add the entry to `ManufacturerRegistry.BUNDLED`.
2. Implement `ManufacturerService`: a plain-language `summary` and `CapabilityDefinition`s with honest requirements. Use `Requirement.Module` when the feature depends on a specific module or software variant. Unverified features use `Implementation.None` with a reason; say where the definition comes from in `source`.
3. Don't add manufacturer requests to `RequestPolicy`, `ElmObdReader`, discovery or scans. A future supported operation needs its own reviewed request policy, must run through a `VehicleConnection` lease, and must check the lease's generation. A service must not keep a transport.
4. App integration is automatic: the home screen lists the service's summary and capabilities. A capability that needs its own screen (like the experiment) needs a route and an explicit user start.
5. Tests: extend `ManufacturerRegistryTest` (selection, laziness, requirements), and if the UI changes, `VehicleStartupFlowTest`. Software checks and constructed replies do not show that a car supports anything.

## What has and hasn't been exercised

Exercised in host software (core JVM tests and Robolectric app tests, constructed data only):

- First-run setup → save → identify; relaunch makes one startup attempt; activity recreation makes none.
- Identification: VIN advertised and read; not advertised; `NO DATA`/negative `0900`; disagreeing responders; truncated or invalid VIN; legacy protocol (no Mode 09 sent); no vehicle answer; disconnect during the VIN read.
- Service selection: only the matching service is constructed, once per connection; VIN make can't be overridden; unknown manufacturer code stays generic; capability gating and the separate oil/intermediate/main entries; confirmation sends nothing.
- Switching both ways between constructed BRZ and Cayenne identities on one scanner; the late VIN reply from a cancelled connection is ignored; the experiment is refused unless this connection's Cayenne 92A is confirmed; BRZ connections receive only the generic identification sequence.
- Unknown vehicle keeps generic scanning; unavailable saved scanner, Bluetooth off, forget; codes, snapshot and recording on the shared connection, including disconnect, pause, background and pending-result behaviour; experiment, bench and power-cycle guard ownership.

Not exercised, needs the owner's phone and cars under the [live-test gate](LIVE_TEST_GATE.md):

- Bluetooth reconnection behaviour of the real adapter, including closing the vehicle connection and immediately reopening one for the experiment or bench check.
- Whether the BRZ and the Cayenne advertise and answer `0900`/`0902` through this adapter, and the real reply timing.
- The permission prompt and saved-scanner behaviour across real app restarts on the S24.
- The deferred experiment timing issue is untouched and still unresolved.
