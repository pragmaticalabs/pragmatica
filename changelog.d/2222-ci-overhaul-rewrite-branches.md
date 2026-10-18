### Changed (2026-10-18 — CI runs on overhaul and rewrite integration branches, #2222)
- **Pull requests into `overhaul/**` and `rewrite/**`, and pushes to them, now run the full CI workflow.** Before, those long-lived integration branches merged with no `build-and-test`, `forge-tests` or `fragment` signal.
