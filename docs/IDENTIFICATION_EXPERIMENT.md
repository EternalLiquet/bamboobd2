# Cayenne identification experiment: two requests

2014 Cayenne S (958) · Live integration, October 31, 2026 · Software-tested; no vehicle run

**Supersedes the v2 rehearsal: B is now `714→77E`; endpoint A has no verified module attribution. The former route is withdrawn, with no fallback.**

## Scope and activation gate

Prepare a separate, user-triggered experimental mode that sends **one `22 F1 87` part-number read to each of two fixed endpoints**, with automatic flow control disabled and no manual flow control. Maximum: **two app-issued CAN data frames, no retries and no other ECU requests**. Capture a complete single-frame response or the first frame of a longer response; label the latter incomplete. Do not add these requests to ordinary polling or expose arbitrary address/payload entry.

Endpoint A (`729→793`) is a reported but unverified endpoint used for comparison; its module identity is unknown. Endpoint B (`714→77E`) is a VAG-analogy cluster hypothesis supported by an original Golf exchange, not a verified Cayenne route. Neither is a verified route to this car's cluster. The owner requested a Bluetooth-connected experiment. The Android screen offers an explicit owner-triggered live attempt after review of the fixed plan and parked-vehicle conditions. Imported REPLAY remains prohibited. The ordinary rehearsal entry point remains FAKE-only; a separate fixed-plan live entry point accepts LIVE transport. No vehicle run was performed during development.

## What the experiment can teach us

The immediate question is whether either fixed endpoint answers this standard-shaped identification read under the selected conditions. A coherent negative response, or a first frame showing the expected service and identifier, can be useful even without a complete part number. Silence remains inconclusive.

| Endpoint | Proposed TX → RX | Evidence and hypothesis |
| --- | --- | --- |
| A: comparison | `0x729 → 0x793` | PCM-Forge reports this pair using Mongoose J2534 and associates it with `7P5919204H`. Parts listings identify that number as a dashboard clock, contradicting the source's PCM label. No supporting raw identity exchange was found; neither module attribution nor Veepeak compatibility is established. |
| B: candidate | `0x714 → 0x77E` | An original 2016 Golf MK7/OBDeleven exchange maps logical module 17 to `714→77E`; PCM-Forge's separate source-code target table also labels this pair cluster. Golf MQB is not the 958 platform, and no independent 958 identity exchange validates the transfer. |

