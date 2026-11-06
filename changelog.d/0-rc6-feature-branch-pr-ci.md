### Changed (2026-11-05 — rc6: pull requests into feature/** integration branches run the full CI gate)
- **CI: pull requests into `feature/**` integration branches now run the full gate.** `ci.yml`, `changelog.yml` and
  `s3-tests.yml` add `feature/**` to `pull_request` and `merge_group`. Until now a PR into `feature/rc6-scaling` ran no
  build-and-test; the rc6 tip was tested only after the merge, by the push run. `ci.yml`'s duplicate-push guard
  (`push-dedupe`) lists `feature/*` among the bases whose `pull_request` run gates, so a branch with an open PR into a
  feature branch is not built twice. Part of #2703.
