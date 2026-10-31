# Bounded module-identification experiment

This is a proposed test for owner review, implemented as a Kotlin/JVM runner with synthetic transport only. **The runner refuses a LIVE transport before any write. There is no Android binding in this slice.** Printing this plan or running the demo sends nothing to an adapter. Owner approval of the exact test, a reviewed live integration and the [live-test gate](LIVE_TEST_GATE.md) are still required before enabling a vehicle run.

The question is small: does the reported `729`/`793` route return a correlated identity response under the explicitly selected UDS hypothesis? This is **not a verified cluster route, service-counter reader or resetter**. No service-field definition is supplied.

## Why this route and these requests

[PCM-Forge's public research note](https://github.com/dspl1236/PCM-Forge/blob/main/research/SERVICE_RESET_RESEARCH.md) reports a Cayenne 958 exchange using TX `0x729`, RX `0x793`, with part `7P5919204H`, through Mongoose Pro J2534. The note calls that module the PCM and explicitly questions whether it is the instrument cluster. This is one author's observation, not a raw exchange independently validated here. The inspected content-blob SHA is `989a3871a4df2307b492d9d29416f791499c4a2f` (a blob, not a commit). No broad address-offset rule is adopted. No code, firmware-derived dataset or reset procedure is copied from that project.

The [udsoncan standard DID constants](https://udsoncan.readthedocs.io/en/latest/_modules/udsoncan/common/dids.html) identify `F187` as manufacturer spare-part number and `F189` as manufacturer ECU software version. They do not establish that this Porsche module supports either field. [py-uds's ReadDataByIdentifier description](https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html#readdatabyidentifier-0x22) supplies the selected UDS `22`/`62` request/response and echoed-DID rule. A response consistent with that rule does not, by itself, distinguish UDS from a similar KWP response or identify the responding module's role.

The physical hypothesis is normal-addressed ISO-TP over 11-bit, 500-kbit/s CAN. The preliminary generic read tests that transport setting; it does not prove the manufacturer route or session. We make no diagnostic-session change. Default-session accessibility, gateway routing and Veepeak optional-command behavior remain unknown. No response means this attempt was inconclusive, not that the cluster is absent.

## Exact proposed host sequence

The notation `\r` below means one ASCII carriage return, byte `0D`; it is not a literal backslash and letter. Each line is a separate write, followed by a bounded wait for its prompt. There are no bare carriage returns, retries, automatic searches, fallback addresses or user-editable commands. Stop at the first failed condition; do not continue down the table.

| Order | Exact host bytes as ASCII | Condition / purpose |
| --- | --- | --- |
| 1 | `ATD\r` | Require `OK`; start from adapter defaults. |
| 2 | `ATE0\r` | Require `OK`; disable echo. |
| 3 | `ATL0\r` | Require `OK`; disable line feeds. |
| 4 | `ATH1\r` | Require `OK`; retain responder/framing information. |
| 5 | `ATI\r` | Record firmware's self-description; this is not authenticated identity. |
| 6 | `ATRV\r` | Require an adapter-reported 12.0–15.0 V; provisional conservative bound, not a battery-health guarantee. |
| 7 | `ATM0\r` | Require `OK`; disable protocol memorization. |
| 8 | `ATTP6\r` | Require `OK`; select only the provisional CAN setting. |
| 9 | `ATCAF1\r` | Require `OK`; adapter supplies request PCI. |
| 10 | `ATAT0\r` | Require `OK`; disable adaptive timeout adjustment. |
| 11 | `ATSTFF\r` | Require `OK`; select the fixed adapter timeout. |
| 12 | `0100\r` | One generic supported-PID baseline; require a complete valid reply. |
| 13 | `ATDPN\r` | Require `6`; a reported setting alone is not connectivity proof. |
| 14 | `ATSH729\r` | Require `OK`; select the single hypothetical physical target. |
| 15 | `ATCRA793\r` | Require `OK`; restrict received responses. |
| 16 | `ATFCSH729\r` | Require `OK`; explicit flow-control destination. |
| 17 | `ATFCSD300000\r` | Require `OK`; fixed flow-control payload. |
| 18 | `ATFCSM1\r` | Require `OK`; use the configured flow-control values. |
| 19 | `ATCFC1\r` | Require `OK`; enable adapter flow control. |
| 20 | `22F187\r` | Require one complete nonempty `62 F1 87 ...` response from `793`. |
| 21 | `22F189\r` | Only after step 20 succeeds; require `62 F1 89 ...` from `793`. |
| 22 | `ATD\r` | Require `OK`; restore adapter defaults before releasing it. |

The [genuine ELM327DSL datasheet](https://www.elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf), printed pp17, 19, 27, 29, 46, 55 and 62–65, documents these adapter mechanisms. `TP6` with memory off avoids saving a protocol; `SP6` would save it. Headers on retain PCI bytes with automatic formatting. The custom flow-control header avoids assuming a standard response-minus-eight address. `ATD` restores custom headers, filters, masks and timers. None of this proves that an adapter advertising an ELM-compatible version implements the commands correctly. Unsupported commands terminate the attempt. `ATBI` is deliberately excluded: its documented initialization bypass would weaken the transport check.

### Host commands versus CAN traffic

The two manufacturer requests contain application payloads `22 F1 87` and `22 F1 89`. Under the selected formatting hypothesis, their CAN data would be `03 22 F1 87 pp pp pp pp` and `03 22 F1 89 pp pp pp pp` on ID `729`, where `03` is the single-frame payload length and `pp` denotes adapter-supplied padding. Padding bytes are not measured or guaranteed by this host transcript. The generic baseline similarly has application payload `01 00`, with PCI length `02` and adapter padding at the default functional address.

`ATFCSD300000` configures implicit flow-control bytes `30 00 00` on ID `729` when the adapter receives an applicable first frame. These transport acknowledgements are additional vehicle traffic, even though the application sends only two manufacturer reads. They are not service-reset acknowledgements. The experiment records all host writes, incoming host chunks, order and available timestamps. It records the configured automatic traffic separately; **it is not a complete CAN-bus capture** and cannot prove actual padding or flow-control transmission.

## Bounds, cancellation and cleanup

Provisional software bounds are 2 seconds per adapter step, 3 seconds per vehicle exchange including pending responses, 45 seconds for the whole attempt, and 8 KiB of incoming host bytes per exchange. An oversized chunk is not retained; the stop reason explicitly records that loss. These are deliberately finite implementation choices, not measured Porsche timing requirements. The adapter timeout is separate from the application's deadline. A pending response never restarts the application deadline or triggers retransmission.

Abort on negative reply, wrong responder/echo, malformed or incomplete framing, timeout, no data, unexpected adapter text, buffer limit, disconnection or cancellation. Preserve partial data and the stop reason. A future operator must also stop for unexpected vehicle behavior, warnings, unstable power or adapter heat, as required by the live-test gate. No state-changing prerequisite is attempted to get past a refusal.

Adapter routing can survive Bluetooth disconnection. On a normal completion, checked `ATD` cleanup is required before reuse. On failure or cancellation, further diagnostic writes stop; if default restoration cannot be confirmed, mark cleanup unconfirmed and require an adapter power cycle before any normal scan. Closing the socket alone does not restore settings. Cancellation cannot retract already transmitted frames or guarantee instantaneous cessation of adapter-generated flow control. A future Android integration must enforce exclusive connection ownership and the reuse restriction; this offline-only runner does not claim that integration exists.

Raw data stays private and outside Git. A future export needs explicit review/redaction; avoiding VIN and serial DIDs does not guarantee that an unexpected module reply contains no private data. Do not send automatic telemetry or publish captures.

## Run without hardware

From the repository root:

```bash
./gradlew -p core offlineDiagnostics -Pmode=plan
./gradlew -p core offlineDiagnostics -Pmode=demo
```

`plan` prints the proposed sequence. `demo` exercises constructed exchanges through a fake transport. Every demo identity and value is synthetic. Neither mode demonstrates Veepeak compatibility, Porsche support, the cluster's identity or a real service reset.

## Evidence needed after an approved future attempt

Review whether the declared framing and responder correlation held, which setup commands were accepted, and whether identity replies were complete. Keep negative, pending and partial results. Treat a returned part/software string as an observation requiring independent module attribution. Do not promote the address pair to a cluster route solely because bytes arrived.

Oil, intermediate and main service still need separate applicable definitions: read identifiers, response layout, units, scaling, signedness, unavailable values and the distinction between remaining, elapsed, configured intervals and prior-service records. This experiment requests none of those counters. A protocol acknowledgement is never evidence that a reminder was reset, and this test performs no reset.
