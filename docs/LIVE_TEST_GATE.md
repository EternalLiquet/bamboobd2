# Live-test gate

Nothing below is authorized. This lists the evidence and permissions a later adapter or parked-car test needs. Each stage needs its own issue, a reviewed plan, and the owner's written **go** in that issue naming the stage. Approval of one stage does not carry to the next. See also [safety boundary](SAFETY_AND_EVIDENCE.md).

## Stages

| Stage | What happens | Vehicle traffic |
| --- | --- | --- |
| L0 | Offline: fake reader, fake transport, synthetic and sanitized replay (current) | None |
| L1 | Bench: adapter powered from a bench 12 V supply, not plugged into a car; the host device (phone first) connects; adapter-local commands only | None |
| L2 | Parked, ignition on, engine off: allowlisted generic reads only (`0100`, `03`, `07`) | Read-only, generic |
| L3 | Parked, engine running outdoors or ventilated, in park with parking brake: the four live PIDs | Read-only, generic |

Driving tests, code clearing, resets, coding and flashing are outside every stage.

A stage is approved for one **host platform, transport medium and adapter model** together. A desktop app, a USB adapter, or a different adapter model is a new set of stage issues; Android approval does not carry over.

## Evidence needed before L1

- **Adapter:** exact model from the label (OBDCheck BLE vs OBDCheck BLE+; listings conflict), photo of the label, any adapter-reported version (`ATI` response is adapter-local), and whether it pairs in Android Bluetooth settings (Classic) or only appears in an app's BLE scan. Vendor documentation to be retrieved by the owner or a reviewer with access.
- **Host device (Android phone first):** make, model, Android version and API level. Expected prompts (to be confirmed against [Android docs](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), checked 2026-10-13): connecting to an already-bonded device needs `BLUETOOTH_CONNECT` (Android 12+) or legacy `BLUETOOTH` (11 and lower); any discovery or scan, Classic or BLE, needs `BLUETOOTH_SCAN` on 12+, and legacy `BLUETOOTH_ADMIN` plus location on 11 and lower. Pairing may be done in system settings, avoiding discovery in the app.
- **Software:** exact commit, allowlist hash, checks passing on that commit (G1-G3, plus the G4 review checklist), independent diff review, and the pinned transport registry (G2) unchanged or reviewed.
- **Data handling:** raw capture stored outside Git with SHA-256, acquisition context and loss recorded in an [evidence log](templates/evidence-log.md); VIN never requested; sanitized fixture reviewed before it enters the repo.

## Additional evidence before L2 and L3

- L1 results recorded: connection path works, chunk sizes observed, adapter status strings seen. Measured, not assumed.
- Unknown until observed: which protocol the adapter reports for the car, whether the car answers generic OBD-II on it, how many ECUs answer, and what any gateway does. Do not assume.
- **Adapter-originated traffic is vehicle traffic.** Protocol auto-search (`ATSP0`) and any adapter keep-alive or wake-up messages are not requests from our code. Whether this adapter sends any, and on which protocol, is unmeasured; record what is observed at L1/L2 and decide before L3.
- Vehicle set-up: stationary, parking brake on, battery healthy or on a maintainer, no other diagnostic tool attached, a person present, adapter removed afterwards (an adapter left plugged in may draw battery power; the amount for this adapter is unmeasured).
- Written stop criteria. Unplug the adapter and stop on: a new warning light or unexpected vehicle behavior, adapter heat, low battery voltage, any response outside the allowlist's expected shapes, repeated timeouts, or the owner saying stop.
- Operator authorization in the stage issue, from the vehicle owner.

## What would change this plan

A different adapter model, a host device without the needed connection mode, or a capture showing the car does not answer generic OBD-II on this adapter. Each returns the work to L0 with a new issue.
