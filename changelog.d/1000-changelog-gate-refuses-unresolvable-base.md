### Fixed (2026-09-11 — #1000: the changelog gate cannot pass without having examined anything)
- **`scripts/changelog-check.sh` exited 0 when its base ref did not resolve.** `git diff` exits 128 on
  an unresolvable range, but the call sat inside a process substitution, whose exit status
  `set -euo pipefail` does not check — `pipefail` governs pipelines, not `< <(...)`. The 128 was
  discarded, the changed-path array came back empty, and the empty-diff branch reported success, so
  the fragment gate could pass **having examined nothing** while printing something indistinguishable
  from a genuinely clean result. The workflow interpolates the base as
  `origin/${{ github.base_ref }}`, so a renamed base branch, an unfetched ref or a fork edge reached
  it. The script now resolves the base with `git rev-parse --verify` before inferring anything from an
  empty diff, and reads each `git diff` exit status through a temp file rather than a process
  substitution.
- **Exit codes now distinguish the two refusals:** 2 means the gate could not look, 1 means it looked
  and refused. Conflating them is what let the defect read as a pass.
- **A pass now reports what it examined** — the changed-path count alongside `needs_fragment`, so a
  zero is measured rather than implied. Same precedent as #740.
- `script-gate` gains `ChangelogCheckTest`, which runs the real script against a real git repository
  with no remote, so `origin/main` is unresolvable exactly as in the reported condition.
