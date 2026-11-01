# Bluetooth Classic transport (issue #12)

The first real `AdapterTransport`: an RFCOMM (Serial Port Profile) connection from the Android app to an adapter already paired in system settings. The owner has observed Classic adapter connection and earlier generic reads. The expanded catalog/recording and v5 identification completion remain without vehicle validation. New hardware operation needs its own reviewed scope ([LIVE_TEST_GATE.md](LIVE_TEST_GATE.md)). Hardware facts and unknowns: [evidence note](evidence/2026-10-28-hardware-readiness.md).

## Sources (checked 2026-10-28)

| Source | Used for |
| --- | --- |
| [Android: Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) (page "last updated 2026-10-28") | Android 12+: `BLUETOOTH_CONNECT` to communicate with already-paired devices, a runtime permission. `BLUETOOTH_SCAN` only for finding devices. Android 11 and lower: legacy `BLUETOOTH`, declared with `maxSdkVersion` 30; location there is for scanning |
| [Android: Connect Bluetooth devices](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) (last updated 2026-10-23) | `createRfcommSocketToServiceRecord(UUID)`, then `connect()`, which blocks (times out after about 12 s) and must run off the main thread. Closing the socket from another thread aborts a blocked call |
| Android SDK 36 `data/annotations.zip` (`@RequiresPermission`) | `getBondedDevices()`, `getName()`, `getBondState()` and `BluetoothSocket.connect()` need `BLUETOOTH_CONNECT`. **`cancelDiscovery()` needs `BLUETOOTH_SCAN`**, so the app does not call it |
| `BluetoothDevice.createRfcommSocketToServiceRecord` reference, read through a search restricted to `developer.android.com` | For serial boards, Android suggests the well-known SPP UUID `00001101-0000-1000-8000-00805F9B34FB` |
| Veepeak guidance, read through a search restricted to `veepeak.com` (site blocked here) | Classic may show a system pairing request, PIN 1234; BLE recommended. Details in the evidence note |

## What the app does

- **No discovery or scanning.** The user pairs the adapter in system Bluetooth settings. The app lists bonded devices by name. Since #66 the chosen scanner's name and address are saved in app-private preferences (backup off) so startup can reconnect to it; **Forget scanner** removes them. Addresses never enter records, captures, reports or logs. See [vehicle startup](VEHICLE_STARTUP.md).
- **Permissions:** `BLUETOOTH_CONNECT` (runtime, Android 12+) and `BLUETOOTH` with `maxSdkVersion="30"`. No `BLUETOOTH_SCAN`, `BLUETOOTH_ADMIN`, `BLUETOOTH_ADVERTISE`, location or Internet. `android.hardware.bluetooth` is declared not required, so the offline app installs and works without Bluetooth. F11's merged-manifest check (`verifyNoForbiddenPermissions`) now requires exactly this set and fails on anything else in those families.
- **Denied permission, Bluetooth off, no adapter, nothing paired, saved scanner unpaired:** each is shown on the home screen (and the bench screen) with what to do. Simulated and replay sessions keep working.
- **`BluetoothClassicTransport`** (`TransportKind.LIVE`): a background reader pumps the socket into a queue, so a timed-out read loses no bytes, and a chunk taken but not delivered goes back to the front. Write failures and a closed socket become `TransportClosedException`, and the end of the stream reads as `null`. It is never reopened.
- **Cancellation:** cancelling during the blocking `connect()` closes the socket, which aborts it. Cancelling later closes the transport. Nothing reconnects automatically.

## The adapter-only bench path (L1)

- `BluetoothBench` connects, runs `AdapterBenchCheck` (in `:core`) and closes. The bench owns and closes its transport and sends no vehicle requests. Separate generic live/recording and identification paths also use Bluetooth, under mutually exclusive app ownership.
- `AdapterBenchCheck` sends only `ATI`, `AT@1` and `ATRV` from the owner-approved list. Every write passes `AdapterBenchCheck.guard`, which rejects every vehicle request, allowlisted or not; a test runs all of them through it.
- Each command is sent once. A missed deadline (`AdapterProfile.PROVISIONAL`, unmeasured) stops the check without sending more, because any character interrupts a busy adapter (ELM327DSJ p9). A disconnect or cancel stops it too, and replies already read stay on screen. On cancel, the command in progress is reported as "interrupted after sending", with any partial reply and its timing, or as "write interrupted, may not have been sent" if the write itself was cancelled. Only commands never sent show "not run".
- For the test handoff, the screen shows "This phone: Android <version> (API <level>)" and a copyable report. The report holds the app version, that line, each command's reply, timing and chunk count, and how the check ended. It has no device identifiers, no Bluetooth address and no adapter serial.
- The banner reads "ADAPTER BENCH — adapter-only commands, never vehicle requests". Confirming before a run is not required; authorization lives in the L1 issue, not in the app.

