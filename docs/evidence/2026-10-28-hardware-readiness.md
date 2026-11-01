# Evidence: hardware readiness for L1 (issue #11)

> Historical owner observations and gaps from October 28/29. Later generic observations are tracked in closed [#7](https://github.com/EternalLiquet/bamboobd2/issues/7), [#14](https://github.com/EternalLiquet/bamboobd2/issues/14) and [#15](https://github.com/EternalLiquet/bamboobd2/issues/15); see [project status](../../PROJECT_STATUS.md) and [PR65 handoff](../PR65_HANDOFF.md) for subsequent evidence. The original failures and unknowns below are retained, not current instructions to repeat those runs.

- Date and observer: opened 2026-10-28, updated 2026-10-29; facts and observations from the owner, recorded by Claude. Claude ran no hardware.
- Status: owner-reported facts, explicit unknowns, and summaries of owner-run observations: an adapter-only check on 2026-10-28 and two codes-only reads on 2026-10-29. Raw captures stay private and are not in Git; no SHA-256 or acquisition log has been recorded for them yet.

## Recorded facts (owner-reported)

| Item | Fact | Source |
| --- | --- | --- |
| Phone | Samsung Galaxy S24 series | Owner confirmed, 2026-10-28 |
| Phone model within the series | S24 Ultra | Earlier conversation context; not re-confirmed |
| Adapter | Veepeak OBDCheck BLE (model "BLE", **not** BLE+), Amazon ASIN B073XKQQQW | Owner, 2026-10-28 |
| First connection mode | Bluetooth Classic | Owner decision, 2026-10-28 (issue #12 go) |
| Test car | 2020 Subaru BRZ | Owner, 2026-10-29 |
| Phone OS | Android 17 (API 37), above the API 26 baseline | Owner, 2026-10-28, from the app's bench screen |
| Pairing | Paired with the phone over Classic long before this work; used for track days with other apps | Owner, 2026-10-28 (pre-existing observation) |
| Earlier phone smoke test | Debug build of `afb8364` installed and ran offline (fake and replay scenarios); owner reported "this test looks good" | Owner, 2026-10-28. No adapter involved |

No serial number, Bluetooth address, VIN or other identifier is recorded here, and none should be.

## Vendor documentation (model level, not this unit)

- Veepeak's product page and Android guidance describe the OBDCheck BLE as supporting Bluetooth LE and Classic Bluetooth. They recommend BLE in apps (no system pairing). For Classic, they say a system pairing request may appear, with PIN 1234. Read 2026-10-28 through a web search restricted to `veepeak.com`, because this environment cannot reach the site. Same limitation as the [earlier note](2026-10-13-veepeak-bluetooth-mode.md); someone with access should re-read [the user instructions](https://veepeak.com/pages/obdcheck-ble-user-instructions) and record the date.
- Veepeak's guidance is not fully consistent. The quick setup tells Android users to pair with PIN 1234, while troubleshooting calls the PIN check mainly an Android head-unit concern. It also says a paired adapter may not show as "connected" in system settings, and that this is normal.
- Classic first was chosen despite the vendor's BLE recommendation (owner decision). This unit connected over Classic from this phone in the owner's runs; long-term reliability is not established.

## Unknowns (each has a named later stage)

| Unknown | Resolved by |
| --- | --- |
| Printed hardware revision or label text on the adapter; label photo | Owner, when convenient (redact any serial number) |
| Adapter firmware and chip. `ATI` reports "ELM327 v1.5" (see below), but many adapters, including clones, report that string, so it does not identify the chip or firmware | Not resolvable from `ATI` alone; record as reported |
| How reliably the unit connects over Classic (one session observed; it was already paired) | Observed during L2 and later use |
| Timing and chunking of vehicle replies (adapter commands: about 30–37 ms, one chunk each) | L2 |
| `ATRV` accuracy (11.3–11.4 V reported in accessory mode) | A multimeter check on the battery, if wanted |
| Which protocol the car uses and how many ECUs answer. Expected CAN, since US cars have used CAN diagnostics since the 2008 model year; not yet observed | The next codes-only read, with `ATSP0` (PR #26) |

## L1 readiness

| Prerequisite | Status |
| --- | --- |
| Host phone | Available (owner). Android 17 (API 37) |
| Adapter | Available (owner). Label details still to be recorded |
| Bench 12 V supply and a correct adapter power fixture | **None, and none planned** (owner, 2026-10-28). The requirement was dropped: L1 is now an adapter-only check in the parked car |
| Operator | The owner |
| Software | The adapter-only bench check from #12 (merged with PR #25; rejects every vehicle request) |
| Authorization | **L1 satisfied by owner decision, 2026-10-28**, on the strength of the owner-run check below. L2 is not authorized by this |

## Owner-run observation outside the L1 gate (2026-10-28)

The owner ran the app's adapter bench check (PR #25, debug build of `1d8d5f9`) with the real adapter, before any L1 go and not on a bench supply. The owner afterwards redefined L1 and decided that this run satisfies it (see the end of this section).

- **Setup:** adapter plugged into the car's OBD-II port, engine off, ignition in accessory mode with accessories drawing power. Already paired with the phone in system settings; the app did no pairing or scanning.
- **Commands sent:** only `ATI`, `AT@1` and `ATRV`, which are adapter-local. The app sent no vehicle request. Whether the adapter itself put anything on the bus while plugged in is unmeasured.
- **Result, as reported by the app (owner's copied report):**

  ```
  BambooBD2 0.1.0-poc bench check (adapter-only), Bluetooth Classic
  Phone: Android 17 (API 37)
  Result: complete
  ATI: ELM327 v1.5 [30 ms, 1 chunk(s)]
  AT@1: OBDII to RS232 Interpreter [36 ms, 1 chunk(s)]
  ATRV: 11.4V [37 ms, 1 chunk(s)]
  ```

  An earlier run the same day, seen in a screenshot, showed the same `ATI` and `AT@1` replies and `ATRV` 11.3V.
- **Observed adapter responses only.** These are not proof of firmware authenticity, battery health or communication with any ECU.
- **What it shows:** this unit connects over Bluetooth Classic (RFCOMM/SPP) from this phone through the app's transport, and answers adapter-local commands promptly (about 30–37 ms per command, one chunk each).
- **What it does not show:**
  - Reliability over repeated connections.
  - Behavior on a bench supply.
  - The chip or firmware behind the "ELM327 v1.5" string.
  - Whether `ATRV` is accurate. It is the adapter's own measurement of its input. 11.3–11.4 V in accessory mode with loads on is plausible, but lower than a healthy battery at rest (typically about 12.4–12.7 V); checking with a meter would settle it.
- **Owner decision (2026-10-28):** with no bench supply, L1 is redefined as this in-car adapter-only check, and **this run satisfies it**. The owner judged that further L1 runs would prove nothing that L2 won't show. The limits above stay recorded; they are not blockers for L2.

## Owner-run codes-only reads (2026-10-29)

The owner ran the app's **Read codes** screen (PR #26, debug build of `2e51c72`, before `ATSP0` was added) twice on the 2020 Subaru BRZ: first with ignition on and engine off, then with the engine running. Both raw captures are kept private, outside Git, and are summarized here.

- **Adapter setup:** `ATE0`, `ATL0` and `ATH1` answered `OK` in 25–42 ms. Echo was already off before `ATE0`, so the adapter appears to keep settings from earlier apps.
- **Vehicle requests:** `0100`, `03` and `07` each ended in `BUS INIT: ...ERROR` after about 2.9 s. The dots arrived about 0.6 s apart.
- **Protocol:** `ATDPN` answered `3`, with no `A`: ISO 9141-2 (K-line, 5 baud init), fixed, with automatic search off. Per ELM327DSJ p26, a fixed non-zero protocol is the only one tried, and a failed init gives exactly this message.
- **Interpretation:** the adapter had protocol 3 stored, and the car, expected to be CAN, never answered a K-line initialization. That the engine-running run failed the same way rules out the ignition position as the cause. No ECU communication took place and nothing was read; the app showed "Not read" without a reason, which PR #26 fixes.
- **Owner decision (2026-10-29):** add `ATSP0` (automatic protocol detection) before `0100`. The owner accepts that a protocol found this way may become the adapter's saved default when its memory function is on. The next read is the owner's to run.