Source: [PCM-Forge research note](https://github.com/dspl1236/PCM-Forge/blob/main/research/SERVICE_RESET_RESEARCH.md), inspected content blob `989a3871a4df2307b492d9d29416f791499c4a2f`. These are research hypotheses, not OEM definitions. No other address is tried.

[LA Global Parts](https://www.laglobalparts.com/Catalog/Details/34574) and [Black Gold Premium Parts](https://www.blackgoldparts.com/products/2018-porsche-cayenne-s-e-hybrid-958-green-center-dash-analog-clock-7p5919204h) identify `7P5919204H` as a separate dashboard analog clock. This contradicts PCM-Forge's PCM label. The source's address-to-part association is itself unverified: no raw identification response supporting it was located. The parts evidence does not prove that `729→793` addresses the clock on this car.

The experiment retains A to test whether the reported endpoint yields a coherent response, not as a known-positive control or confirmed PCM/clock route. B remains low confidence for this Cayenne despite stronger address evidence on a different VAG platform. The former `717→781` arithmetic candidate is withdrawn: a logical module number cannot simply be added to `0x700`. B uses `714→77E`; A and the traffic limit remain unchanged. Software availability does not establish vehicle compatibility; the owner decides whether to start a parked-vehicle attempt.

The new route evidence is [MQB-sniffer's original work log](https://github.com/mrfixpl/MQB-sniffer/blob/main/README.md), inspected blob `bbc2672393eca05627824335e8da3e18ba2034e0`, and [PCM-Forge's target table](https://github.com/dspl1236/PCM-Forge/blob/main/tools/bench-dongle/pcm_slcan.py), inspected blob `d4f10c73b429a61fa340cea1735378043fed3ce5` (first 160 lines). The latter also assigns PCM to `773→7DD`, contradicting the project's earlier prose table; a source-code constant is not proof of a successful 958 read. No routines, sessions, security operations or scans from either project are adopted. The Golf exchange includes a session change; this proposal deliberately does not, so default-state rejection remains possible.

[An original 2018 Golf R report](https://github.com/commaai/openpilot/issues/1238#issuecomment-602155568) independently illustrates the `+0x6A` reply offset and a part-number read on another module. It supports neither logical-address arithmetic nor target-Cayenne applicability.

Proposed transport: **11-bit CAN, 500 kbit/s, normal ISO-TP addressing**. This is also an assumption. No TP2.0 negotiation, extended addressing or alternative bitrate is attempted. A successful A response would support this limited transport path to A, not prove the route to B.

## Exact requests and reception

1. Send application payload `22 F1 87` to A once.
2. After A's transaction ends cleanly or yields ordinary no-data, wait at least one second, configure B, and send the same payload once. An abort condition prevents step 2.

For the proposed transport, each request's raw eight-byte CAN data field is:

`03 22 F1 87 00 00 00 00`

The leading byte is the single-frame payload length; the last four bytes are proposed padding. [udsoncan's identifier definitions](https://udsoncan.readthedocs.io/en/latest/_modules/udsoncan/common/dids.html) name `F187` as the manufacturer spare-part number. That establishes a standard UDS meaning, not support or encoding on this Porsche.

Keep raw response bytes. A complete positive reply must start `62 F1 87`. Remaining bytes are opaque; a printable-text preview is provisional. A matching envelope alone does not establish the diagnostic family or identify the responder as the cluster.

For a valid ISO-TP first frame, record the announced length and available payload prefix, then label it **incomplete—no flow control sent**. Never reconstruct missing bytes or report the prefix as a complete identity. No consecutive-frame collection is requested, and neither software nor adapter may intentionally send flow control in this stage.

## Isolated adapter setup

Obtain exclusive adapter ownership and stop all polling, discovery and reconnect jobs. After Bluetooth connects, drain optional startup text for at most two seconds. A standalone terminal `>` or 250 ms without incoming bytes after complete lines permits the existing first command, `ATPC`. A quiet connection is not proof of an idle adapter; `ATPC` is the explicit handshake and must return `OK` and a prompt within two seconds before any further setup. Partial lines, malformed input, disconnect or continuously arriving data stop the run. No blank carriage return, recovery command or extra vehicle request is added. Startup bytes remain in the private report. This replaces v3's incompatible requirement for an unsolicited prompt; the protocol plan is now v4. The two-second startup bound counts toward the 60-second protocol deadline.

Local setup, in order:

`ATPC`, `ATD`, `ATM0`, `ATI`, `ATE0`, `ATL0`, `ATS1`, `ATH1`, `ATD0`, `ATR1`, `ATAT0`, `ATST7D`, `ATTP6`, `ATCAF0`, `ATCFC0`, `ATCEA`, `ATAL`

Then set A's header/filter with `ATSH729`, `ATCRA793`, and issue `ATBI`. Before switching to B, require a settled prompt, issue `ATPC`, reapply `ATCAF0`, `ATCFC0`, `ATCEA`, `ATAL`, then `ATSH714`, `ATCRA77E`, and `ATBI`.

Send each setup command once, requiring its complete response and prompt within two seconds and the overall deadline. `ATI` returns an identification string; configuration commands require `OK`. The parser must tolerate echo and either line-ending style until normalization completes. An unrecognized command or error aborts; there is no fallback sequence.

`ATPC` closes protocol activity. **`ATD` restores configurable defaults, not necessarily pristine factory settings**; prior programmable parameters can affect behavior. `ATM0` precedes protocol activation to avoid remembering a successful protocol. No programmable parameters are written. `ATD0` requests raw lines without a separate DLC digit. Receive filtering limits host-visible replies, not bus activity.

`ATST7D` proposes a nominal **500 ms receive-gap timeout** at the documented base multiplier. Inherited configuration and firmware behavior may alter its actual timing; host deadlines remain authoritative. `ATBI` avoids a generic initialization exchange but also bypasses protocol validation, adding uncertainty if the transport assumption is wrong. No automatic protocol search is permitted.

The [Elm datasheet](https://www.elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf) documents these adapter controls. Veepeak's [FAQ20, page 3](https://veepeak.com/cdn/shop/files/OBDCheck_BLE_PLUS_FAQs_Troubleshooting_guide_V3.pdf?v=1027675084096395144) describes normal BLE as supporting v1.4 AT commands. Recognition of a command is not wire-level validation of this particular adapter.

## Limits and stopping behavior

- **Two app-issued CAN data frames maximum:** the two requests above. Zero retries, manual flow-control frames, keepalives or other ECU requests. Automatic flow control remains disabled throughout.
- Count a request against the budget before starting its transport write. A partial or uncertain write is never retried. Only one request may be outstanding.
- Maximum **60 seconds** for the whole run, including setup. Maximum **two seconds** for a request to produce its first response or terminal prompt. An earlier prompt ends reception.
- Response pending may extend an active receive window to **five seconds from the original request**, never restart the deadline. If the prompt returns, record pending/incomplete and end that route. Do not assume idle CAN streaming or send a monitor command.
- After a first frame, send nothing further. Wait only for the terminal prompt within the existing deadline. A first frame is a useful partial result, not a reason to extend the run.
- Maximum **64 KiB** transcript and **256 received adapter lines**. Any excess, malformed framing, mismatched service/identifier, unexpected responder, unsolicited consecutive frame, CAN/buffer error, lost prompt or disconnect aborts the entire run.
- Cancellation, backgrounding or leaving the experiment screen prevents any further writes and closes the connection without reconnecting. Already-submitted bytes cannot be recalled; disconnecting cannot guarantee immediate cessation of adapter activity or a responder's timeout.
- Software limits do not guarantee the number of physical CAN transmissions: controller retries, acknowledgements and faulty firmware are outside the app's direct control.

| Outcome | Meaning | Next action |
| --- | --- | --- |
| Complete single-frame `62 F1 87 …` | Positive response to the proposed identifier; data encoding and module identity remain provisional | Preserve bytes; proceed only to B if this was A and no abort condition occurred. |
| Valid first frame with expected positive prefix | Responder offers a longer reply; announced length and prefix are evidence | Record incomplete, send no flow control; proceed to B only after A's prompt and settling delay. |
| Coherent service-not-supported or request-out-of-range reply | A responder rejects this read under these conditions | End the route; A may still be followed by B. |
| Pending, followed by prompt or deadline | Final result was not observed | End the route without retry; A may still be followed by B only if its prompt is settled. |
| Security, conditions or session denial | A prerequisite may be unmet | Abort the whole run; no unlock or session change. |
| Ordinary no-data with a settled prompt | Route, timing, transport or support may be wrong | End route without retry; A may still be followed by B. |
| New warning, display disturbance, abnormal sound, unstable power or owner presses Stop | Unexpected behavior or owner cancellation | Stop immediately; no cleanup requests or automatic restart. |

Unclassified negative responses stop the entire run. A timeout without a terminal prompt also aborts the whole run; it is not safe to queue B into an unsettled adapter transaction.

## Review before a vehicle run

The owner reviews the two hypotheses, exact requests, implementation and potential side effects before separately approving a run. Any run must be parked securely, in Park with parking brake applied, ignition on and engine off, with stable power and an attentive owner. No other diagnostic tool may be connected concurrently.

Identification reads can wake modules and load the bus. Wrong addressing, protocol assumptions, adapter behavior or ECU defects could cause communication faults, warnings, temporary display disturbance or battery drain. No persistent ECU change is intended, but a read is not a guarantee of no side effects. Do not clear any resulting codes or send a session-change request as part of this test.

Keep the transcript private. Record the adapter identification string, settings, actual request/response lines, monotonic timestamps and stop reason. Do not request VIN/serial fields or log Bluetooth hardware addresses; review unexpected data for identifiers before sharing. Identifying a module would still not supply maintenance-counter definitions.

## Focused implementation checks

Use offline transcripts to verify only the behavior needed here:

- Exact two-request emission and setup ordering, with no flow control, retry, fallback or other ECU request.
- Split-line/prompt handling, complete single frames and incomplete first frames.
- Malformed or mismatched replies stopping further traffic.
- Cancellation and deadlines, including partial-write uncertainty.
- Absence of polling, background and reconnect traffic during and after the experiment.

Keep the fixed endpoints, request bytes and caps outside the ordinary polling allowlist. No arbitrary hex entry or address/DID enumeration. End by disconnecting; the next ordinary session must initialize its own settings.

**Deferred:** manual flow control and additional software/hardware identifiers may be considered in a separate proposal after this first result. They are not runnable options in this stage.

## This build

Open **Identification experiment**, review the two fixed routes and risks, select the already-paired scanner, acknowledge the parked/engine-off conditions, then explicitly choose **Connect and run two reads**. There is no automatic start, reconnect or retry. Bluetooth connect is limited to ten seconds; the entire connect-and-protocol attempt is also limited to 60 seconds. The core refuses imported recordings as live command plans.

The app reserves adapter ownership across connect, preflight, setup and reads. Competing bench, ordinary live and recording starts are blocked. Stop, Back, leaving the screen or backgrounding cancels the attempt and closes the socket; submitted bytes cannot be recalled. Completed and partial results retain raw preflight bytes, attempted writes, received chunks, monotonic timing, provenance and the stop reason. Reports are stored privately; sharing is an explicit owner action and requires checking unexpected contents for private identifiers.

Because this experiment changes adapter settings without cleanup writes, the app retains a reuse restriction across restart after an attempted command. A completed or cancelled attempt with no attempted writes releases the restriction after closing the socket; process death or uncertain writes retain it. Unplug and reinsert the adapter before acknowledging its power cycle in the experiment screen and starting an ordinary session. Closing Bluetooth alone does not clear custom headers, filters or formatting. This explicit owner acknowledgment is not an electrical measurement. Ordinary scans retain their existing allowlist and initialization.

The optional rehearsal and JVM demo remain hardware-free:

```sh
./gradlew -p core offlineDiagnostics -Pmode=plan
./gradlew -p core offlineDiagnostics -Pmode=demo
```

There is no generic `0100` baseline, software-version request, automatic flow control or cleanup write in the experiment. Neither endpoint nor the adapter's handling of this sequence has been validated on the car. A failed startup or ATPC handshake does not show that either vehicle endpoint is absent.
