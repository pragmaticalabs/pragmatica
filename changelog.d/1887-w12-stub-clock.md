### Fixed (2026-10-07 — #1887: stub case W12 of `test-cloud-helpers.sh` raced a 1s `$SECONDS` deadline)
- **Test infrastructure only.** `read_amount` in the 02w suite measured its retry deadline with the integer `$SECONDS`, so a 1s deadline was
  really somewhere in (0, 1] seconds of wall time and one slow first call could exhaust it: `rc=5 calls=1` instead of `rc=4`, four times in
  CI. The clock is now an overridable `now_s`; W12 drives it by the stub's call sequence (transient answer, then no answer, then the deadline),
  so it asserts the sequence and exactly three calls whatever the runner's speed. Real runs still read `$SECONDS`.
- Measured: with a 0.3s delay injected into each stub call, the old case failed 10 of 25 runs with the CI signature; the new case 0 of 15,
  and 0 of 50 undelayed and 0 of 20 under CPU load. Mutations: a deadline that is always passed, and a forgotten earlier transient answer,
  each redden W12.
