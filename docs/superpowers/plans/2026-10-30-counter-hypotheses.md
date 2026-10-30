# Synthetic Counter Hypotheses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The established handoff selects a separate GPT-6 Sol HIGH implementer; the parent owns dispatch and subsequent independent reviews.

**Goal:** Execute small synthetic examples that distinguish mathematical counter interpretations while preserving uncertainty about vehicle applicability.

**Architecture:** Two files in the existing core test source set: a bounded pure evaluator and its JUnit tests. No production caller, transport, request bytes, imported diagnostic data or external schema is involved. Results describe mathematical fits and rejected candidates; none is a vehicle definition.

**Tech Stack:** Existing Kotlin/JVM and JUnit 4 dependencies, JDK 21 running Gradle, bytecode target 17.

**Spec:** [Cayenne cloud research and experiment requirements](../../CAYENNE_CLOUD_RESEARCH.md), especially Selected offline experiment; [desktop checkpoint](../../CAYENNE_OFFLINE_RESEARCH_CHECKPOINT.md).

## Global Constraints

- No vehicle or adapter operation, new request, sweep, reset/write, security access, VIN collection or proprietary data.
- All inputs are newly constructed and labelled synthetic; no public-source fixture or code import.
- No main-source, Android, permission, dependency, build, CI, allowlist or protocol-fixture changes.
- Unknown or related-platform-only applicability is preserved even when only one mathematical fit remains.
- Public issues, pushes and PRs remain blocked; this plan authorizes none. The parent manages local checkpoint/review scope. No commit is part of this implementation task.
- Bound this experiment to exactly two-byte fields, scales of 1 or 100 units per count, at most 16 observations and at most 32 explicitly supplied candidates. These are research limits, not vehicle facts.

## Review Focus

- Signedness and byte order can produce equally plausible results: assert all fits, not a winner.
- A declared numeric all-ones value differs from an unavailable or unspecified one: cover all three.
- Category or unit mismatches must fail before any counter calculation: cover observation and interval metadata.
- Two constant-anchor interpretations can agree with changing countdowns: retain both.
- A uniquely fitting mathematical model must preserve unknown applicability: assert it directly.

---

### Task 1: Implement and verify the finite test-only evaluator

**Files:**

- Create `core/src/test/kotlin/io/github/eternalliquet/bamboobd2/core/research/CounterHypotheses.kt`.
- Create `core/src/test/kotlin/io/github/eternalliquet/bamboobd2/core/research/CounterHypothesesTest.kt`.

**Interfaces:** Use `internal` declarations in package `io.github.eternalliquet.bamboobd2.core.research`.

- `CounterOrder { BIG_ENDIAN, LITTLE_ENDIAN }`
- `CounterMeaning { REMAINING, ELAPSED, DUE_AT, LAST_SERVICE_PLUS_INTERVAL }`
- `CounterUnit { KILOMETERS, DAYS }`
- `ServiceCategory { OIL, INTERMEDIATE, MAIN }`
- `AllOnesMeaning { UNSPECIFIED, NUMERIC, UNAVAILABLE }`
- `ApplicabilityNote { UNKNOWN, RELATED_PLATFORM_ONLY }`
- `FieldSpec(fieldKey: String, widthBits: Int?, unit: CounterUnit?, category: ServiceCategory?)`
- `CounterObservation(field: FieldSpec, bytes: List<Int>, position: Long)`; `position` is the known current odometer or abstract day coordinate in the declared unit.
- `DeclaredInterval(value: Long, unit: CounterUnit, category: ServiceCategory)`.
- `CounterHypothesis(id: String, order: CounterOrder, signed: Boolean, unitsPerCount: Int, meaning: CounterMeaning, interval: DeclaredInterval? = null, allOnes: AllOnesMeaning = UNSPECIFIED)`.
- `CounterFit(hypothesisId: String, remaining: List<Long>)` and `CounterRejection(hypothesisId: String, reason: String)`.
- `CounterReport(fits: List<CounterFit>, rejections: List<CounterRejection>, applicability: ApplicabilityNote)`.
- `evaluateCounterHypotheses(observations: List<CounterObservation>, candidates: List<CounterHypothesis>, applicability: ApplicabilityNote): CounterReport`.

No public serializer, CLI, registration, profile type or transport reference. Preserve supplied candidate order. Reject duplicated/blank candidate IDs and malformed whole-experiment input with `IllegalArgumentException`; return a candidate rejection for a mathematically contradicted or incomplete candidate.

- [x] **Step 1: Write the failing tests for interpretation ambiguity.** Use helper builders only inside the test file to keep cases readable. Exact assertions:

| Test | Synthetic input and expected assertion |
| --- | --- |
| `singleObservationKeepsEndianAndSignAlternatives` | Bytes `[1,2]`, position 1000; four scale-1 REMAINING candidates covering BE/LE and signed/unsigned. Fits in supplied order have remaining `[258]`, `[258]`, `[513]`, `[513]`; no rejections. |
| `declaredScaleIsNotChosenByPlausibility` | Bytes `[0,2]`; BE unsigned REMAINING scales 1 and 100. Both fit with `[2]` and `[200]`. |
| `distanceChangeSeparatesCountdownFromElapsedAndAnchors` | Bytes `[0,100]`, `[0,90]`; positions 1000,1010. Supply BE unsigned scale-1 candidates for all four meanings, intervals of 200 km for ELAPSED/LAST_SERVICE. Only REMAINING fits, with `[100,90]`; rejected IDs identify the other three. |
| `elapsedCounterNeedsItsDeclaredInterval` | Bytes `[0,100]`, `[0,110]`; positions 1000,1010. ELAPSED with interval 200 km yields `[100,90]`. Otherwise identical candidate with no interval is rejected, with no interval result. |
| `constantAnchorsDoNotDistinguishDueAtFromLastService` | Bytes `[4,76]` twice (1100); positions 1000,1010. DUE_AT yields `[100,90]`; LAST_SERVICE with interval 200 km yields `[300,290]`. Both fit. |
| `equivalentSyntheticHistoriesNeedFieldMeaningEvidence` | Positions 48300,48350. LAST_SERVICE bytes `[162,28]` twice (41500), interval15000, gives `[8200,8150]`. Separate DUE_AT experiment bytes `[220,180]` twice (56500) gives the same list. Do not combine these as one observed field. |

