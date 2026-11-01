# Importing a capture as a replay fixture (F16)

This guide covers generic OBD captures that could become reviewed replay scripts in Git. Identification reports wrap a separate experiment result and include manufacturer requests outside the generic allowlist. Relabeling their provenance cannot make them executable replay; use [offline diagnostics](OFFLINE_DIAGNOSTICS.md) for inert analysis. **No capture has been imported.** Every replay script in the repository is synthetic, and making a capture needs its own authorized live stage ([LIVE_TEST_GATE.md](LIVE_TEST_GATE.md)). This guide doesn't authorize one.

Replay scripts live in `core/src/main/resources/replay/` (bundled) or in tests. Their format is `ReplayScript` in `core/.../protocol/ReplayTransport.kt`: the request expected at each step, and the adapter's output as timed ASCII chunks.

## Captures from the app

A generic live codes-only or one-pass snapshot session produces a raw capture: the "Copy raw capture for review" button on the finished session. It is a `ReplayScript` JSON with every request and reply chunk and its timing, marked `RAW_CAPTURE`. `ReplayScript.parse` rejects that provenance, so a raw capture can't be used as a fixture by accident. The steps below turn it into one. Expected content of an L2 capture: adapter setup replies, the supported-PID pages (including advertised continuations), the protocol number and trouble codes; snapshots also contain each positively advertised implemented reading. It contains no VIN, because none is requested. Review it anyway before it leaves private storage. After the first write, the capture retains reply chunks byte-for-byte. Pre-write bytes are counted but not retained by `CaptureRecorder`. Replay sends chunks as ASCII, so a byte above 0x7F in a capture would not replay faithfully. ELM327 replies are normally plain ASCII.

**Known limitation: disconnects are not recorded.** The capture holds each request and each received chunk with its timing, but not the moment the connection ended. A session that ended `DISCONNECTED` (for example, the adapter unplugged) replays as silence after its last chunk and ends `FAILED` instead. If the connection dropped while a reply was awaited, the reader waits out its deadline and resync window and reports no response. If it dropped between requests, the reader sends a request the capture doesn't contain, and replay stops with a `ReplayDivergedException`. Either way the replay doesn't reproduce the `DISCONNECTED` outcome. Note a disconnect in the evidence log, and add `disconnectAfterMillis` to the sanitized script by hand if the fixture needs it.

## Before anything enters Git

1. **Authorized source.** The capture comes from a stage the owner approved in writing, in that stage's issue. If you can't name the issue, stop.
2. **Raw capture stays outside Git, unchanged.** Store the original file somewhere private and never edit it. Every later step works on a copy.
3. **Hash it.** Record the SHA-256 of the original file before making any copy.
4. **Record acquisition and loss.** Fill in an [evidence log](templates/evidence-log.md) with the tool and version, adapter and firmware as printed or reported, timestamp basis, direction of each byte stream, and any known loss: dropped bytes, buffer overruns, gaps, or a capture started mid-session.
5. **Redact the copy.**
   - Remove VINs, mode 09 replies, serial numbers, owner names, locations, Bluetooth addresses and anything else that identifies a person or vehicle.
   - Keep only the allowlisted requests (`docs/PROTOCOL.md`). A script that includes any other request fails `ReplayScript.problems()`.
   - Don't replace a removed value with an invented one that looks real. Drop the step or note the gap instead.
   - Record each redaction in the evidence log.
6. **Convert.** Write the redacted copy as a `ReplayScript` with:
   - `provenance`: `SANITIZED_CAPTURE`
   - `sourceTier`: `capture`
   - `sha256`: the original's hash from step 3
   - `evidenceLog`: a link to the evidence log
   - `source`: what the capture is and what was removed

   Chunk timings come from the capture's timestamps, never tuned by hand.
7. **Owner review.** The owner reviews the script and its evidence log before it is merged. The review checks for leftover identifiers as well as format.

## What a replayed capture proves

It proves that this code handles those exact bytes in that order. Replay gives no evidence about other adapters, other vehicles, the same adapter's timing on another day, or what a signal means. The deadlines in `AdapterProfile.PROVISIONAL` stay provisional until authorized L1 testing measures real timing, and a single capture doesn't change that.
