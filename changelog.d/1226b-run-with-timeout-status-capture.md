### Fixed (2026-09-18 — `_run_with_timeout` could not return the status it exists to return)
- **`wait "$pid"` followed by `rc=$?` aborts under `set -e`.** Every suite sets `set -euo pipefail`,
  so a non-zero child killed the shell before the status was captured — in the one function whose
  whole purpose is capturing it. Now captured in the condition (`rc=0; wait "$pid" || rc=$?`).
- Pre-existing and reachable only on a box with neither `timeout` nor `gtimeout`, so it has never
  fired here or on CI. Found while reviewing #1226 and fixed rather than recorded as folklore: the
  failure it would produce — a suite vanishing mid-run with no message — is expensive to diagnose
  and cheap to prevent. [verified: forcing the fallback with both binaries shadowed, a child exiting
  7 now returns 7 instead of aborting the shell]
