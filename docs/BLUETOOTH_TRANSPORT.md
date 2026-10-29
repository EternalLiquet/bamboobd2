# Bluetooth Classic transport (issue #12)

The first real `AdapterTransport`: an RFCOMM (Serial Port Profile) connection from the Android app to an adapter already paired in system settings. It is implemented and tested offline only. **Nothing here has run on hardware.** Running it on a powered adapter is stage L1 and needs its own go ([LIVE_TEST_GATE.md](LIVE_TEST_GATE.md)). Hardware facts and unknowns: [evidence note](evidence/2026-10-28-hardware-readiness.md).

## Sources (checked 2026-10-28)

| Source | Used for |
| --- | --- |
| [Android: Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) (page "last updated 2026-10-28") | Android 12+: `BLUETOOTH_CONNECT` to communicate with already-paired devices, a runtime permission. `BLUETOOTH_SCAN` only for finding devices. Android 11 and lower: legacy `BLUETOOTH`, declared with `maxSdkVersion` 30; location there is for scanning |
| [Android: Connect Bluetooth devices](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) (last updated 2026-10-23) | `createRfcommSocketToServiceRecord(UUID)`, then `connect()`, which blocks (times out after about 12 s) and must run off the main thread. Closing the socket from another thread aborts a blocked call |
| Android SDK 36 `data/annotations.zip` (`@RequiresPermission`) | `getBondedDevices()`, `getName()`, `getBondState()` and `BluetoothSocket.connect()` need `BLUETOOTH_CONNECT`. **`cancelDiscovery()` needs `BLUETOOTH_SCAN`**, so the app does not call it |
| `BluetoothDevice.createRfcommSocketToServiceRecord` reference, read through a search restricted to `developer.android.com` | For serial boards, Android suggests the well-known SPP UUID `00001101-0000-1000-8000-00805F9B34FB` |
| Veepeak guidance, read through a search restricted to `veepeak.com` (site blocked here) | Classic may show a system pairing request, PIN 1234; BLE recommended. Details in the evidence note |

## What the app does

- **No discovery or scanning.** The user pairs the adapter in system Bluetooth settings. The app lists bonded devices by name and connects to the one picked. Bluetooth addresses are used only to connect and are never stored.
- **Permissions:** `BLUETOOTH_CONNECT` (runtime, Android 12+) and `BLUETOOTH` with `maxSdkVersion="30"`. No `BLUETOOTH_SCAN`, `BLUETOOTH_ADMIN`, `BLUETOOTH_ADVERTISE`, location or Internet. `android.hardware.bluetooth` is declared not required, so the offline app installs and works without Bluetooth. F11's merged-manifest check (`verifyNoForbiddenPermissions`) now requires exactly this set and fails on anything else in those families.
- **Denied permission, Bluetooth off, no adapter, nothing paired:** each is shown on the bench screen with what to do. Simulated and replay sessions keep working.
- **`BluetoothClassicTransport`** (`TransportKind.LIVE`): a background reader pumps the socket into a queue, so a timed-out read loses no bytes, and a chunk taken but not delivered goes back to the front. Write failures and a closed socket become `TransportClosedException`, and the end of the stream reads as `null`. It is never reopened.
- **Cancellation:** cancelling during the blocking `connect()` closes the socket, which aborts it. Cancelling later closes the transport. Nothing reconnects automatically.

## The adapter-only bench path (L1)

- `BluetoothBench` connects, runs `AdapterBenchCheck` (in `:core`) and closes. The Bluetooth transport never leaves `BluetoothBench`, so in this stage the app has **no way to run a vehicle-read session over Bluetooth**. Live vehicle reads (L2) are not wired.
- `AdapterBenchCheck` sends only `ATI`, `AT@1` and `ATRV` from the owner-approved list. Every write passes `AdapterBenchCheck.guard`, which rejects every vehicle request, allowlisted or not; a test runs all of them through it.
- Each command is sent once. A missed deadline (`AdapterProfile.PROVISIONAL`, unmeasured) stops the check without sending more, because any character interrupts a busy adapter (ELM327DSJ p9). A disconnect or cancel stops it too, and replies already read stay on screen. On cancel, the command in progress is reported as "interrupted after sending", with any partial reply and its timing, or as "write interrupted, may not have been sent" if the write itself was cancelled. Only commands never sent show "not run".
- For the test handoff, the screen shows "This phone: Android <version> (API <level>)" and a copyable report. The report holds the app version, that line, each command's reply, timing and chunk count, and how the check ended. It has no device identifiers, no Bluetooth address and no adapter serial.
- The banner reads "ADAPTER BENCH — adapter-only commands, never vehicle requests". Confirming before a run is not required; authorization lives in the L1 issue, not in the app.

## The codes-only live read (L2)

- A separate **Read codes** screen (banner "LIVE READ — car codes only, no sensor readings or clearing") connects to a paired adapter and runs the normal `ElmObdReader` session in **codes-only scope** (`ReadScope.CODES_ONLY` in `:core`).
- **Sequence:** `ATE0`, `ATL0`, `ATH1` and `ATSP0`, each of which must answer `OK`. Then `0100`, during which the adapter searches protocols (bounded 30 s budget). Then `ATDPN`, which reports the protocol found and picks the reply form. Then `03` and `07`. Any of these may be sent once more under the single retry after a missed deadline. No sensor PIDs, no clearing, no other protocol command; the protocol is detected, not hardcoded. A test checks the exact bytes written.
- **When the car doesn't answer:** a session that gets nothing says "Finished, but nothing was read from the car". It shows the reported protocol and the adapter's reason for each list it couldn't read (for example `BUS INIT: ...ERROR`). It never says "no codes" unless both code lists were read.
- **Behavior:** the same deadlines, single retry, response-pending and stale-reply rules as replay sessions. Provenance is `LIVE`, from the transport. The session can be saved and reopened like any other. The connection closes when the session ends; nothing reconnects.
- **Raw capture:** the transport records every request and reply chunk with its timing. The finished session screen offers "Copy raw capture for review". The capture is kept in memory only and marked `RAW_CAPTURE`, which the replay loader rejects until it goes through [CAPTURE_IMPORT.md](CAPTURE_IMPORT.md).
- The bench check stays a separate adapter-only path.

## G2 transport registry

`BluetoothClassicTransport LIVE reviewed:issue-12` is pinned in `transport-registry.txt`. A LIVE entry now needs a `reviewed:<reference>` marker. An unpinned, unmarked or undeclared transport still fails. **The marker records code review only; it is not permission to run the transport on hardware.**

## Verified offline vs needs hardware

| Verified offline (Robolectric on the JVM, not a device) | Needs L1 on the real phone and adapter |
| --- | --- |
| Permission-needed and denied states; Bluetooth off; paired-device listing (Robolectric Bluetooth shadows) | The real permission prompt on the S24 and its Android version |
| Bench over a fake RFCOMM socket: only `ATI`, `AT@1`, `ATRV` written, CR-terminated; replies parsed; connect failure; cancel during a blocking connect closes the socket; disconnect keeps earlier replies | Whether the unit pairs and connects over Classic with SPP, and how reliably |
| A paired shadow adapter that never answers ends as "no reply" through the real `createRfcommSocketToServiceRecord` path | Real chunk sizes, timing, status strings and `ATI`/`AT@1`/`ATRV` replies |
| Merged manifest has exactly the reviewed Bluetooth permissions | Behavior when Bluetooth is toggled or the adapter loses power mid-check |
