# Protocol core: sources, rules and the approved adapter command list

The request policy, ELM327-style framing and generic OBD-II decoders are in `:core` (`core/src/main/kotlin/.../core/protocol/`). Their new catalog and continuation behavior have offline tests. Earlier owner-operated sessions observed the original four readings; the expanded catalog has no separate vehicle validation.

## Sources

| Source | What it is used for | How it was obtained |
| --- | --- | --- |
| **ELM327 data sheet, revision ELM327DSJ** (Elm Electronics, 94 pages) | Command termination, prompt, echo, status texts, header and multi-frame layouts, the DTC table, and the 0100, coolant and RPM worked examples | `elmelectronics.com` is blocked by this environment's network policy. Two independent GitHub copies were byte-identical (SHA-256 `43cab005c9e1678325a3ba64066db93e09e2a78a23eb5fa360ee1eaf9a585428`): [PowerBroker2/ELMduino `reference/ELM327DS.pdf`](https://github.com/PowerBroker2/ELMduino/blob/master/reference/ELM327DS.pdf) and [Obeisance/Arduino_OBD_interface_for_Torque `ELM327DS.pdf`](https://github.com/Obeisance/Arduino_OBD_interface_for_Torque/blob/master/ELM327DS.pdf). Retrieved 2026-10-28. Anyone with access to the vendor site should confirm the revision |
| **Official ELM327 data sheet, revision ELM327DSL**: [elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf) (owner-supplied) | The authoritative reference for everything above | **Not fetched here**: the host is blocked by this environment's network policy, as was a filedn.com mirror. Verification used revision ELM327DSJ, an earlier revision of the same data sheet, so page numbers may differ in DSL. Re-check against DSL when the host is allowed |
| [Emtron OBD-II display documentation](https://docs.emtronaustralia.com.au/displays/functions/obd2/index.html) (owner-supplied) | Engine load (04) = A × 100 / 255 %, vehicle speed (0D) = A km/h | Manufacturer implementation documentation, not the SAE standard. The site is blocked here; its PID table was read through a search restricted to `docs.emtronaustralia.com.au` (2026-10-28) and agrees with the owner's citation. **Owner-accepted for this milestone (2026-10-28)** |
| [python-OBD](https://github.com/brendan-w/python-OBD) at commit `a378bdd` (GPL-2.0): `obd/decoders.py`, `obd/UnitsAndScaling.py` | Cross-check of the same two formulas | Read, not copied. Third-party library |
| SAE J1979 / ISO 15031-5 | The governing standard | **Not inspected**: paid standard, not available here |

Page numbers below refer to ELM327DSJ.

## Request policy (F9)

- Only `RequestPolicy` creates a `Request`. Its constructor is internal to `:core`, so Kotlin code in other modules (the Android shell) cannot call it; `internal` is not enforced against Java callers or deliberate compiler-suppression tricks. `AdapterTransport.write` takes only a `Request`.
- Deny by default. Accepted vehicle requests are exactly `01` plus each of the 27 IDs in the catalog below, the eight page IDs `00/20/40/60/80/A0/C0/E0`, and `03`, `07`. Accepted adapter commands are exactly the eight approved below. The SHA-256 of the combined allowlist is pinned in `RequestPolicyTest`.
- Only the exact canonical text is accepted. The ELM327 ignores spaces and control characters and is case-insensitive (p9), so `0 4` would reach a vehicle as mode 04. Variants are rejected, not normalized.
- Empty and whitespace-only lines are rejected: a single carriage return repeats the last command (p9, p12).
- Every AT command outside the approved list is rejected.

## Framing (F1, F5)

- Commands end with a carriage return (p8). Replies end with a carriage return and an optional linefeed, and the `>` prompt means the adapter is idle (p8–9). A reply is complete only at the prompt; text after the first prompt belongs to the next reply.
- NUL bytes are dropped, as the data sheet advises (p9).
- Echo is on by default (`E1`, p17); a first line repeating the command is removed.
- Status texts map to typed results (pp87–89): `?`, `UNABLE TO CONNECT`, `CAN ERROR`, `BUFFER FULL`, `STOPPED`, `BUS ERROR`, `BUS BUSY`, `DATA ERROR`, `<DATA ERROR`, `FB ERROR`, `<RX ERROR`, `LV RESET`, `ACT ALERT`, `LP ALERT`, `ERRxx`, and a `BUS INIT: ...ERROR` line. `SEARCHING...` is skipped. `NO DATA` is its own result and never means "unsupported". An error line anywhere in a reply wins over data lines.
- The caller states the format; it is not guessed from the bytes:
  - **Headers off** (default, p18): each line is one message with no responder identity. A CAN multi-frame reply starts with a three-digit byte count and numbered lines `0:`, `1:`, … (pp42–45). Bytes beyond the count are ignored.
  - **Headers on, 11-bit CAN**: `<ID> <PCI> <data> [padding]` (p41, p43). Bytes after the length in the PCI are ISO-TP padding and are ignored whatever their value, since padding is not required to be zero. Single frames `0n`, first frames `1n nn`, consecutive frames `2n` in sequence, grouped per responder ID. The identifier must be at most `7FF` and a frame at most eight bytes (p43); a single frame carries 1–7 bytes, a first frame is a full frame announcing at least 8, and every consecutive frame except the last is full. Out-of-order, orphaned, oversized or truncated frames are malformed.
  - **Headers on, non-CAN**: three header bytes, data, one check byte (p42). The third header byte identifies the responder. The check byte is dropped, not verified.
  - 29-bit CAN and other shapes are not handled and come back malformed.

## Decoding (F2, F6, F8, F13)

- A reply decodes to a value only when its service byte is the request's service + 0x40 and, for mode 01, its PID byte matches. Otherwise it is malformed. Mode 01 replies must also have the exact data length.
- **Supported PIDs (0100 and continuation pages)**: four bitmap bytes, PID base+*n* is bit `7 − ((n−1) mod 8)` of byte `(n−1)/8` (ELM327DSJ p31). Per-responder pages and uncertainty are retained, with union support for aggregate planning. A missing, `NO DATA`, negative or malformed map stays unknown for that responder/range; confirmed readings from another responder may still be requested. See #45 below.
- **Readings**:

  | PID | Formula | Source |
  | --- | --- | --- |
  | 05 coolant | A − 40 °C | Primary: `41 05 7B` → 83 °C (p31) |
  | 0C RPM | (256A + B) / 4 | Primary: `41 0C 1A F8` → 1726 rpm (pp31–32) |
  | 04 engine load | 100A / 255 % | Manufacturer documentation (Emtron), cross-checked with python-OBD; owner-accepted for this milestone. SAE J1979 not inspected |
  | 0D vehicle speed | A km/h | Same as PID 04 |

- **Trouble codes (03 stored, 07 pending)**: the first hex digit maps to P0–P3, C0–C3, B0–B3, U0–U3 (p34). Non-CAN replies have no count byte, and `00 00` pairs are filler. CAN replies carry a count in the second byte (p34), and anything after the counted codes must be zero padding. The same bytes therefore decode differently by protocol form, and the form must be stated. Codes are attributed to a responder when headers are on, and deduplicated by (responder, code) in first-seen order.
- **Negative responses** `7F <service> <code>` are reported with their code. `7F xx 78` is "response pending" (p45); see the session rules below.

## Approved adapter command list

The owner approved the first seven on 2026-10-28 and `ATSP0` on 2026-10-29 (`AdapterCommand` in code). Every hardware gate in [LIVE_TEST_GATE.md](LIVE_TEST_GATE.md) still applies. All eight configure or query the adapter itself. `ATSP0` sends nothing to the vehicle on its own, but it makes the adapter search protocols on the next vehicle request (see below).

| Command | Kind | What it does (page) | Limits |
| --- | --- | --- | --- |
| `ATE0` | Configuration | Echo off (p17) | The parser also handles echo on |
| `ATL0` | Configuration | Linefeeds off (p20) | The parser also handles linefeeds |
| `ATH1` | Configuration | Headers on (p18), so replies show which responder sent them (p34) | |
| `ATDPN` | Information | Describe the protocol by number (p17) | Reports the adapter's current or last protocol setting. It is **not** proof that any ECU is connected or answering |
| `ATI` | Information | Print the version ID (p18) | Self-reported text, **not** an authenticated or unique device identity |
| `AT@1` | Information | Display the device description (p28) | Self-reported text, **not** an authenticated or unique device identity |
| `ATRV` | Information | Read the input voltage (p24) | The adapter's own input voltage measurement, not a vehicle sensor value |
| `ATSP0` | Configuration | Set the protocol to automatic (p26) | Sent before `0100` in live reads. Replaces a fixed protocol stored by another app, which makes a car on any other protocol fail with `BUS INIT: ...ERROR` (seen on the owner's CAN car, adapter fixed to protocol 3). `SP 0` writes nothing to EEPROM itself, but with the adapter's memory function on, a protocol it finds becomes the saved default (owner accepted). The search is adapter-originated vehicle traffic |

**Still denied (not approved):**
- **Resets and defaults:** `ATZ`, `ATWS`, `ATD`.
- **Protocol selection or search:** every `ATSP` form other than `ATSP0` (including `ATSP 0`, `ATSP00` and `ATSPA0`), and `ATTP`, `ATSS`.
- **Addressing and filters:** `ATSH`, `ATCRA`, `ATCF`, `ATCM`, `ATCP`, `ATTA`, `ATSR`, `ATRA`.
- **Nonvolatile memory:** `ATPP`, `ATSD`.
- **Bus initiation and wakeup:** `ATSI`, `ATFI`, `ATSW`, `ATWM`, `ATKW`.
- **Monitoring:** `ATMA`, `ATMR`, `ATMT`, `ATMP`.
- **Timeouts:** `ATST`. The session layer (#10) will decide whether a timeout command is needed.

**Protocol search (decided 2026-10-29):** the owner approved `ATSP0` and the adapter-originated protocol search it causes on the first vehicle request. The first `0100` after it gets a bounded 30 s search budget (`AdapterProfile.PROVISIONAL.protocolSearchMillis`, unmeasured) instead of the normal 10 s, with the usual single retry. `ATDPN` then reports the protocol found, which is recorded on the session.

## Sessions over a transport (#10)

`ElmObdReader` (`core/.../reader/`) is the shared protocol path for fake, replay and Android Bluetooth Classic transports. Provenance follows the transport's declared kind: `FAKE` gives `SIMULATED`, `REPLAY` gives `REPLAY`, and the Bluetooth transport gives `LIVE`. The latter is a software path, not evidence that the expanded catalog or recording ran on a vehicle.

- **Order.**
  1. `ATE0`, `ATL0`, `ATH1` and `ATSP0`, each of which must answer `OK`. Anything else stops the session.
  2. `0100`, then `ATDPN`, which picks the reply form: protocols 1–5 are non-CAN, and 6 and 8 are 11-bit CAN. Then follow only advertised support-page continuations through `01E0`. Any other protocol answer stops the session, because 29-bit CAN and J1939 are not handled.
  3. `03`, `07`, and only the readings the supported-PID map allows.

  `ATI`, `AT@1` and `ATRV` are approved but not used. A one-pass broad snapshot requests each positively advertised implemented scalar PID once after discovery and code reads. Live recording prepares discovery and codes once, then polls one to four selected advertised catalog PIDs sequentially; neither path queries every advertised but undecoded PID.
- **Idle before writing.** Any character sent while the adapter is working interrupts it, so software must wait for the prompt (p9). Before each write the reader requires that no reply is still unfinished and that the adapter has been quiet for `quietMillis`. Before the first command it cannot know the adapter is at its prompt; quiet is all it checks. Text that arrives then belongs to no request and is discarded, with a `stale_reply_discarded` event. If a partial reply is still pending, the reader waits for its prompt first.
- **Deadlines.** Each exchange has a deadline from write to prompt. Missing it covers no bytes, a partial reply and a missing prompt alike. If a `7F xx 78` line has arrived by then, the deadline is extended **once** by `pendingExtensionMillis`. The adapter applies its own 5 s pending timer for CAN and KWP (p45). The host extension is a second, bounded guard. A pending line counts as resolved only when a later final reply from the **same responder** follows it in the same response. Another responder's reply, or one that came before the pending line, doesn't resolve it. Values and codes from responders that did answer are kept, and each unresolved responder is recorded: a reading gets a "no final reply" failure for that responder, and a code list is kept but not marked as read, so the app never reports "no codes" from it. A response with only pending lines is recorded as "no final reply".
- **Retry.** After a missed deadline the reader sends nothing. It waits up to `resyncMillis` for the prompt. If the prompt comes, the late reply is discarded and the same request is sent **once more**. If it doesn't, or the second try also misses its deadline, the session stops (`FAILED`). There is no reset, no other recovery command and no blind resend.
- **Stops.** A closed transport gives `DISCONNECTED`. No reply, an adapter command answered with anything but `OK`, no usable protocol, or `LV RESET` (the adapter restarted and lost its settings) gives `FAILED`. Data read so far is kept with `complete=false`, and the reason is the last session event. The reader never reconnects; a new connection is a new session.
- **Results.** A reading is a value per responder, `NO DATA`, or a failure with its reason. A code list that wasn't read isn't marked as read, so an empty list never reads as "no codes".
- **Limits.**
  - The deadlines in `AdapterProfile.PROVISIONAL` are invented for offline replay and unmeasured.
  - A stray reply that arrives *after* a write can't be told apart from the real one unless its service or PID differs.
  - Replay proves processing of the scripted bytes, not adapter timing.

Replay scripts are described in [CAPTURE_IMPORT.md](CAPTURE_IMPORT.md). The four bundled ones are synthetic, built from the data sheet examples, with invented timing.

## Fixtures (F16)

Fixtures live in `core/src/test/resources/fixtures/protocol/`, one JSON file each. Required fields:
- `provenance`: `SYNTHETIC`, or `SANITIZED_CAPTURE` with `sha256` and `evidenceLog`.
- `sourceTier`: `primary` (a data sheet example), `constructed` (built from a cited rule) or `manufacturer` (manufacturer implementation documentation, such as Emtron's PID table).
- `source`: the citation.
- `request`, `headers`, `form`, `reply`, `expected`.

`ReadPlanAndHygieneTest` rejects missing metadata, non-allowlisted requests, mode 09 replies and VIN-shaped tokens. All current fixtures are synthetic; no capture has been imported.

## Transport registry (G2)

`transport-registry.txt` lists every `AdapterTransport` implementation in any module's `src/` tree (Kotlin and Java) with its declared kind. Today that is `FakeTransport` and a test spy (`FAKE`), `ReplayTransport` (`REPLAY`) and the Android `BluetoothClassicTransport` (`LIVE`, pinned with `reviewed:issue-12`; see [BLUETOOTH_TRANSPORT.md](BLUETOOTH_TRANSPORT.md)). `verifyTransportRegistry` runs in both `./gradlew -p core check` and the root `check`. It fails on an unlisted, removed, undeclared or anonymous transport that directly names `AdapterTransport`, and on a `LIVE` one whose entry lacks a `reviewed:<reference>` marker. The marker records code review, not permission to run on hardware. It reads each class's supertype list after any annotations and constructor parameters, so a class that only takes a transport as a parameter is not counted. Comments and string literals are blanked out first. A class header that names `AdapterTransport` in a shape the scan can't parse (for example a `where` clause) fails the check rather than being skipped. It is a source-text scan, so an implementation that reaches the interface only through another base type would not be detected; adding such a base type must come with an update to the check.

## Mode 01 catalog and bounded discovery (#45, 2026-10-29)

The implemented 27-scalar catalog is `Mode01Catalog.readings`; `lookup(pid)` accepts an exact two-digit uppercase ID. The eight `pageIds` are separate metadata requests. A broad one-pass snapshot now reads positively advertised implemented IDs; live recording selects up to four distinct IDs. This source check establishes generic decoder layout only, not support in either target car. The SAE J1979 / ISO 15031-5 text was not inspected. The vendor documents below are implementation evidence, and a conflicting implementation can still exist; unknown data remains unknown.

| IDs | Meaning and label distinction | Bytes | Formula (unsigned A/B) | Unit | Inspected primary implementation source |
| --- | --- | ---: | --- | --- | --- |
| 04 | calculated engine load | 1 | 100A/255 | % | Emtron, Service 0x01 Common PIDs |
| 05 | coolant temperature | 1 | A−40 | °C | ELM327DSJ p31; Emtron |
| 06, 07 | short/long fuel trim bank 1 | 1 each | 100A/128−100 | % | ArCx WAT tutorial, PID 06 example (one unsigned byte); OBD Solutions ECUsim 5100 §7.1.1 raw 3C/46; CSS Electronics OBD2 DBC table 06/07 |
| 08, 09 | short/long fuel trim bank 2 | 1 each | 100A/128−100 | % | CSS Electronics OBD2 DBC table 08/09; DashLogic 08/09 bank 2 A-byte definitions |
| 0B, 33 | intake manifold absolute pressure / barometric absolute pressure | 1 each | A | kPa | Emtron, Service 0x01 Common PIDs; ECUsim §7.1.1 for 33 |
| 0C | engine speed | 2 | (256A+B)/4 | rpm | ELM327DSJ pp31–32 |
| 0D | vehicle speed | 1 | A | km/h | Emtron, Service 0x01 Common PIDs |
| 0E | timing advance | 1 | (A−128)/2 | ° | Emtron, Service 0x01 Common PIDs |
| 0F, 46, 5C | intake / ambient / engine oil temperature | 1 each | A−40 | °C | Emtron, Service 0x01 Common PIDs; ECUsim §7.1.1 for 0F/46 |
| 10 | mass air flow | 2 | (256A+B)/100 | g/s | Emtron, Service 0x01 Common PIDs; ECUsim §7.1.1 |
| 11 | absolute throttle position A | 1 | 100A/255 | % | Emtron 11 throttle; Snap-on printed pp35–36 distinguishes absolute from relative |
| 1F | run time since engine start | 2 | 256A+B | s | Emtron; ECUsim §7.1.1 |
| 2F | fuel tank level | 1 | 100A/255 | % | Emtron; ECUsim §7.1.1 |
| 42 | control module voltage, distinct from adapter `ATRV` | 2 | (256A+B)/1000 | V | Emtron; ECUsim §7.1.1 |
| 45 | relative throttle position | 1 | 100A/255 | % | DashLogic vendor listing `$45`; Snap-on printed pp35–36 |
| 47, 48 | absolute throttle position B/C | 1 each | 100A/255 | % | DashLogic `$47/$48`; Snap-on printed pp35–36 |
| 49, 4A, 4B | accelerator pedal position D/E/F | 1 each | 100A/255 | % | DashLogic `$49–$4B` byte/formula; **Snap-on** printed pp35–36 names APP_D/E/F |
| 4C | commanded throttle actuator, not a measured plate position | 1 | 100A/255 | % | DashLogic `$4C`; Snap-on printed p36 |
| 5A | relative accelerator pedal position | 1 | 100A/255 | % | DashLogic `$5A` |

Sources inspected directly: [Emtron display OBD-II documentation](https://docs.emtronaustralia.com.au/displays/functions/obd2/index.html) “Service 0x01 Common PIDs”; [DashLogic OBD-II J1979 PID Listing](https://www.dashlogic.com/docs/technical/obdii_pids) (copyright 2018, `$45–$5A`); [Snap-on Global OBD Vehicle Communication Software Manual](https://www.snapon.com/Files/Diagnostics/UserManuals/GlobalOBDVehicleCommunicationSoftwareManual_EAZ0025B43.pdf) August 2013, EAZ0025B43C Rev. A, printed pp35–36 (PDF pp38–39); [ArCx WAT API Tutorial: OBD-II](https://support.arcx.com/WAT/wat_docs/tutorial-obd-II.obd.html) PID 06 example; [CSS Electronics OBD2 PID overview and own DBC conversion table](https://www.csselectronics.com/pages/obd2-pid-table-on-board-diagnostics-j1979), 06–09 rows; [OBD Solutions ECUsim 5100 User Guide](https://www.scantool.net/scantool/downloads/64/ECUsim_5100_UG.pdf) §7.1.1. ELM327DSJ and mirror/hash details are recorded earlier in this document. DashLogic labels `$49–$4B` “absolute throttle D/E/F” while Snap-on calls them accelerator pedal D/E/F; the latter wording is used. DashLogic includes an optional B byte for banks 3/4 on 06–09; this implementation accepts exactly the independently documented one-byte bank 1/2 layout and rejects an extra byte rather than truncating it. Emtron's fallback polling on failed discovery and its suggested fast rates are not adopted.

A discovery run begins its 180-second wall-clock budget before `ATE0` and includes `ATL0`, `ATH1`, `ATSP0`, `0100`, `ATDPN` and every later page exchange. It follows only a valid responder continuation bit through 00/20/40/60/80/A0/C0/E0, with the existing one-retry prompt/resync exchange. Page bytes are MSB-first, the last bit advertises the next page, and no marker becomes a sensor reading. Each `PageEvidence` preserves ECU, page base, `VALID`/`MISSING`/`NO_DATA`/`NEGATIVE`/`MALFORMED`/`PENDING`/`CONFLICT`, supported scalar IDs, a separate continuation flag, and reason. A zero valid map ends that responder's chain; a missing page leaves its later range unknown. A page never lends support to another responder. A capped E0 continuation remains incomplete.

A malformed frame invalidates that responder's otherwise intact duplicate, while another responder's valid single-frame reply is retained. This recovery applies only to Mode 01; malformed DTC replies remain unread. `NO DATA` mixed with payload invalidates the whole exchange. A positive Mode 01 reply followed by unresolved pending does not supply a numeric value or authorize continuation. Negative-only and conflicting-only pages retain their per-responder status. If a later page stops on an adapter reset, earlier maps survive and no further request is sent.

Schema 2 adds `SupportedPids.Known.pages` with a default empty list and keeps `pids` union and `complete`. Schema 1 is decoded with empty pages, so it can establish only aggregate support by an unidentified responder; it cannot establish per-ECU support. An absent later-range PID remains unknown in a legacy union: the old complete flag covered its first page, not a continuation walk. The codec accepts schema 1/2, rejects unknown versions and invalid provenance, and does not rewrite saved schema-1 files. Schema 2 also carries defaulted live-recording samples, events, selected PIDs and stop reason. Stored outcomes retain elapsed observation time, wall time, responder, unit and missing/failure reasons; old snapshots remain readable. Saved reports count observations by PID/responder/unit and show min/max/last only for actual values. Comparisons mark missing sides, unit mismatches and unknown responders instead of inventing a numeric difference.
