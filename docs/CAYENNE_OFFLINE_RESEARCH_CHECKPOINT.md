# Cayenne deeper-research checkpoint

Checkpoint: 2026-10-30. Research only; no implementation, dependency installation, hardware operation, capture acquisition, VIN collection or publication was performed by this research worker. Parent verified repository base `c1a169b3c21aa8278034933a070e1b288c463619` and branch `research/cayenne-offline-exploration`. This note extends the bounded six-source pass in PR #61; it is not a supported vehicle profile.

## New inspected evidence

1. **OBDb/Porsche-Cayenne has an openly published signal catalog and model-year fixtures.** Its repository advertises CC-BY-SA-4.0. The inspected `signalsets/v3/default.json` blob is `45031b167dcc60205c85d78dd47688d039edc249`; it contains no oil-service/inspection interval definitions. It does contain oil level, warning threshold and engine-oil-temperature definitions. The temperature entry itself says scaling is unknown. Do not equate an oil sensor with a maintenance counter or a model repository with verified exact-trim coverage.
   - Catalog: https://github.com/OBDb/Porsche-Cayenne/blob/main/signalsets/v3/default.json
   - License: https://github.com/OBDb/Porsche-Cayenne/blob/main/LICENSE
2. **There is actual year-labelled test material for 2014.** The `tests/test_cases/2014/command_support.yaml` blob `89cd510d9010abe9fa3e9aa176b9a16a7a2a12f4` records transmission shift-position and four crash-counter commands as supported. It records the catalog's oil-level, oil-warning-threshold and oil-temperature commands as unsupported, along with battery and tire-pressure candidates. These are upstream dataset assertions: exact engine/trim, ECU software, adapter, acquisition method and reasons for unsupported status are not established by the inspected files. An unsupported entry is not proof of universal absence.
   - https://github.com/OBDb/Porsche-Cayenne/blob/main/tests/test_cases/2014/command_support.yaml
3. **A small 2014 shift-position fixture provides concrete framing evidence, unrelated to maintenance.** Blob `45077b1d16a3afb5ff7576f1c6c9811d3ad287eb` records a reply with responder `7E9`, a four-byte payload, positive service `62`, echoed identifier `210F`, and one data byte, mapped to PARK and DRIVE in two examples. This is evidence about the published fixture, not this owner's Cayenne. The service number alone does not distinguish UDS from another compatible diagnostic service family. No command from this source is authorized for transmission or production allowlisting.
   - https://github.com/OBDb/Porsche-Cayenne/blob/main/tests/test_cases/2014/commands/7E1.7E9.22210F.yaml
4. **Related-platform lookup did not yield an interval definition.** The inspected OBDb/Volkswagen-Touareg default catalog has 526 commands, blob `c094b50e6e76eb317d65d8be0a45282ad526af58`. A case-insensitive search for service, maintenance, inspection, oil-distance/time, SIA and WIV found three engine/DPF service-related entries, not maintenance-distance/time counters. This is a bounded negative result, not an exhaustive search or proof about Porsche.
   - https://github.com/OBDb/Volkswagen-Touareg/blob/main/signalsets/v3/default.json
5. **Another diagnostic vendor is worth following.** The inspected PoCOM 2016 Cayenne 958/E2 coverage page distinguishes SDI8 and SDI8 (UDS), and names 958/970 transmission and other module families. This is adjacent-model vendor coverage, not a 2014 protocol identification. Inspect its exact 2014 coverage, cluster page and public measured-value labels next; do not acquire proprietary databases.
   - https://www.obdtester.com/pocom-diagnostic-coverage/porsche/cayenne/2016_958_e2
6. **The owned-adapter distinction remains material.** Veepeak's current OBDCheck BLE page says Classic Bluetooth and BLE, hardware label ELM327 v1.5, generic CAN/KWP/ISO9141/J1850 support, and lists maintenance resets among unsupported functions. Neither generic protocol support nor a reset exclusion settles a read-only manufacturer route. The manufacturer explicitly separates this product from BLE+.
   - https://veepeak.com/products/obdcheck-ble

The GitHub links use moving branches; blob hashes above pin the inspected contents. No upstream code or datasets have been copied into this repository. Inspect the exact license before any reuse. Do not import complete test trees: unrelated captures may contain identifiers outside the task's scope.

## Provisional hypothesis ledger

