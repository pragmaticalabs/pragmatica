### Changed (2026-11-02 — #2558: build-and-test runs its module tests as parallel group jobs)
- **build-and-test's length came from a dependency chain whose links include their own tests.** In run 41351583462,
  `aether/node` started 25 minutes into a 45-minute reactor. It was waiting for the tests of stream, invoke and
  deployment. The tests now run in four `test-group-N` jobs, listed in `tools/ci-test-groups.txt` and balanced by
  measured test time. Each job installs its modules' upstream with `-DskipTests`, then tests only its own modules.
  The format/lint gate and the static pins run beside them in `static-gates`.
- `build-and-test` remains the required check. It is now an aggregate: it fails unless `static-gates` and every
  group passed, and it fails a grouped module that wrote no report.
  [mechanism: `needs` + `if: always()`, `tools/ci-test-groups.py verify-reports`]
- `tools/ci-test-groups.py check` fails a module with tests that no job owns, one owned twice, and a matrix that
  differs from the groups file. [verified: tools/test_ci_test_groups.py]
- `dead-surface-gate` now also refuses a corpus module whose `target/classes` exists but holds no class file.
