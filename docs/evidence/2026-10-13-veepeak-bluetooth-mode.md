# Evidence: Does the Veepeak OBDCheck BLE support Classic Bluetooth on Android?

- Date and observer: opened 2026-10-13, updated 2026-10-27; Claude (planning sessions).
- Status: **vendor-documented** for the model. The owner's particular unit and firmware are **untested**.
- Source and license/provenance:
  - According to a search summary of the [official OBDCheck BLE product page](https://veepeak.com/products/obdcheck-ble) (see below for how it was read), the page lists the adapter as supporting "Bluetooth LE & Classic Bluetooth" and lists Android phones and tablets among supported devices (Android head units "may not be compatible"), as well as iOS, Windows and Mac.
  - According to the search summary of Veepeak's Android connection guidance, Veepeak recommends choosing Bluetooth LE in apps (no PIN or system pairing needed) and says Classic Bluetooth may bring up a system pairing request (PIN listed as 1234).
  - How it was read on 2026-10-27: this session's network policy blocks veepeak.com, so the page could not be fetched directly. The statements above come from a web search restricted to veepeak.com, which returned the product page and the guidance pages with summarized content. The owner independently reported that the official page lists both modes. Anyone with access should re-read the page and record the date.
  - Superseded: the 2026-10-13 version of this note relied on retailer search snippets suggesting that only the OBDCheck BLE+ supports Classic. That reading is withdrawn; it is contradicted by the vendor page and was never verified.
- Authorized capture hash and storage reference: none.
- Tool, adapter, firmware, ECU identity if known, timestamp basis and capture loss: not applicable; no adapter was examined.
- Frame offsets/timestamps and measured result: none.
- Competing explanation and discriminating test: vendor support for the model does not show that the owner's unit (hardware revision, firmware) behaves the same, or that a given phone pairs reliably over Classic. Discriminators: the label on the owner's unit (exact model name, any printed version), and pairing/scan behavior and `ATI` responses observed during a separately authorized L1 bench test ([live-test gate](../LIVE_TEST_GATE.md)).
- Confidence and limitations: moderate to high that the vendor documents both modes for this model. Two consistent reports exist (a summarized search result and the owner's own reading), but the page was not fetched directly in this session. No confidence yet for the owner's unit. The vendor's own recommendation of Bluetooth LE on Android is relevant to the Classic-first choice and is not yet resolved.
- Next safe experiment: proposed slice S6, documentation only (label, vendor documentation, already-existing observations). New observations belong to L1.