| Hypothesis | Support / competing explanation | Confidence | Discriminating evidence |
| --- | --- | --- | --- |
| Instrument cluster owns or exposes service counters | Existing Durametric matrix/guide places reminders at cluster; the display might consume derived data from another module | Medium as a vendor-supported target, unknown exact variant | Exact non-VIN cluster part/HW/SW plus lawful, applicable read-only field documentation |
| Some manufacturer-specific reads on a 2014 Cayenne use CAN-framed service 22/62 | OBDb's 2014 shift fixture has this structure; one transmission example does not establish cluster addressing, transport/session, or exact trim | Medium for upstream fixture contents; low for transferring to target cluster | Existing authorized trace/definition identifying module, framing and service family; no new request or sweep |
| A shared VW/platform identifier could help narrow search | Related automotive lineage and reusable public schemas; Touareg catalog search did not find relevant counters and Porsche-specific definitions may differ | Low | Independently sourced matching ECU part/software family and identical documented field semantics |
| Maintenance data may be elapsed/remaining/due-at values with separate oil/inspection distance/time | Existing guide distinguishes categories; plausibly displayed values alone admit several interpretations | Medium for ambiguity; unknown wire meaning | Existing paired labelled observations separated by known distance/time, plus definition of reset and overdue semantics |
| The Veepeak could carry an exact future read if it uses compatible CAN addressing and supported flow control | Manufacturer advertises generic CAN and Classic Bluetooth; clone firmware/features and cluster route are unproven | Low/conditional | First establish complete finite route, then separately authorize bounded adapter validation; no hardware action now |
| Public schemas can support offline tooling without providing Porsche data | OBDb publishes structural signal/fixture representations; existing research names odxtools/UDS/ISO-TP tools | High for tooling distinction | Inspect upstream schema/license and synthetic examples; do not confuse parser support with model definitions |

## Recommended bounded code experiment

Build a **test-only counter-hypothesis experiment**, with no transport reference, production decoder or request bytes. Use the repository's existing Kotlin/JUnit dependencies and entirely constructed observations. Enumerate only a small declared set: two-byte big/little endian, signed/unsigned, explicit scale choices, and remaining/elapsed/due-at semantics. The experiment should report all surviving hypotheses and reasons for rejection rather than selecting a plausible-looking winner.

Minimum cases:

- One positive sample deliberately leaves several hypotheses indistinguishable.
- A second synthetic observation with known distance change distinguishes countdown, elapsed and due-at interpretations.
- Values below the sign boundary leave signed/unsigned ambiguous; a synthetic boundary crossing exposes the difference.
- An all-ones value stays ambiguous between unavailable, signed negative and a valid unsigned quantity unless a supplied synthetic schema declares its meaning.
- Missing unit, wrong width, truncated value, mismatched field identity and mixed service categories produce no interval result.
- Unknown module applicability remains unknown even if one mathematical hypothesis survives.

Keep helpers under test sources or an explicitly isolated research directory. No runtime dependency, Android UI, hardware path, request-policy expansion, OBDb data import or automatic profile activation. This experiment makes the next useful observation explicit and tests ambiguity; it cannot establish Porsche support. A finite test-only opaque-reply matcher using wholly synthetic identifiers is a possible follow-on, but is lower priority than counter semantics because #61 already exercised framing.

## Resume avenues

1. Inspect exact 2014 PoCOM cluster coverage and public measured-value labels. The 2016 URL above is only a lead.
2. Inspect ABRITES' official public Porsche manual's transport explanation, which search surfaced alongside 958 service coverage. Keep security/reset procedures outside the research deliverable; only use model and transport distinctions.
3. Read OBDb's current schema and `dbgfilter` definition before interpreting filters. The year-specific support file is more direct than guessing filter semantics. Pin current commit separately; the saved tree API response reported tree SHA `f8b9f75def18f95cbcaa6a9a55c2b0c1f4e9af52`, which must not be labelled a commit.
4. Inspect upstream odxtools' license and synthetic examples, plus ASAM's public ODX description, for clean-room schema design. No Porsche ODX/PDX/ODIS/PIWIS database acquisition.
5. Search Porsche-origin/NHTSA bulletins specifically for 92A/958 maintenance-counter display or cluster software behavior. Current search results mostly concerned other generations and were not promoted to evidence.
6. Review existing `docs/RESEARCH.md` before repeating general open-source tool surveys; it already covers python-can, can-isotp, udsoncan, ecu_diagnostics, mcd-diag-rs, EdiabasLib and related tools.

## Checkpoint limitations

Research stopped at the owner's sleep/checkpoint instruction. No code experiment was implemented or run, no exact maintenance read definition was found, and no independent review has occurred for this checkpoint. Publication remains blocked at parent level: automatic approval review rejected creation of a public issue because delegated research/code authorization was not judged sufficient for that publication. No issue creation succeeded; this worker performed only local note creation.
