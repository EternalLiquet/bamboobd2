# Claude Code workflow

Follow `AGENTS.md`, especially the **future diff-before-edit “go” gate**. Inspect and draft a concrete patch before later edits; wait for explicit owner “go.” The 2026-10-13 bootstrap authorization covered documentation and agent scaffolding only.

Sonnet 5 owns planning, integration, and verification. Use the configured Opus 5.5 advisor for substantive plan review before locking a plan, after the same normalized error occurs twice, and before completion. Record actual advice and any evidence-backed disagreement. Advisor timing is model-driven, so these are instructions rather than an enforced scheduler. If a required review is unavailable, disclose that gap; do not claim it occurred.

Delegate a bounded read-only question to `search`, `grep`, or `docs` when useful. Specify scope, output limit, and stop condition. Verify consequential findings yourself. Do not launch all three by default. Their tool allowlists restrict repository edits and shell execution; web research still sends requests externally. Never send private captures, code, identifiers, or secrets in those requests. Subagents may inherit the advisor, so the no-consult instruction is not a hard cost control.

Start research with a falsifiable hypothesis and alternative. Preserve authorized originals outside Git; hash and describe acquisition context and capture loss. Record derived decodes separately. Use offline replay and golden fixtures; test malformed length, truncation, unknown identifiers, duplicate and out-of-order data, timeouts, boundaries, and reconnect where relevant. Property-based tests should assert structural invariants; fuzz parsers offline within resource limits. A parser test cannot verify a guessed signal meaning.

Jev is **not configured** here. Follow `docs/JEV_PROPOSAL.md`; do not invent a Claude setting or claim automatic routing. Finish with diff review, applicable checks, advisor review if available, exact commit, and unresolved uncertainty.

## Design and writing skills

Four project skills live in `.claude/skills/`; sources, revisions and local changes are in `docs/SKILLS.md`. Use one only when the task needs it, never all four by default. Project requirements, safety rules and the agreed POC scope and exit criteria override skill advice. A skill never justifies extra polishing rounds or new test infrastructure.

- `frontend-design`: main visual-design guidance. Build in native Kotlin with Jetpack Compose and Material components, and keep Android navigation, accessibility and platform behavior. Never add HTML, Tailwind, React or a WebView to satisfy a design skill.
- `ui-ux-pro-max`: targeted UX and Compose reference that supports the chosen design instead of redesigning the app. Always pass `--stack jetpack-compose` (its default is web). Don't use `--persist` unless asked.
- `design-taste-frontend` (Taste Skill): only for initial visual exploration or a redesign the owner asks for. Once a direction is chosen, keep it. Its browser checks don't validate native Android screens; don't build a web prototype just to run them.
- `humanizer`: for human-facing prose we write or edit, including docs, PR descriptions and app copy. Cut formulaic contrasts, forced three-part lists, excess dashes and filler. Keep technical meaning, facts, uncertainty and safety information. Invent no anecdotes or claims. Leave code, identifiers, commands, paths and links unchanged, keep UI labels short, and return only the final text unless drafts are requested.

Native UI counts as validated only after running the Android app.
