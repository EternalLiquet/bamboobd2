# Protocol core: sources, rules and the approved adapter command list

This covers issue #9: request policy, ELM327-style framing and the generic OBD-II decoders in `:core` (`core/src/main/kotlin/.../core/protocol/`). All of it runs offline against `FakeTransport`. Nothing here has been sent to an adapter or a vehicle.

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
- Deny by default. Accepted vehicle requests are exactly `0100`, `0104`, `0105`, `010C`, `010D`, `03`, `07`. Accepted adapter commands are exactly the seven approved below. The SHA-256 of the combined allowlist is pinned in `RequestPolicyTest`.
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
- **Supported PIDs (0100)**: four bitmap bytes, PID *n* is bit `7 − ((n−1) mod 8)` of byte `(n−1)/8` (p31). Each responder's map is kept; support is the union. A missing, `NO DATA`, negative or malformed map leaves support **unknown**, and then no readings are requested (F8).
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

The owner approved exactly these seven on 2026-10-28 (`AdapterCommand` in code). **The approval covers code and fake/replay tests only, not sending them to an adapter or vehicle.** Every hardware gate in [LIVE_TEST_GATE.md](LIVE_TEST_GATE.md) still applies. All seven configure or query the adapter itself; none of them sends a message to a vehicle.

| Command | Kind | What it does (page) | Limits |
| --- | --- | --- | --- |
| `ATE0` | Configuration | Echo off (p17) | The parser also handles echo on |
| `ATL0` | Configuration | Linefeeds off (p20) | The parser also handles linefeeds |
| `ATH1` | Configuration | Headers on (p18), so replies show which responder sent them (p34) | |
| `ATDPN` | Information | Describe the protocol by number (p17) | Reports the adapter's current or last protocol setting. It is **not** proof that any ECU is connected or answering |
| `ATI` | Information | Print the version ID (p18) | Self-reported text, **not** an authenticated or unique device identity |
| `AT@1` | Information | Display the device description (p28) | Self-reported text, **not** an authenticated or unique device identity |
| `ATRV` | Information | Read the input voltage (p24) | The adapter's own input voltage measurement, not a vehicle sensor value |

**Still denied (not approved):**
- **Resets and defaults:** `ATZ`, `ATWS`, `ATD`.
- **Protocol selection or search:** `ATSP`, `ATTP`, `ATSS`.
- **Addressing and filters:** `ATSH`, `ATCRA`, `ATCF`, `ATCM`, `ATCP`, `ATTA`, `ATSR`, `ATRA`.
- **Nonvolatile memory:** `ATPP`, `ATSD`.
- **Bus initiation and wakeup:** `ATSI`, `ATFI`, `ATSW`, `ATWM`, `ATKW`.
- **Monitoring:** `ATMA`, `ATMR`, `ATMT`, `ATMP`.
- **Timeouts:** `ATST`. The session layer (#10) will decide whether a timeout command is needed.

**Open point for L2:** the data sheet says ELM327 chips ship set to automatic protocol search (p20). Even without `ATSP0`, the first vehicle request may make the adapter search protocols. That is adapter-originated vehicle traffic and must be decided in the L2 plan ([LIVE_TEST_GATE.md](LIVE_TEST_GATE.md)).

## Sessions over a transport (#10)

`ElmObdReader` (`core/.../reader/`) is the one protocol path. Replay uses it today, and hardware later changes only the transport. It runs over any `AdapterTransport`, and its provenance comes from the transport's declared kind: `FAKE` gives `SIMULATED` and `REPLAY` gives `REPLAY`. No live kind exists.

- **Order.**
  1. `ATE0`, `ATL0` and `ATH1`, each of which must answer `OK`. Anything else stops the session.
  2. `0100`, then `ATDPN`, which picks the reply form: protocols 1–5 are non-CAN, and 6 and 8 are 11-bit CAN. Any other answer stops the session, because 29-bit CAN and J1939 aren't handled.
  3. `03`, `07`, and only the readings the supported-PID map allows.

  `ATI`, `AT@1` and `ATRV` are approved but not used.
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

`transport-registry.txt` lists every `AdapterTransport` implementation in any module's `src/` tree (Kotlin and Java) with its declared kind. Today that is `FakeTransport` and a test spy (`FAKE`) and `ReplayTransport` (`REPLAY`). `verifyTransportRegistry` runs in both `./gradlew -p core check` and the root `check`. It fails on an unlisted, removed, undeclared, anonymous or `LIVE` transport that directly names `AdapterTransport`. It reads each class's supertype list after any annotations and constructor parameters, so a class that only takes a transport as a parameter is not counted. Comments and string literals are blanked out first. A class header that names `AdapterTransport` in a shape the scan can't parse (for example a `where` clause) fails the check rather than being skipped. It is a source-text scan, so an implementation that reaches the interface only through another base type would not be detected; adding such a base type must come with an update to the check.