- [x] **Step 2: Run the focused test class and record the expected failure from missing evaluator declarations.** Standard command: `./gradlew -p core test --tests io.github.eternalliquet.bamboobd2.core.research.CounterHypothesesTest --console=plain`. Do not claim a failed download/toolchain start as a meaningful failing test.

- [x] **Step 3: Implement the interfaces and bounded evaluator.** Validate nonempty field key; width exactly 16; explicit unit/category; identical `FieldSpec` across observations; exactly two integers in 0..255; position in 0..1,000,000,000; nonempty observation/candidate lists within the declared limits. For each candidate require scale 1 or 100. A supplied interval must be 1..1,000,000,000 and match the observation's unit/category. ELAPSED and LAST_SERVICE require an interval. All calculations use `Long`; no inferred conversion or floating point tolerance.

Decode the two bytes in declared order, then apply 16-bit two's-complement sign if requested, then multiply by the declared scale. For each adjacent observation, compare decoded delta with negative position delta (REMAINING), positive position delta (ELAPSED), or zero (DUE_AT/LAST_SERVICE). Reject a mismatch with a stable descriptive reason. One observation imposes no delta constraint. Convert surviving decoded values `x` to hypothetical remaining values as follows: REMAINING `x`; ELAPSED `interval-x`; DUE_AT `x-position`; LAST_SERVICE `x+interval-position`. Preserve negative results without declaring them actual overdue state. Do not discard a fit for looking implausible.

If either observation contains `[255,255]`, UNSPECIFIED rejects that candidate with an ambiguity reason, UNAVAILABLE rejects with an unavailable reason, NUMERIC allows ordinary decoding. No zero/sentinel inference beyond this declared synthetic rule. Copy `applicability` unchanged to the report; the function cannot output a supported profile.

- [x] **Step 4: Add the remaining semantic and validation assertions.**

| Test | Exact assertions |
| --- | --- |
| `signBoundaryRejectsOnlyTheContradictedInterpretation` | Bytes `[127,255]`, `[128,0]`; positions1000,1001; BE ELAPSED interval50000. Unsigned fits `[17233,17232]`; signed rejects. This assumes no wrapping in the declared model. |
| `allOnesNeedsAnExplicitSyntheticMeaning` | Single `[255,255]`. REMAINING numeric signed candidate fits `[-1]`; numeric unsigned fits `[65535]`; unspecified and unavailable candidates reject with distinct reasons. |
| `dayAndDistanceCasesRemainSeparate` | Repeat the 100-to-90 countdown with DAYS and positions2000,2010: `[100,90]`. A two-observation experiment mixing DAYS/KILOMETERS throws; a km interval used with a day observation rejects. |
| `invalidObservationMetadataProducesNoResult` | Table-driven `assertThrows(IllegalArgumentException::class.java)` for null unit, null category, null/8/32 width, blank/mismatched field key, changed category, one/three bytes, byte -1/256, empty/17 observations, position -1/1000000001. |
| `invalidCandidatesDoNotInventAContext` | Empty/33 candidates or duplicate/blank IDs throw. Scale0/2, missing required interval, interval0/1000000001, and an OIL interval for a MAIN observation reject. |
| `uniqueFitDoesNotPromoteApplicability` | Reuse the four-candidate distance-change input: exactly one fit, report applicability remains UNKNOWN. Repeat with RELATED_PLATFORM_ONLY and assert it stays that value. |

- [x] **Step 5: Run the focused class and then the established core gate.** Require focused PASS, then `./gradlew -p core check --console=plain` exit0. Record source HEAD, commands, test counts and actual output. In this selected cloud environment the parent has a verified local helper, `python3 /workspace/scratch/run-bamboobd2-gradle.py`, which supplies the existing Temurin JDK21, writable Gradle cache and inherited proxy without repository edits; append the same Gradle task/flags. If it is unavailable, use the standard command and report the concrete blocker. No Android build is necessary for test-source-only changes.

- [x] **Step 6: Inspect the full diff and hand off.** Confirm only the two test-source files plus the parent's authorized research documentation changed; no existing protocol fixture was modified. Confirm the files contain no transport/request construction or external dataset. Report that all test observations are constructed and that no Cayenne read definition was validated. Leave commit/publication decisions to the parent and preserve the existing public-action block.

## Self-review and handoff boundary

The plan covers the research spec's ambiguity, delta discrimination, width/unit/category rejection, all-ones handling and unchanged applicability. Fixed two-byte width and integer scales keep it small; fractional scaling, date epochs, dynamic interval algorithms, saturation/wrap and actual wire frames are explicitly unmodeled. Passing this experiment validates the declared mathematical examples only. Stop if implementation needs production types, a new dependency or a broader codec framework; revise the scope with the parent before expanding it.
