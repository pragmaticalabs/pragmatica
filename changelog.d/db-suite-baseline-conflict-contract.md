### Fixed (2026-09-30 — integration harness: 10-database asserted a first-time baseline that Step 7 makes impossible)
- **`10-database` failed 2 of 3 on every run because its premise never held.** `run-tests.sh` Step 7 deploys
  every cluster-A blueprint before any suite runs, and test-persistence's deploy applies V900 through the
  reactive schema flow, so the datasource is at version 900 before the suite starts and the server's
  `409 Baseline conflict ... up to version 900` is correct. The 06/10 parallelism was not the cause (06 only
  polls for the V900 the Step 7 deploy triggered), so serialising or isolating suites would not have helped.
  The suite now asserts that contract: the first baseline is refused with 409 naming the applied version, the
  repeat gets the identical 409, and `currentVersion` is unchanged. The file is renamed
  `test-schema-baseline-conflict.sh`; CHARTER C3/C6 are rewritten to match.
- **Stated gap:** the first-time baseline path now has no end-to-end coverage; the CHARTER records it as
  `[unverified: ...]` (rc5 follow-up).
  [verified: `aether/tests/integration/test/test-baseline-conflict-suite.sh` (5 stub scenarios against the real
  `_api_call`); the pre-change script fails the 409 scenario exactly as the real runs did, and disabling the
  version check leaves the moved-version scenario green. No cluster or cloud run was made.]
