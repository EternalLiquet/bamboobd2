# Evidence: Does the owner's Veepeak OBDCheck BLE support Classic Bluetooth on Android?

- Date and observer: 2026-10-13, Claude (planning session).
- Status: **hypothesis**, unresolved.
- Source and license/provenance: [issue #2](https://github.com/EternalLiquet/bamboobd2/issues/2) states that OBDCheck BLE supports Android over Classic and BLE, citing the [Veepeak product page](https://veepeak.com/products/obdcheck-ble). That page could not be fetched from the planning session (DNS failure), so the claim was not checked at the source. A web search returned snippets (not fetched pages) of retailer listings, including [OBD Planet's BLE vs BLE+ comparison](https://obdplanet.com/veepeak-obdcheck-ble-plus-vs-ble/) and a [Newegg listing](https://www.newegg.com/p/0H9-07SW-000B3). Per the snippets: BLE+ is listed as BLE plus Classic on Android; the original BLE is listed as Bluetooth 4.0 / BLE only. Retailer text is not independent testing and may be copied between products.
- Authorized capture hash and storage reference: none.
- Tool, adapter, firmware, ECU identity if known, timestamp basis and capture loss: not applicable; the adapter was not examined.
- Frame offsets/timestamps and measured result: none.
- Competing explanation and discriminating test: (A) the owner's adapter is dual-mode, as issue #2 says. (B) It is the original BLE-only model, or the BLE+ model was meant. Discriminators: the adapter's printed model name, vendor documentation retrieved by someone with access, and whether the adapter can be paired in Android system Bluetooth settings (suggests Classic) or appears only in a BLE scan.
- Confidence and limitations: low for both A and B.
- Next safe experiment: proposed slice S6 (adapter and host-device facts), documentation and owner-provided facts only; no vehicle needed.
