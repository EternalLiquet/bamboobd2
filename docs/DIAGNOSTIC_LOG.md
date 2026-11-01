# Diagnostic log export

The app keeps a local log of every Bluetooth connection so a failed car test can be diagnosed from a file instead of screenshots. Nothing is uploaded. The owner chooses **Export diagnostic log** and then shares the file through Android's share sheet or saves it.

Only host software checks with constructed sockets have exercised this. No phone or car has run it yet.

## What is recorded

One session per Bluetooth connection: the normal vehicle connection (adapter setup, automatic identification and every scan or recording on it), the adapter bench check, and the Cayenne identification experiment. Each session is a JSON-lines file under the app's private `files/diagnostics/`.

Every event has a sequence number, wall-clock time (`atUtc`), milliseconds since the session started (`tMs`, monotonic) and a category:

| Category | Meaning | Kinds |
| --- | --- | --- |
| `traffic` | Received data and host I/O boundaries | `write_started`, `write_completed`, `exchange`, `write_failed`, `stream_end` |
| `app` | What the app did | `session_start` (with app version, version code, Android version, purpose, adapter name), `connect_start`, `connected`, `connect_failed`, `write_attempt`, `operation_start`/`operation_end` (identification, scan, recording, bench, experiment), `connection_closed`, `truncated`, `session_end` |
| `interpretation` | The app's own explanation or decoded outcome, which can be wrong | `note` (reader notes such as `codes_not_read`, `retry`, `late_reply_discarded`; identification summary) |

`write_attempt` means the caller requested a write. `write_started` is recorded immediately before the socket's blocking write on the I/O thread; `write_completed` means write and flush returned. These events share an `exchangeId`. None proves ECU acknowledgement, and a missing completion leaves delivery unknown. Cancellation before I/O has an attempt without a started write.

An `exchange` contains one started request and the input received until the adapter's `>` prompt, the next write, or the end. `reply` preserves received delimiters (CR shown as `\r`) except marked redaction or withholding. `prompt: false` with `endedBy: "next_write"` means no prompt arrived before the app moved on. `chunks` lists each received chunk's arrival time and size; `endedTMs` is session-relative and `durationMs` is exchange-relative. Bytes with no request outstanding have `request: null`. Ambiguous incomplete payloads are withheld, while safe adapter status and short negative-response prefixes remain useful for diagnosis.

## Effect on the connection

Logging adds no request. Transport callbacks take timestamps and admit bounded work without waiting for disk; one background thread redacts and writes it. This has small CPU and queue overhead, not a zero-cost guarantee. Export takes its complete snapshot on the same worker so rotation cannot remove a file mid-export. Export and explicit flush wait off the transport path.

## Storage bounds

- At most 8 sessions; starting a new one deletes the oldest and permanently retires its live log handle, so late callbacks cannot recreate it.
- Each session stops storing traffic after about 240 kB and other events after about 256 kB, with a small reserve for loss and end records. An atomic sidecar checkpoint shares the traffic budget; temporary replacement can briefly use another checkpoint's space.
- Pending ordinary work is limited to 256 events and 64,000 bytes/characters before receive buffers are copied. Each exchange is limited to 64,000 characters and 512 receive chunks. Overload seals further traffic admission and persists `truncated` promptly, even before disconnect. Session-end work has reserved admission. Data after the first loss must not be read as a complete trace.
- Sessions survive disconnects, failed operations and app restarts. A session the app never closed is exported with `ended: false`. While a request is pending, an atomically replaced, redacted checkpoint preserves the request, safe partial data and timing. A crash can still lose work not yet persisted; this is not synchronous journaling.
- If no completed exchange was stored, export exposes the checkpoint separately as `incompleteExchange`, with `recovery: "completion_not_recorded"`. It is not a new ordered event or proof that the request is still running. Completed exchange IDs suppress stale duplicate checkpoints.

## Export

The export is one JSON document: `format`, `formatVersion: 2`, `exportedAtUtc`, `app` (version name and code, Android version), `privacy`, a `legend`, `truncated`, and `sessions` oldest first, each with `id`, `ended`, `truncated`, its ordered `events`, and any `incompleteExchange` checkpoint.

The button is on the home screen under the vehicle card. It also appears on a live scan that didn't complete, a recording stopped by anything other than the user, and an experiment attempt that didn't finish. If no log exists the dialog says so. If any session was cut short, or didn't close normally, the dialog says that too. **Share…** opens the share sheet with the file (read-only `content://` link through a `FileProvider` limited to the export folder). **Save to file…** opens the system file picker.

## Privacy

Redacted before writing to disk, and again on export:

- **VINs.** Mode 09 identifier data is replaced by `[REDACTED:VIN data, N bytes]`, keeping the responder ID and short negative replies. CR, LF and attached prompts are handled, along with encoded traffic embedded in errors. VIN first frames, decoded VINs and ambiguous ASCII continuation fragments are hidden under other requests too. Plain-text VIN-shaped strings become `[REDACTED:VIN]`. Checkpoints withhold unclassified partial payloads so a later chunk cannot reveal that an earlier persisted prefix was private.
- **Bluetooth addresses** become `[REDACTED:bluetooth-address]` anywhere they appear, including platform error text.

Kept for debugging: requests, responder IDs, protocol replies, timing, errors, adapter name, app version. **The export is not anonymous.** The adapter name, timestamps and module replies (for example part numbers) remain. Review it before sharing it publicly.

A validated full F187 first frame permits its immediately following flow-control exchange's part-number continuation to remain visible. This redaction context belongs to one log session and does not change request policy, flow control or the deferred experiment timing behavior.

## Code

- `core/.../diagnostics/TrafficSink.kt`: the interface the transport calls.
- `core/.../diagnostics/DiagnosticRedactor.kt`: VIN and address redaction.
- `core/.../storage/DiagnosticLog.kt`: sessions, storage bounds, rotation and export.
- `app/.../bluetooth/BluetoothClassic.kt`: reports writes, reads, write failures, stream end and local close.
- `app/.../connection/VehicleConnection.kt`, `AppViewModel.kt`, `BluetoothBench`: session and operation boundaries.
- `app/.../ui/DiagnosticExport.kt`: the export button and dialog.

Tests: `DiagnosticLogTest` (core: ordering, partial and error replies, persistence, rotation, truncation, redaction) and `DiagnosticExportFlowTest` (app: one connection across identification and a failed scan, restart, share and save intents, empty log, narrow-screen card layout).
