# Safety and evidence boundary

## Capability stages

1. **Default development scope:** analyze authorized, sanitized captures offline. Agents do not operate adapters or vehicles as part of ordinary software work.
2. **Separately scoped owner-operated reads:** generic Bluetooth reads and the fixed [identification experiment](IDENTIFICATION_EXPERIMENT.md) are implemented. Earlier generic observations and partial F187 first frames are recorded in [project status](../PROJECT_STATUS.md). Exact module identity and full v5 multipart completion remain unverified. A generic OBD-II response does not identify a manufacturer diagnostic profile.
3. **Only after vehicle-specific evidence and a new authorization:** propose one exact oil/service reminder change, with before/after reads, prerequisites, bounds, abort conditions and independent review. Oil and inspection intervals may differ. A stored original value is not a guaranteed rollback.

No exact diagnostic family, physical module attribution, maintenance field, adaptation channel, security procedure or reset payload is established for the target 2014 Cayenne S. The fixed experiment assumes ISO-TP framing and has partial owner-observed replies; that evidence does not establish a general manufacturer profile or complete transfer. Do not send guessed requests, perform broad DID sweeps, live replay or fuzzing, clear DTCs, use actuator routines, seek security access, code or flash. A future hardware plan must address stationary vehicle, stable power, compatible lawful tooling, operator authorization and stop criteria.

## Evidence record

Use [the evidence log template](templates/evidence-log.md) for each claim. Keep raw authorized captures immutable and outside Git until a specific sanitized fixture is reviewed. Record hashes, acquisition tool/version, adapter and firmware, timestamp basis, direction, loss, and ECU identity if known. Strip VIN, personal identifiers, secrets and proprietary data before any repo or hosted-model use. Link each decode claim to offsets or timestamps, note conflicting data, and preserve unknown as unknown.

These rules also apply to third-party research: repository code can illustrate architecture without establishing license compatibility, legal access to diagnostic definitions or Cayenne behavior. Do not copy unlicensed code or proprietary diagnostic data.
