# Safety and evidence boundary

## Capability stages

1. **Now:** analyze authorized, sanitized captures offline. No live transmission.
2. **Later, by separate reviewed scope:** identify the exact adapter, market, ECU hardware/software and transport through authorized reads. A generic OBD-II response does not identify a manufacturer diagnostic profile.
3. **Only after vehicle-specific evidence and a new authorization:** propose one exact oil/service reminder change, with before/after reads, prerequisites, bounds, abort conditions and independent review. Oil and inspection intervals may differ. A stored original value is not a guaranteed rollback.

No UDS, ISO-TP, VW gateway address, adaptation channel, security procedure or reset payload is established for the target 2014 Cayenne S. Do not send guessed requests, perform broad DID sweeps, live replay or fuzzing, clear DTCs, use actuator routines, seek security access, code or flash. A future hardware plan must address stationary vehicle, stable power, compatible lawful tooling, operator authorization and stop criteria.

## Evidence record

Use [the evidence log template](templates/evidence-log.md) for each claim. Keep raw authorized captures immutable and outside Git until a specific sanitized fixture is reviewed. Record hashes, acquisition tool/version, adapter and firmware, timestamp basis, direction, loss, and ECU identity if known. Strip VIN, personal identifiers, secrets and proprietary data before any repo or hosted-model use. Link each decode claim to offsets or timestamps, note conflicting data, and preserve unknown as unknown.

These rules also apply to third-party research: repository code can illustrate architecture without establishing license compatibility, legal access to diagnostic definitions or Cayenne behavior. Do not copy unlicensed code or proprietary diagnostic data.
