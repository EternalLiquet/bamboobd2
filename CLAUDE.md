# Claude Code workflow

Follow `AGENTS.md`, especially the **future diff-before-edit “go” gate**. Inspect and draft a concrete patch before later edits; wait for explicit owner “go.” The 2026-10-13 bootstrap authorization covered documentation and agent scaffolding only.

Sonnet 5 owns planning, integration, and verification. Use the configured Opus 5.5 advisor for substantive plan review before locking a plan, after the same normalized error occurs twice, and before completion. Record actual advice and any evidence-backed disagreement. Advisor timing is model-driven, so these are instructions rather than an enforced scheduler. If a required review is unavailable, disclose that gap; do not claim it occurred.

Delegate a bounded read-only question to `search`, `grep`, or `docs` when useful. Specify scope, output limit, and stop condition. Verify consequential findings yourself. Do not launch all three by default. Their tool allowlists restrict repository edits and shell execution; web research still sends requests externally. Never send private captures, code, identifiers, or secrets in those requests. Subagents may inherit the advisor, so the no-consult instruction is not a hard cost control.

Start research with a falsifiable hypothesis and alternative. Preserve authorized originals outside Git; hash and describe acquisition context and capture loss. Record derived decodes separately. Use offline replay and golden fixtures; test malformed length, truncation, unknown identifiers, duplicate and out-of-order data, timeouts, boundaries, and reconnect where relevant. Property-based tests should assert structural invariants; fuzz parsers offline within resource limits. A parser test cannot verify a guessed signal meaning.

Jev is **not configured** here. Follow `docs/JEV_PROPOSAL.md`; do not invent a Claude setting or claim automatic routing. Finish with diff review, applicable checks, advisor review if available, exact commit, and unresolved uncertainty.
