# Offline diagnostic evidence

The portable `core.offline` API inspects manufacturer recordings without opening an adapter. It does not change the generic request allowlist or turn imported strings into executable requests. The identification experiment uses the same response framing and evidence conventions; partial route replies are owner-observed, while protocol family and physical module attribution remain unresolved.

## Small entry points

- `ManufacturerRecording.fromCapture(json, DiagnosticProtocol.UDS, RecordedFraming.CAN_11BIT_HEADERS)` imports the existing capture JSON shape as inert data. Select `UNKNOWN` when protocol is unknown. Use `.copy(expectedResponder = ..., vehicleAlias = ..., vehicleApplicability = ..., moduleIdentity = ...)` only with explicitly supplied metadata. A vehicle alias is a local pseudonym, never a VIN.
- `ManufacturerRecording.parse(json)` imports a wrapper with `script`, `protocol`, `framing` and optional identity fields. The script uses the existing capture schema, including byte-preserving chunk text and milliseconds relative to each request.
- `OfflineDiagnostics.analyze(recording).render()` explains framing, correlation and uncertainty.
- `ServiceEvidence.decode(report, definition)` interprets one field only under a complete explicit definition.
- `ServiceEvidence.compare(before, after, operationObservation).render()` keeps oil, intermediate and main service separate.
- `OfflineExample.render()` runs the complete constructed analysis and before/after example. Oil changes by one synthetic day, intermediate stays unchanged and main becomes unavailable. No operation or vehicle observation occurred.

From the repository root:

```sh
./gradlew -p core offlineDiagnostics -Pmode=plan
./gradlew -p core offlineDiagnostics -Pmode=demo
./gradlew -p core offlineDiagnostics -Pmode=analyze -Pinput=/absolute/path/recording.json
./gradlew -p core offlineDiagnostics -Pmode=capture -Pinput=/absolute/path/raw.json -Pprotocol=UNKNOWN -Pframing=CAN_11BIT_HEADERS
```

`analyze` accepts the wrapper; `capture` accepts existing capture JSON and requires explicit protocol/framing parameters. These commands only read local files and print minimized reports. `-Pmode=fixture` prints a synthetic wrapper JSON example; use Gradle `--quiet` if redirecting it into a local file. The CLI input limit is 2,000,000 bytes. It has no Bluetooth connection path. Definitions and optional dashboard observations use the small Kotlin API above; there is no generic definition editor or inferred Porsche catalog.

## What an import means

Requests, raw chunks, timing, responder identifiers and original metadata stay in the recording. Parsing is strict and bounded to 2,000,000 JSON characters, 256 requests and 4,096 chunks. Malformed response bytes stay available as evidence. Raw captures remain `RAW_CAPTURE`; importing them does not sanitize them or promote them to replay fixtures. Sanitized captures need an original SHA-256 and evidence-log reference, whose contents this tool cannot authenticate.

Framing defaults to unknown and is never inferred. Select: 11-bit CAN with headers, non-CAN with headers, or payload lines without headers. Existing ELM prompt assembly and multi-frame parsing are reused. Non-CAN checksum verification and unsupported transports remain outside the existing parser's capabilities. An ELM host transcript is not a complete CAN-bus capture: adapter-generated flow control and traffic hidden by filtering may be absent.

Protocol is never inferred. The modeled positive layouts are UDS `22` with one two-byte identifier and KWP `21`/`1A` with a one-byte identifier or `22` with one two-byte identifier. Shared response shapes do not prove which protocol a module implements. Other services remain unknown. Selected-protocol negatives validate echoed service, distinguish NRC `78` pending from terminal negatives and never imply success. Complete framing alone is not a valid field read.

Correlation uses the original request grouping, responder and protocol echo. It cannot distinguish a delayed reply to an earlier identical request without stronger acquisition evidence. Repeated matching requests, multiple positive terminals, wrong responders, malformed data, unresolved pending responses or partial prompt/framing prevent field decoding. Chunk time is not a request timestamp; request-to-request time cannot be reconstructed from the capture schema.

## Explicit fields and comparisons

A `ServiceFieldDefinition` must name its source, vehicle and module applicability, protocol/request/responder, category, meaning, unit, zero-based payload offset, width, byte order, signedness, multiplier, additive offset and all-ones rule. Width is 1–6 bytes, keeping raw integers exact before floating-point scaling. Offsets include the diagnostic response service and identifier. Explicit unavailable raw values are evaluated before signedness and scaling. Invalid or missing definitions return unknown.

Categories are oil, intermediate and main. Meanings remain separate: remaining, elapsed, configured interval, previous-service record and due-at. This tool does not infer one from another or calculate a countdown from plausible intervals. No Porsche service definitions ship. Definitions marked synthetic cannot decode real recordings. Other applicability is caller asserted, not independently verified by the tool.

Comparison requires matching nonblank vehicle pseudonyms/module identities, matching provenance, distinct recording IDs and identical field definitions. It does not normalize conflicting units or choose among duplicate observations. Manual dashboard observations retain source and observation time and are compared separately; numerical agreement does not establish a raw field's meaning. Changed counters are reported independently of the optional operation description. An acknowledgement alone cannot establish that an operation occurred or caused a change.

The PR63 counter experiments informed the separate categories, explicit all-ones policy and refusal to choose a meaning by plausible arithmetic. Their synthetic candidate evaluator remains separate test-only research; PR63 is merged, and its examples are not vehicle definitions.

## Privacy

Text reports omit raw request/reply payloads, user-provided identity strings, source text and operation descriptions. Full evidence objects still retain them locally. This is minimization, not a general VIN/serial detector: review all files under [CAPTURE_IMPORT.md](CAPTURE_IMPORT.md) before sharing, publishing or adding fixtures to Git. Imported data cannot be sent by these APIs. Normal replay validation still rejects manufacturer commands.
