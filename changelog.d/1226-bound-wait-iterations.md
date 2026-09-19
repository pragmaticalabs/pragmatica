### Fixed (2026-09-18 — #1226: a budget that did not bound wall-clock time, billed in euros)
- **`wait_for` checked its deadline only BETWEEN iterations, so one slow poll could outlive the whole
  budget.** On cloud a failing poll also refreshes the management endpoint, which scans provider VMs
  when the pinned node has died — so an iteration did provider API work exactly when the provider was
  degraded. Measured on a cloud round at `23c9b532c`: a 180s budget ran **1083s**, 720s ran 1597s,
  270s ran 977s, and one suite consumed **19057s (5h17m)** on five paid VMs while starving six suites
  queued behind it. Two more suites later wedged into an external cap.
- **Both the predicate and the endpoint refresh are now bounded by `deadline - SECONDS`**, recomputed
  each iteration — the same budget already exported as `WAIT_FOR_REMAINING`, which existed for
  predicates to honour and nothing enforced.
- **The bound had to preserve state that a subshell would have eaten.** `timeout`/`bash -c` cannot be
  used: predicates are shell functions from the sourced harness libs, and a fresh interpreter does not
  have them. Forking THIS interpreter keeps them, but a killed child cannot hand back its own
  assignments — so `WAIT_FOR_VALUE`, the last observed value and the read-failure counts now cross the
  fork through files the parent reads after `wait` returns. Without that, a timed-out wait would have
  reported `<none: no poll ran>` instead of the value it actually saw: a silent downgrade of the one
  diagnostic an operator reads during an outage. Verified both directions — `last observed value: 42
  (0 of 4 read(s) failed)` on a clean reader, and `(2 of 4 read(s) failed)` on an intermittent one.
- `_refresh_mgmt_entry_point` exports the rotated endpoint, which is its entire purpose, so only its
  inner `_resolve_live_endpoint` is forked; the exports happen in the caller's own shell. Cloud
  endpoint rotation behaves exactly as before, bounded.
- A killed poll exits with a signal status, which falls through the same branch as an ordinary false
  predicate rather than the `2|127` "buggy predicate" path.
- **Known bound, not fixed:** killing a forked poll does not kill ITS children, so a predicate that
  calls an untimed blocking command directly can leak that process for its own duration. Every real
  predicate's slow primitive already self-bounds (`curl -m 2`, hcloud via `_run_with_timeout 10`), so
  real orphans die within seconds. A process-group kill was ruled out empirically — backgrounded jobs
  here share the script's own pgid, so it would kill the test script — and `setsid` is Linux-only.

- **`wait "$pid"` followed by `rc=$?` aborts under `set -e`.** Every suite sets `set -euo pipefail`,
  so a non-zero child killed the shell before the status was captured — in the one function whose
  whole purpose is capturing it. Now captured in the condition (`rc=0; wait "$pid" || rc=$?`).
- Pre-existing and reachable only on a box with neither `timeout` nor `gtimeout`, so it has never
  fired here or on CI. Found while reviewing #1226 and fixed rather than recorded as folklore: the
  failure it would produce — a suite vanishing mid-run with no message — is expensive to diagnose
  and cheap to prevent. [verified: forcing the fallback with both binaries shadowed, a child exiting
  7 now returns 7 instead of aborting the shell]
