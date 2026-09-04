### Changed (2026-09-04 — #819: per-PR changelog fragments assembled at release prep)
- **`CHANGELOG.md` is no longer edited by feature pull requests.** Each pull request adds
  `changelog.d/<number>-<slug>.md` holding its entry in the existing format;
  `scripts/changelog-assemble.sh` folds the fragments into the `Unreleased` section at release prep.
  Every rc4 pull request had been conflicting on the one file all of them appended to.
- **A CI check** (`.github/workflows/changelog.yml`, `scripts/changelog-check.sh`) fails a pull request
  that edits `CHANGELOG.md` without the `release-prep` label, or that changes sources without a
  well-formed fragment and without the `no-changelog` label. Documentation-only pull requests are exempt.
