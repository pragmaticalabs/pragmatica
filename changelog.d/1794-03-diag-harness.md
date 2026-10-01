### Fixed (2026-10-01 — #1790: 03 Scale_down diagnostics lost their evidence)
- **The suite-end capture deleted the load generator's failure bodies.** Its stale-clear removed every `*.log` in the suite
  directory, including `load-failures-<test>.log` written just before it; now only node logs and an earlier run's bodies go.
  A request that got no answer (status 000) inherited the previous request's body because the scratch file was reused: it is
  truncated per tick, and curl is bounded (`--connect-timeout 2 --max-time 5`).
- **The opt-in victim override is applied once per value**, so the load's re-resolve can leave a victim that halted, and
  `scale_load_retarget_to_victim` prefers a removed voter that hosts the load's slice (the current owner first), logging
  "victim: yes, hosts slice: yes/no" (#1790's shape). The step line records whether the target hosts the slice.
- **The logs of every voter a scale-down removes are captured at demotion**, while it still runs (it halts seconds later and
  its log was never available). Bounded, once per test, armed only in run-tests.sh, unable to fail a test.
- **The Seed marker PUT records its response body** and retries once on 503 only (a 500 stays visible); the seed waits for the
  membership to settle (installed == target voters, member count == voter count). The false "7->5 removes the two CTM nodes"
  premise comment is corrected.
- **Run-level warnings reach the summaries.** `log_run_warning` records a warning in a per-run file that `print_summary`, the
  end-of-run report and each suite's entry in `test-results.json` (`warnings`, `warning_texts`) carry; the seed's
  membership-not-settled warning uses it. The demotion capture cannot add a FAIL, closes fd 7, kills an ssh that ignores
  SIGTERM (`timeout -k`) and is reaped at the end of its test. `--max-time 5` makes the load's error-rate stricter than
  earlier runs (a slow answer now counts as a 000), so rates are not comparable across that change.
  [verified: `aether/tests/integration/test/test-load-bodies-and-vm-capture.sh` B1, B2, D1-D9, RW1-RW4 (test-cloud-helpers) and `test-scale-down-victims.sh`
  V6-V11, P1-P3, M1-M4; each pin reddens with its production hunk reverted. No cloud run.]
