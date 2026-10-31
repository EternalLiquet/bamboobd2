# Fixed Cayenne F187 identification experiment

2014 Cayenne S (958), October 31, 2026. Plan `two-endpoint-f187-manual-flow-control-v5`.

## Scope and evidence

This owner-triggered experiment sends one `22 F1 87` read on each of two fixed routes. Owner-operated v4 evidence established matching positive first frames with announced length 14 on both routes. It did not establish a complete part number, module identity, cluster attribution, maintenance values or reset support. Raw captures remain private and are not included in this repository.

| Route | Request → reply | Attribution |
| --- | --- | --- |
| A | `729 → 793` | Module identity unknown. The originally cited `7P5919204H` part is an analog clock; this does not establish the address-to-module association. |
| B | `714 → 77E` | Cluster hypothesis from a Golf/VAG analogy; not verified as a Cayenne cluster. |

Historical route source: [PCM-Forge research note](https://github.com/dspl1236/PCM-Forge/blob/main/research/SERVICE_RESET_RESEARCH.md), inspected content blob `989a3871a4df2307b492d9d29416f791499c4a2f`. An original 2016 Golf MK7/OBDeleven exchange maps logical module 17 to `714→77E`; Golf MQB is not the 958 platform. The former `717→781` arithmetic candidate is withdrawn, with no fallback. Neither shared prefixes nor matching complete part numbers alone would prove that the replies came from the same physical module.

The v5 continuation adds **one conditional manual flow-control frame per route** to collect the remainder of the observed 14-byte response. Automatic flow control remains off. This changes the earlier no-flow-control experiment; the Android review and plan identifier explicitly distinguish it. No VIN, additional DID, scan, session/security request, reset, coding, retry or cleanup write is included. Ordinary polling and RequestPolicy are unchanged; arbitrary requests and imported REPLAY are not executable.

## Exact commands

Assumptions: normal ISO-TP, 11-bit CAN, 500kbit/s. Existing startup fix retained: drain optional startup bytes for at most 2s. A standalone prompt or 250ms quiet period after complete lines permits the first `ATPC`. Partial/malformed startup data or disconnect stops without writes. ATPC must return OK and a terminal prompt before further setup.

Shared setup, in order (each command terminated with carriage return):

```text
ATPC
ATD
ATM0
ATI
ATE0
ATL0
ATS1
ATH1
ATD0
ATR1
ATAT0
ATST7D
ATTP6
ATCAF0
ATCFC0
ATCEA
ATAL
```

A: `ATSH729`, `ATCRA793`, `ATBI`, then raw `0322F18700000000`.

After A's final terminal prompt, wait at least 1s. B: `ATPC`, `ATCAF0`, `ATCFC0`, `ATCEA`, `ATAL`, `ATSH714`, `ATCRA77E`, `ATBI`, then the same raw request.

Every setup command requires one OK reply plus prompt within 2s, except ATI requires an identification string. Echo and CR/LF differences are tolerated until normalization. Rejected configuration stops the whole run; no fallback. ATD restores configurable adapter defaults and ATBI bypasses protocol validation. Their acceptance does not prove on-wire behavior.

### Conditional continuation on either route

Only after the expected responder returns an eight-byte CAN first frame whose announced payload length is exactly 14 and whose initial payload is `62 F1 87`, retain its six payload bytes and await the terminal adapter prompt. Only then send:

```text
30020A0000000000
```

Keep the same TX header and RX filter. This is ISO-TP Continue To Send, block size 2, minimum separation 10ms, padded to 8 bytes. It is a transport-control frame, not an ECU setting change. One FC maximum per route, no repeats. A single-frame positive or negative reply does not trigger FC.

Collect CF21 (seven payload bytes), then CF22 (one final byte, excluding padding). Both must have the expected responder. Reject unsolicited, repeated, reordered, truncated or excess frames. A terminal prompt before all 14 payload bytes is an incomplete transfer and stops the whole run. Require the final prompt even after complete data. Do not manufacture missing bytes. Printable identity bytes are shown as reported part-number text; all identity bytes also have a hex representation. Reported identity does not verify module attribution.

**Timing limitation:** the observed adapter took approximately half a second to produce a prompt after the first frame. The ECU's flow-control deadline is unknown. Waiting for the prompt may miss that deadline; sending serial bytes before the prompt could interrupt the adapter. A timeout means this manual timing/adapter approach remains unresolved, not that the route or part number is absent. There is no automatic-FC fallback.

Protocol basis: [ELM327DSJ](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf), CAF0/CFC0 and command/prompt behavior, and [Linux ISO-TP documentation](https://docs.kernel.org/networking/iso15765-2.html), FC blocksize/STmin semantics. Unlike ELM automatic FC, manual FC permits validating the first frame and bounding host sends before continuation. An adapter's advertised firmware string is not a verified feature guarantee.

## Bounds and outcomes

- At most four attempted host CAN sends: two F187 requests and zero to two conditional FC frames. No retry or keepalive. This is a host-send bound, not a guarantee about physical controller retransmissions.
- Each exchange has a 2s deadline; a response-pending NRC78 permits up to 5s from the original read, never restarting that deadline. FC collection has its own 2s maximum. Protocol and app attempt each bounded to 60s; Bluetooth connect bounded to 10s.
- Maximum 64KiB transcript, 256 received lines and 14-byte reassembly. Unknown/malformed service, DID, responder or denial stops the run.
- Coherent NRC11/31, ordinary no-data, or pending ending at a prompt may permit the second route. A first frame only permits FC after its prompt. A failed/incomplete FC transfer does not permit the second route.
- Stop/back/background close the socket, cancel the run and never reconnect or send cleanup. Adapter ownership excludes bench, normal live reads and recording throughout the attempt.

## Android operation and records

Open Identification experiment, select the already-paired scanner, review the exact test, and explicitly approve the two reads plus conditional flow-control frames. Use a parked vehicle, parking brake, ignition on/engine off, stable power and no competing diagnostic app. Stop for warnings, unusual sounds or display disturbance. Development and hosted checks never operate the vehicle.

Reports use RAW_CAPTURE provenance and contain app/plan versions, attempted-write timestamps and received chunks. The request timestamps plus per-step chunk offsets allow measuring first-frame-to-flow-control delay. Reports persist privately, export only after preview/identifier review, and are never automatically uploaded. Bluetooth addresses are not stored. Synthetic rehearsal remains labeled SYNTHETIC and uses constructed identities.

After any attempted command, a persisted reuse restriction requires unplugging/reinserting the adapter and acknowledgment before another scan. Closing Bluetooth does not restore headers or filters. A zero-write attempt releases the restriction after socket closure; process death and uncertain writes retain it. An old restriction survives APK updates until acknowledgment.

## Verification and remaining gap

Tests cover the fixed request sequence, single frames, pending/negative responses, strict fourteen-byte reassembly, malformed/extra CF rejection, no FC without a valid first frame and prompt, cancellation/timeout without retries, exclusive Android ownership, private report handling and zero-write reuse. Fake-socket Android tests exercise both complete routes with exactly four host CAN sends. Core/Android checks and independent review establish software behavior only. The manual-FC continuation is not yet vehicle-validated; maintenance definitions and service reset remain out of scope.
