# Cayenne service read: owner evidence proposal

Draft for owner review, 2026-10-30. Target: 2014 Porsche Cayenne S (958, V8). The goal is useful open-source oil and inspection information with definitions we can substantiate. No claim of being first is made. This proposal authorizes no new connection or diagnostic operation.

## Useful evidence from ordinary use

When service information is already available in existing notes or during ordinary dashboard use, record the exact wording and values. There is no need for a dedicated drive, ignition cycle, reset, diagnostic session or new report to supply this evidence.

- Preserve each displayed label, including oil, intermediate or main service only if actually present. Do not rename them all “inspection.”
- Record distance and time separately, with their displayed units and any “remaining,” “since,” “due” or overdue wording. Preserve dashes, unavailable messages and rounding; missing information is not zero.
- Include the observation date, market if already known, and the odometer and its unit if already available. Exclude VIN, registration and personal information from shared material.

Use one row per actual display label; leave unavailable details marked unknown:

| Observation | Exact display label and wording | Distance and unit | Time and unit | Date; odometer and unit |
| --- | --- | --- | --- | --- |
| A | unknown | unknown | unknown | unknown |
| B, optional later ordinary observation | unknown | unknown | unknown | unknown |

If an existing lawful diagnostic report is available, a transcription of the cluster's part number, hardware version and software version would narrow the variant. Include the report's tool/version and date if known, and preserve whether each item is a reported identifier or a tool's descriptive label. Leave missing items unknown. Share only these relevant non-VIN details, not a whole report, serial identifiers or customer information. Do not create a new report or enter a maintenance wizard to obtain them: documented service workflows can write settings even when some screens show current values.

An optional second observation can be collected at a naturally later opportunity. Keep the same labels and units, and record the known date and odometer differences. Note any intervening service, reset or unit change already known to have occurred. Do not cause one for this experiment. If the change is unknown or smaller than display rounding, the comparison may remain inconclusive.

## What the comparison can establish

Paired displays can show whether a displayed value counts down or up over the observed distance or time, and whether oil and inspection labels expose separate quantities. They can help reject an interpretation that predicts the opposite direction under the same conditions. No change can also reflect rounding; a short matching trend does not prove a fixed service algorithm.

A displayed countdown cannot distinguish a stored remaining value from a calculation using elapsed distance, an absolute due point, or last-service mileage plus a configured interval. Existing, explicitly labelled last-service and interval values could support a consistency check, but identical arithmetic still would not identify the underlying fields. Dashboard observations and module identity do not establish request bytes, response layout, units of a raw field, or a diagnostic protocol.

## What a future diagnostic proposal still needs

The current [evidence matrix](CAYENNE_MAINTENANCE_READ.md) identifies these unresolved requirements. The cluster is a vendor-supported research lead, not a verified route for this exact vehicle. Generic OBD replies and a compatible response envelope cannot establish its manufacturer-specific protocol. The owned Veepeak OBDCheck BLE, Bluetooth Classic and Samsung S24 are known; compatibility with this proposed read is unknown.

| Required before an executable proposal | Current status |
| --- | --- |
| Exact market and cluster part/hardware/software, linked to an applicable source | unknown |
| Transport/protocol, bitrate, addressing form and gateway route | unknown |
| Exact physical request, response and any flow-control addresses | unknown |
| Finite, documented read requests and their expected replies, tied to each service label | unknown |
| Field widths, byte order, sign, scale, units, unavailable and overdue meanings | unknown |
| Required session, initialization and keep-alive behavior, with evidence the whole sequence remains read-only | unknown |
| Adapter/firmware support, bounded request count, timings, deadlines and stop behavior | unknown |
| Reviewed software version, vehicle conditions and separate specific owner go for that complete plan | not established |

These blanks are deliberate: filling them with guesses would make a manual exploration proposal misleading. No numeric identifiers, trial requests or operational steps are proposed here. A future plan must specify all transmissions, including adapter-originated setup traffic, and how unexpected replies or vehicle behavior end the run. It must not introduce identifier sweeps, automatic fallback exploration, writes, resets, security access or VIN requests.

The owner would review and approve that concrete, finite plan before any new connection, under the [live-test gate](LIVE_TEST_GATE.md). Prior generic OBD authorization does not establish permission for a manufacturer-specific cluster read. Creating a public issue, pushing or opening a PR remains outside the current authorization; this is a local draft only.

The [research note](CAYENNE_CLOUD_RESEARCH.md) records the source evidence and the synthetic counter experiment. Public documentary research can continue independently while these observations, if available, help narrow applicability. Neither the experiment nor this proposal adds supported Porsche service functionality yet.