## The codes-only live read (L2)

- The **Scan** screen offers codes only, a supported-sensor snapshot and live recording, on the home screen's shared connection (`connection/VehicleConnection.kt`), after identification. Codes-only uses `ElmObdReader` with `ReadScope.CODES_ONLY`; [recording](LIVE_RECORDING.md) uses the same transport serially.
- **Sequence:** `ATE0`, `ATL0`, `ATH1` and `ATSP0`, each of which must answer `OK`. Then `0100`, during which the adapter searches protocols (bounded 30 s budget). Then `ATDPN`, which reports the protocol found and picks the reply form. Then advertised support-page continuations through at most eight pages total, followed by `03` and `07`. Discovery has a 180-second monotonic budget including setup. Any of these may be sent once more under the single retry after a missed deadline. No sensor PIDs, no clearing, no other protocol command; the protocol is detected, not hardcoded. A test checks the exact bytes written.
- **When the car doesn't answer:** a session that gets nothing says "Finished, but nothing was read from the car". It shows the reported protocol and the adapter's reason for each list it couldn't read (for example `BUS INIT: ...ERROR`). It never says "no codes" unless both code lists were read.
- **Behavior:** the same deadlines, single retry, response-pending and stale-reply rules as replay sessions. Provenance is `LIVE`, from the transport. The session can be saved and reopened like any other. The connection stays open after the session for the next scan, until the user disconnects, the link drops or the app goes to the background; nothing reconnects by itself.
- **Raw capture:** the transport records every request and reply chunk of that session with its timing, starting at the session's own setup; identification (and its VIN reply) is never included. The finished session screen offers "Copy raw capture for review". The capture is kept in memory only and marked `RAW_CAPTURE`, which the replay loader rejects until it goes through [CAPTURE_IMPORT.md](CAPTURE_IMPORT.md). It doesn't record when a connection ended, so a disconnected session doesn't replay as `DISCONNECTED` (see the limitation there).
- The bench check stays a separate adapter-only path.

## G2 transport registry

`BluetoothClassicTransport LIVE reviewed:issue-12` is pinned in `transport-registry.txt`. A LIVE entry now needs a `reviewed:<reference>` marker. An unpinned, unmarked or undeclared transport still fails. **The marker records code review only; it is not permission to run the transport on hardware.**

## Original offline checks and remaining hardware questions

The table was the original L1 checklist. Owner-reported connection and adapter replies subsequently satisfied L1; see the evidence note above. Reliability, fault scenarios and unobserved device behavior remain unverified.

| Verified offline (Robolectric on the JVM, not a device) | Original hardware question (see subsequent evidence above) |
| --- | --- |
| Permission-needed and denied states; Bluetooth off; paired-device listing (Robolectric Bluetooth shadows) | The real permission prompt on the S24 and its Android version |
| Bench over a fake RFCOMM socket: only `ATI`, `AT@1`, `ATRV` written, CR-terminated; replies parsed; connect failure; cancel during a blocking connect closes the socket; disconnect keeps earlier replies | Whether the unit pairs and connects over Classic with SPP, and how reliably |
| A paired shadow adapter that never answers ends as "no reply" through the real `createRfcommSocketToServiceRecord` path | Real chunk sizes, timing, status strings and `ATI`/`AT@1`/`ATRV` replies |
| Merged manifest has exactly the reviewed Bluetooth permissions | Behavior when Bluetooth is toggled or the adapter loses power mid-check |

## Identification ownership and reuse

The separate [fixed experiment](IDENTIFICATION_EXPERIMENT.md) uses bounded startup draining, an ATPC handshake and conditional manual flow control. `AppViewModel` excludes competing bench/live/recording connections, closes on cancellation/backgrounding, and persists an adapter power-cycle acknowledgment requirement after attempted commands. Socket closure alone does not restore experimental headers and filters. A zero-write attempt clears the guard after closure; uncertain writes/process death retain it. Since [epic #66](https://github.com/EternalLiquet/bamboobd2/issues/66), the experiment and bench check first close the shared vehicle connection, and the power-cycle guard also blocks reconnecting it until acknowledged.
