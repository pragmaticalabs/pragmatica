### Fixed (2026-10-01 — integration harness: a partition firewall could stay applied after S05's heal)
- **`cloud_heal_partition` waits for the detach and verifies the result.** At S-triple-prime the second minority node's
  firewall delete failed with `resource_in_use`: `remove-from-resource` returns when the detach action is accepted,
  not when `applied_to` has settled, so the firewall stayed applied to the server and the node stayed partitioned
  for the rest of the run (the teardown heal failed the same way). The heal now polls `applied_to` until empty
  (bounded, 60s), deletes with backoff on `resource_in_use`, verifies the firewall is gone rather than trusting
  the exit code, and on final failure names the firewall id and server and leaves the record for the driver's
  proof of zero. The create path CONFIRMS the partition (bounded: `applied_to` must show the server, an unreadable
  describe is not confirmation) or FAILS: a test with no partition in place would pass vacuously.
  [verified: `aether/tests/integration/test/test-partition-heal-detach.sh` (13 stub tests against a fake `hcloud`);
  no wait reddens 2, no verify reddens 1, no resource_in_use retry reddens 2, warn-instead-of-fail reddens 2,
  counting an unreadable describe as applied reddens 2, dropping the no-jq `|| true` reddens 1. No cloud run.]
