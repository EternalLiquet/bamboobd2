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
| Earlier phone smoke test | Debug build of `afb8364` installed and ran offline (fake and replay scenarios); owner reported "this test looks good" | Owner, 2026-10-28. No adapter involved |

No serial number, Bluetooth address, VIN or other identifier is recorded here, and none should be.

## Vendor documentation (model level, not this unit)

- Veepeak's product page and Android guidance describe the OBDCheck BLE as supporting Bluetooth LE and Classic Bluetooth. They recommend BLE in apps (no system pairing). For Classic, they say a system pairing request may appear, with PIN 1234. Read 2026-10-28 through a web search restricted to `veepeak.com`, because this environment cannot reach the site. Same limitation as the [earlier note](2026-10-13-veepeak-bluetooth-mode.md); someone with access should re-read [the user instructions](https://veepeak.com/pages/obdcheck-ble-user-instructions) and record the date.
- Veepeak's guidance is not fully consistent. The quick setup tells Android users to pair with PIN 1234, while troubleshooting calls the PIN check mainly an Android head-unit concern. It also says a paired adapter may not show as "connected" in system settings, and that this is normal.
- Classic first was chosen despite the vendor's BLE recommendation (owner decision). Whether this unit works reliably over Classic with this phone is unknown until L1.

## Unknowns (each has a named later stage)

| Unknown | Resolved by |
| --- | --- |
| Exact Android version and API level of the phone (must be at least API 26) | Owner reads it from settings, or from the app's bench screen ("This phone: Android … (API …)"), before L1 |
| Printed hardware revision or label text on the adapter; label photo | Owner, before L1 (redact any serial number) |
| Adapter firmware version | L1 (`ATI` reply) |
| Whether the unit pairs over Classic with this phone, and how reliably | L1 |
| Chunk sizes, timing and status strings | L1 (the bench check records timing and chunk counts per command) |
| Supply voltage seen by the adapter on the bench | L1 (`ATRV`; this is the adapter's input measurement, not a vehicle value) |
| What the adapter does on a vehicle (protocol search, ECUs answering) | L2 |

## L1 readiness

| Prerequisite | Status |
| --- | --- |
| Host phone | Available (owner). Android version still to be recorded |
| Adapter | Available (owner). Label details still to be recorded |
| Bench 12 V supply and a correct adapter power fixture | **Not confirmed: L1 is blocked on this.** No purchase or improvised wiring is authorized. Options for the owner: use an existing bench supply with a proper OBD-II power fixture if one is already on hand, obtain one deliberately (owner decision), or keep L1 blocked |
| Operator | The owner |
| Software | The offline bench path from #12 (adapter-only, rejects every vehicle request), pending its review and the L1 go |
| Authorization | **Pending.** L1 needs its own reviewed plan and the owner's written go naming L1 in its issue |
