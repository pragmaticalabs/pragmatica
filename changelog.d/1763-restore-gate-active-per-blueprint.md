### Fixed (2026-09-30 — integration harness: restore_cluster_baseline passed with a blueprint that had no ACTIVE instance)
- **`restore_cluster_baseline` now requires an ACTIVE instance for every deployed slice artifact.** At S-prime it
  passed while test-echo was 5 x UNLOADING, and 02-chaos then failed 90s later with a misleading "no ACTIVE
  owner". A bounded wait (60s x TIMEOUT_SCALE) covers a legitimate rolling update; on a miss it fails naming each
  starved artifact with every instance's state, so the cause is visible at the restore. Keyed per
  `group:artifact` (an old version may drain while the new one is ACTIVE); an unreadable `/api/v1/slices` fails.
  [verified: `aether/tests/integration/test/test-restore-slices-gate.sh` (9 stub tests on the real functions);
  removing the per-artifact check reddens 3, removing the call 1, removing the bounded wait 1. No cloud run.]
