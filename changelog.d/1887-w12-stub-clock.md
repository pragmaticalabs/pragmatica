### Fixed (2026-10-07 — #1887: a `$SECONDS` poll deadline is (N-1, N] seconds, not N; W12 of `test-cloud-helpers.sh` raced it)
- **Test infrastructure only.** `read_amount` in the 02w suite measured its retry deadline with the integer `$SECONDS`, so a 1s deadline was
  really somewhere in (0, 1] seconds of wall time and one slow first call could exhaust it: `rc=5 calls=1` instead of `rc=4`, four times in
  CI. The clock is now an overridable `now_s`; W12 drives it by the stub's call sequence (transient answer, then no answer, then the deadline),
  so it asserts the sequence and exactly three calls whatever the runner's speed. Real runs still read `$SECONDS`.
- Measured: with a 0.3s delay injected into each stub call, the old case failed 10 of 25 runs with the CI signature; the new case 0 of 15,
  and 0 of 50 undelayed and 0 of 20 under CPU load. Mutations: a deadline that is always passed, and a forgotten earlier transient answer,
  each redden W12.
- **The same defect is a real-run hazard, not only a stub one.** `$((SECONDS + N))` expires after anywhere in (N-1, N] seconds of wall time. New
  `deadline_in <N>` in `lib/common.sh` is reached no sooner than N seconds (`SECONDS + N + 1`; a budget of 0 stays already-expired). Applied to the
  sub-10s budgets of the real harness: `wait_for_node_removed` (default 8s), `stream_publish_status` (default 10s), the S05 partition hold (5s)
  and the 02w `read_amount` deadline (inline, since the stub tests extract that function by name). Pinned by W14 (real clock, a 1s deadline must
  retry for at least 1s; 547ms with the +1 removed) and D1/D2. The other `SECONDS + N` sites (15s to 900s budgets) lose at most one second of a
  long budget and are left; `SECONDS - since >= window` elapsed checks have the mirror error and are left.
- Two stub tests that extract functions by name needed the new dependencies (`now_s`, `deadline_in`); the first commit of this PR had broken
  `test-entity-create-retry.sh` (`NOW_S: unbound variable`), now fixed and the full `run-stub-suites.sh` is 25 of 25.

