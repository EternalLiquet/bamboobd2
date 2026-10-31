# Bounded identification experiment implementation plan

> For agentic workers: use executing-plans for the runner and independent parallel implementation for offline analysis, then independent whole-branch review.

**Goal:** Prepare a finite, owner-reviewable identification experiment and the minimum offline tools needed to understand its results.

**Architecture:** A fixed experiment plan and cancellable runner live separately from normal scans. This revision refuses LIVE transports before any write; the owner must first review the exact plan. An offline recording wrapper reuses capture chunks and ELM framing but never converts imported requests into executable requests. Explicit field definitions and comparisons remain small pure functions.

**Tech stack:** Existing Kotlin/JVM core, coroutines, serialization, JUnit. No new dependencies.

**Spec:** Owner's 2026-10-31 handoff and priority adjustment. Routine implementation/commits/push/draft PRs authorized; no merge or hardware use. Separate experiment activation requires exact-test review.

## Decisions and constraints

- Fresh checkout on `feat/bounded-identification-evidence`, master `c1a169b3c21aa8278034933a070e1b288c463619`.
- PR63 inspected, remains independent. Reuse its explicit-category, sentinel and ambiguity principles; do not promote synthetic hypotheses to definitions.
- Tentative route is a hypothesis, possibly PCM. No verified Porsche cluster/service definition is included.
- Normal RequestPolicy and replay import policy remain unchanged. Offline importer cannot send.
- Keep provenance, byte ordering, original chunks, timing, responder and uncertainty. Reports are private local output by default, not auto-redacted public evidence.
- No VIN/serial requests, session changes, scan loops, fallback routes, writes, resets or security access.
- No Android screen in this slice: focus on executable JVM example and reviewed runner; LIVE gate cannot be bypassed with imported data or a caller boolean.

## Review focus

Malformed/oversized input; pending without final response; incorrect echo or responder; false reset success from acknowledgements; persistent adapter state after cancellation.

## Tasks

- [x] Add `ElmResponseParser.parseRecorded(text, requestText, format)` so offline parsing reuses framing without constructing Request. Preserve existing parser signature and behavior; focused framing regression.
- [x] Add offline recording/analyzer, explicit decoder and conservative comparison under core/offline with constructed fixtures and focused tests. Independent agent owns those files. Public definitions remain empty for Porsche.
- [x] Add fixed identification plan, simulated-only cancellable runner and tests under core/experiment. Record all attempted writes/reads and stop on unexpected or incomplete replies; retain immutable plan and no retries. Exact candidate sequence and evidence in docs.
- [x] Add JVM entry point/task: print plan, run constructed demonstration, analyze local capture wrapper. Filesystem access only in core/storage. No Bluetooth binding.
- [ ] Run core check and hosted required Android checks, independently review final diff, fix substantive findings, commit/push and open draft PR. No merge.

## Execution notes

Initial wrapper baseline failed before build: Java network access did not inherit the current proxy. Downloaded the exact Gradle distribution using the normal HTTPS proxy and verified its pinned SHA256; no repository dependency or network-policy change. Local helper supplies the current proxy to Gradle. Test results recorded at handoff.

Review/checkpoint notes: full core check and synthetic demo passed. Independent review found empty-chunk retention, unexpected adapter search text and spaced recorded echoes; each received a failing regression followed by a fix. Caller-timeout cancellation and initial-ATD no-retry behavior likewise reproduced before fixing. Final checks/review and PR links are recorded in the PR handoff. Live integration is deliberately not enabled; owner must review the exact experiment first.
