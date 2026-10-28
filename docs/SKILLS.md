# Project skills

Four Claude Code skills are installed for this repository only, under `.claude/skills/`. When to use each one is set in `CLAUDE.md`. This file records where they came from and what was changed. Installed 2026-10-28 from shallow clones at the revisions below. No upstream installer was run, because each one either installs a wider collection, adds unrelated sub-skills or writes to the user-level `~/.claude/skills/`.

| Skill (folder) | Upstream | Revision | Version | License |
| --- | --- | --- | --- | --- |
| `frontend-design` | [anthropics/skills](https://github.com/anthropics/skills/tree/main/skills/frontend-design), `skills/frontend-design/` | `dbd4588f9e1033efb41dad4bef2f7947c8993d44` | none stated | Apache-2.0 (`LICENSE.txt`) |
| `ui-ux-pro-max` | [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill), `.claude/skills/ui-ux-pro-max/` | `28b3b8dee8c976df4fa3e94aed4e092ad43fcb03` | 2.15.0 (`skill.json`) | MIT |
| `humanizer` | [blader/humanizer](https://github.com/blader/humanizer), `SKILL.md` | `8c307f367c91bd974002f10f2be2965d642c13df` | 3.1.0 | MIT |
| `design-taste-frontend` (Taste Skill) | [Leonxlnx/taste-skill](https://github.com/Leonxlnx/taste-skill), `skills/taste-skill/` | `12af471f627f866411b4bd785b19646d3672784c` | 2.1.5 (plugin manifest) | MIT |

## Local changes

- `ui-ux-pro-max/SKILL.md`: the 11 script paths `${CLAUDE_PLUGIN_ROOT}/.claude/skills/ui-ux-pro-max/scripts/search.py` were replaced with `.claude/skills/ui-ux-pro-max/scripts/search.py`. That variable exists only in plugin installs, and the replacement is the path upstream's own CLI writes for a local install. Commands run from the repository root.
- Left out as upstream development files: `ui-ux-pro-max/scripts/tests/` (they run only inside the upstream repo, and one asserts the plugin path) and Taste's `evals/`.
- Each folder without its own license file got a copy of the upstream repository `LICENSE`.

Everything else is byte-identical to upstream at the listed revision.

## Dependencies and side effects

- `ui-ux-pro-max` scripts need Python 3 and use only the standard library. A query reads the bundled CSV data. `--persist` writes `design-system/` into the project; use it only when asked. Running the scripts creates `__pycache__/` next to them unless `PYTHONDONTWRITEBYTECODE=1` is set.
- `design-taste-frontend`: per its bundled README, `draw.mjs`, `lint.mjs` and `assets.mjs` run on Node 18+, and `inspect.mjs` needs Node 22+ and an installed Chrome, Edge or Chromium. The `scenes.mjs` header states the same Node 22+ and browser requirement. `assets.mjs` downloads images from Openverse, Wikimedia Commons, museum and NASA APIs. `draw.mjs` writes a history file to `~/.taste-skill/` (override with `TASTE_SKILL_HISTORY`) and its output to `.taste-skill/` in the project, which is git-ignored. Its browser checks are not validation of native Android screens.
- `frontend-design` and `humanizer` are instructions only, with no scripts.

## Updating

Clone the upstream at the new revision, replace the folder, re-apply the local changes above, and update the table. Review new or changed scripts before running them.
