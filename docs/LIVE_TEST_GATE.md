# Live-test gate

Nothing below is authorized, including powering or connecting any adapter. This lists the evidence and permissions a later adapter or parked-car test needs. Each stage needs its own issue, a reviewed plan, and the owner's written **go** in that issue naming the stage. Approval of one stage does not carry to the next. See also [safety boundary](SAFETY_AND_EVIDENCE.md).

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

Current status (2026-10-28): phone (Android 17, API 37), adapter and operator are recorded, the adapter label is still to be recorded, and **the bench supply is not confirmed, so L1 is blocked**. An owner-run adapter-only check in the parked car (outside this gate) is recorded in the readiness note, not as an L1 result. See the [hardware readiness note](evidence/2026-10-28-hardware-readiness.md). The offline bench path for L1 is described in [BLUETOOTH_TRANSPORT.md](BLUETOOTH_TRANSPORT.md).

These are documentation items. None requires powering or connecting the adapter.

- **Adapter:** exact model name and any printed version from the label, with a photo; the vendor documentation for that model, with link and retrieval date ([current evidence note](evidence/2026-10-13-veepeak-bluetooth-mode.md)); any already-existing observations (for example from earlier use with another app), recorded as-is. No new test is run to produce them.
- **Host device (Android phone first):** make, model, Android version and API level, read from the phone's settings. It must meet the provisional baseline (`minSdk` 26); if it does not, use another phone rather than lowering the baseline. Expected prompts (to be confirmed against [Android docs](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), checked 2026-10-13): connecting to an already-bonded device needs `BLUETOOTH_CONNECT` (Android 12+) or legacy `BLUETOOTH` (11 and lower); any discovery or scan, Classic or BLE, needs `BLUETOOTH_SCAN` on 12+, and legacy `BLUETOOTH_ADMIN` plus location on 11 and lower. Pairing may be done in system settings, avoiding discovery in the app.
- **Software:** exact commit, allowlist hash, checks passing on that commit (G1-G3, plus the G4 review checklist), independent diff review, and the transport registry (G2, from slice 2) in place with the new transport reviewed and pinned.
- **Data handling:** raw capture stored outside Git with SHA-256, acquisition context and loss recorded in an [evidence log](templates/evidence-log.md); VIN never requested; sanitized fixture reviewed before it enters the repo.

## Collected during the authorized L1 test (not prerequisites)

- Whether the unit pairs in Android system Bluetooth settings (Classic), appears in an app's BLE scan, or both, and which mode works reliably.
- Responses to allowlisted adapter-local identification commands such as `ATI`.
- Observed chunk sizes, timing and adapter status strings.

Record these in the evidence note. They are measured during L1, never assumed beforehand.

## Additional evidence before L2 and L3

- L1 results recorded as above.
- Unknown until observed: which protocol the adapter reports for the car, whether the car answers generic OBD-II on it, how many ECUs answer, and what any gateway does. Do not assume.
- **Adapter-originated traffic is vehicle traffic.** Protocol auto-search (`ATSP0`) and any adapter keep-alive or wake-up messages are not requests from our code. Whether this adapter sends any, and on which protocol, is unmeasured; record what is observed at L1/L2 and decide before L3.
- Vehicle set-up: stationary, parking brake on, battery healthy or on a maintainer, no other diagnostic tool attached, a person present, adapter removed afterwards (an adapter left plugged in may draw battery power; the amount for this adapter is unmeasured).
- Written stop criteria. Unplug the adapter and stop on: a new warning light or unexpected vehicle behavior, adapter heat, low battery voltage, any response outside the allowlist's expected shapes, repeated timeouts, or the owner saying stop.
- Operator authorization in the stage issue, from the vehicle owner.

## What would change this plan

A different adapter model, a host device without the needed connection mode, or a capture showing the car does not answer generic OBD-II on this adapter. Each returns the work to L0 with a new issue.
