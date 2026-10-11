### Fixed (2026-10-08 — #2029: the integration lint baseline broke on any edit above a waived line, and CI never ran it)
- **`aether/tests/integration/lint-baseline.txt` waived findings by `file:LINE`.** An edit that shifted a waived line made the waiver
  stale and the same finding "new", `lint-tests.sh` exited 1, and `run-tests.sh` (which runs it under `set -euo pipefail`) aborted
  every integration run before provisioning anything. Waivers are now keyed by rule, file and the normalised CONTENT of the flagged
  line; inserting lines above a waived line keeps lint green, editing the waived line or adding a violation does not. New findings
  still print `file:line`. The baseline was migrated (43 entries, same 43 findings).
- **CI now runs `lint-tests.sh`** (in the `stub-suites` job, which needs no JDK), so the PR that introduces a finding fails, not the
  next integration run. `test-lint-baseline-by-content.sh` pins the line-shift, new-violation, edited-line and duplicate-line cases.
