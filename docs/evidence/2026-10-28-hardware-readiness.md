# Evidence: hardware readiness for L1 (issue #11)

- Date and observer: 2026-10-28; facts from the owner, recorded by Claude. Documentation only: nothing was powered, paired, scanned or connected.
- Status: owner-reported facts, plus explicit unknowns. No new hardware observation exists.

## Recorded facts (owner-reported)

| Item | Fact | Source |
| --- | --- | --- |
| Phone | Samsung Galaxy S24 series | Owner confirmed, 2026-10-28 |
| Phone model within the series | S24 Ultra | Earlier conversation context; not re-confirmed |
| Adapter | Veepeak OBDCheck BLE (model "BLE", **not** BLE+), Amazon ASIN B073XKQQQW | Owner, 2026-10-28 |
| First connection mode | Bluetooth Classic | Owner decision, 2026-10-28 (issue #12 go) |
| Phone OS | Android 17 (API 37), above the API 26 baseline | Owner, 2026-10-28, from the app's bench screen |
| Pairing | Paired with the phone over Classic long before this work; used for track days with other apps | Owner, 2026-10-28 (pre-existing observation) |
| Earlier phone smoke test | Debug build of `afb8364` installed and ran offline (fake and replay scenarios); owner reported "this test looks good" | Owner, 2026-10-28. No adapter involved |

No serial number, Bluetooth address, VIN or other identifier is recorded here, and none should be.

## Vendor documentation (model level, not this unit)

- Veepeak's product page and Android guidance describe the OBDCheck BLE as supporting Bluetooth LE and Classic Bluetooth. They recommend BLE in apps (no system pairing). For Classic, they say a system pairing request may appear, with PIN 1234. Read 2026-10-28 through a web search restricted to `veepeak.com`, because this environment cannot reach the site. Same limitation as the [earlier note](2026-10-13-veepeak-bluetooth-mode.md); someone with access should re-read [the user instructions](https://veepeak.com/pages/obdcheck-ble-user-instructions) and record the date.
- Veepeak's guidance is not fully consistent. The quick setup tells Android users to pair with PIN 1234, while troubleshooting calls the PIN check mainly an Android head-unit concern. It also says a paired adapter may not show as "connected" in system settings, and that this is normal.
- Classic first was chosen despite the vendor's BLE recommendation (owner decision). Whether this unit works reliably over Classic with this phone is unknown until L1.

## Unknowns (each has a named later stage)

| Unknown | Resolved by |
| --- | --- |
| Printed hardware revision or label text on the adapter; label photo | Owner, before L1 (redact any serial number) |
| Adapter firmware and chip. `ATI` reports "ELM327 v1.5" (see below), but many adapters, including clones, report that string, so it does not identify the chip or firmware | Not resolvable from `ATI` alone; record as reported |
| Whether the unit pairs over Classic with this phone, and how reliably | L1 |
| Chunk sizes, timing and status strings | L1 (the bench check records timing and chunk counts per command) |
| Supply voltage seen by the adapter on the bench | L1 (`ATRV`; this is the adapter's input measurement, not a vehicle value) |
| What the adapter does on a vehicle (protocol search, ECUs answering) | L2 |

## L1 readiness

| Prerequisite | Status |
| --- | --- |
| Host phone | Available (owner). Android 17 (API 37) |
| Adapter | Available (owner). Label details still to be recorded |
| Bench 12 V supply and a correct adapter power fixture | **Not confirmed: L1 is blocked on this.** No purchase or improvised wiring is authorized. Options for the owner: use an existing bench supply with a proper OBD-II power fixture if one is already on hand, obtain one deliberately (owner decision), or keep L1 blocked |
| Operator | The owner |
| Software | The offline bench path from #12 (adapter-only, rejects every vehicle request), pending its review and the L1 go |
| Authorization | **Pending.** L1 needs its own reviewed plan and the owner's written go naming L1 in its issue |

## Owner-run observation outside the L1 gate (2026-10-28)

The owner ran the app's adapter bench check (PR #25, debug build of `1d8d5f9`) with the real adapter. This happened before the L1 go and not on a bench supply, so it is recorded as an owner-run observation, **not** as an authorized L1 result.

- **Setup:** adapter plugged into the car's OBD-II port, engine off, ignition in accessory mode. Already paired with the phone in system settings; the app did no pairing or scanning.
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
- **What it shows:** this unit connects over Bluetooth Classic (RFCOMM/SPP) from this phone through the app's transport, and answers adapter-local commands promptly (about 30–37 ms per command, one chunk each).
- **What it does not show:**
  - Reliability over repeated connections.
  - Behavior on a bench supply.
  - The chip or firmware behind the "ELM327 v1.5" string.
  - Whether `ATRV` is accurate. It is the adapter's own measurement of its input. 11.3–11.4 V in accessory mode with loads on is plausible, but lower than a healthy battery at rest (typically about 12.4–12.7 V); checking with a meter would settle it.
- **Open decision for the owner:** L1 is defined as a bench supply with the adapter out of the car. Either keep that definition and still run L1 on a bench, or revise L1 to allow an adapter-only check in the parked car with the engine off. The revision would be made in the L1 issue before more hardware runs.
