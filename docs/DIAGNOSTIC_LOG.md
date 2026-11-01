# Diagnostic log export

The app keeps a local log of every Bluetooth connection so a failed car test can be diagnosed from a file instead of screenshots. Nothing is uploaded. The owner chooses **Export diagnostic log** and then shares the file through Android's share sheet or saves it.

Only host software checks with constructed sockets have exercised this. No phone or car has run it yet.

## What is recorded

One session per Bluetooth connection: the normal vehicle connection (adapter setup, automatic identification and every scan or recording on it), the adapter bench check, and the Cayenne identification experiment. Each session is a JSON-lines file under the app's private `files/diagnostics/`.

Every event has a sequence number, wall-clock time (`atUtc`), milliseconds since the session started (`tMs`, monotonic) and a category:

| Category | Meaning | Kinds |
| --- | --- | --- |
| `traffic` | What the transport actually wrote and received | `exchange`, `write_failed`, `stream_end` |
| `app` | What the app did | `session_start` (with app version, version code, Android version, purpose, adapter name), `connect_start`, `connected`, `connect_failed`, `operation_start`/`operation_end` (identification, scan, recording, bench, experiment), `connection_closed`, `truncated`, `session_end` |
| `interpretation` | The app's own explanation or decoded outcome, which can be wrong | `note` (reader notes such as `codes_not_read`, `retry`, `late_reply_discarded`; identification summary) |

An `exchange` is one request and everything received until the adapter's `>` prompt, the next write, or the end. `reply` is the exact received text (CR shown as `\r`), so partial replies and adapter text such as `NO DATA`, `STOPPED` or `BUS INIT: ...ERROR` stay as sent. `prompt: false` with `endedBy: "next_write"` means no prompt arrived before the app moved on (a timeout as the reader saw it). `chunks` lists each received chunk's arrival time and size. Bytes that arrive with no request outstanding are logged as an exchange with `request: null`.

## Effect on the connection

Logging adds no request and no delay. The transport only hands each write and each received chunk to a queue; one background thread formats and writes the files. Cancellation, socket closing and connection ownership are unchanged. The existing tests that check the exact bytes sent still pass with logging on.

## Storage bounds

- At most 8 sessions; starting a new one deletes the oldest.
- Each session stops storing traffic after about 240 kB and all other events after about 256 kB. The dropped count is recorded in a `truncated` event before `session_end`, so a failed or long session stays exportable.
- Sessions survive disconnects, failed operations and app restarts. A session the app never closed (for example, the process was killed) is exported with `ended: false`.

## Export

The export is one JSON document: `format`, `formatVersion`, `exportedAtUtc`, `app` (version name and code, Android version), `privacy`, a `legend`, `truncated`, and `sessions` oldest first, each with `id`, `ended`, `truncated` and its `events`.

The button is on the home screen under the vehicle card. It also appears on a live scan that didn't complete, a recording stopped by anything other than the user, and an experiment attempt that didn't finish. If no log exists the dialog says so. If any session was cut short, or didn't close normally, the dialog says that too. **Share…** opens the share sheet with the file (read-only `content://` link through a `FileProvider` limited to the export folder). **Save to file…** opens the system file picker.

## Privacy

Redacted before writing to disk, and again on export:

- **VINs.** The reply to `0902` has its data lines replaced by `[REDACTED:VIN data, N bytes]`, keeping the responder ID and short replies such as `7F 09 31`. Any responder whose hex payload decodes to a 17-character VIN, with or without ISO-TP framing bytes, is redacted the same way under any request. Plain-text VIN-shaped strings become `[REDACTED:VIN]`.
- **Bluetooth addresses** become `[REDACTED:bluetooth-address]` anywhere they appear, including platform error text.

Kept for debugging: requests, responder IDs, protocol replies, timing, errors, adapter name, app version. **The export is not anonymous.** The adapter name, timestamps and module replies (for example part numbers) remain. Review it before sharing it publicly.

## Code

- `core/.../diagnostics/TrafficSink.kt`: the interface the transport calls.
- `core/.../diagnostics/DiagnosticRedactor.kt`: VIN and address redaction.
- `core/.../storage/DiagnosticLog.kt`: sessions, storage bounds, rotation and export.
- `app/.../bluetooth/BluetoothClassic.kt`: reports writes, reads, write failures, stream end and local close.
- `app/.../connection/VehicleConnection.kt`, `AppViewModel.kt`, `BluetoothBench`: session and operation boundaries.
- `app/.../ui/DiagnosticExport.kt`: the export button and dialog.

Tests: `DiagnosticLogTest` (core: ordering, partial and error replies, persistence, rotation, truncation, redaction) and `DiagnosticExportFlowTest` (app: one connection across identification and a failed scan, restart, share and save intents, empty log, narrow-screen card layout).
