### Fixed (2026-10-17 — #2213 guard refused the integration harness's own re-bootstrap and left stale registry entries)
- **The cloud integration harness reaped a cluster's VMs and then re-bootstrapped (or later re-launched) the same name,
  which the #2213 existing-cluster guard now refuses.** S20's full-drain recovery failed with "Cluster 'test-b' already
  exists: the cluster registry already holds it" in both runtimes, which quarantined 02-chaos and skipped 02y/02w/02s;
  the end-of-run teardown reaped VMs but never unregistered, so stale entries halted the next launch.
- `_cloud_full_drain_recover` now runs `aether cluster remove <name>` (a missing entry is tolerated) and moves
  `~/.aether/clusters/<name>` aside to `<name>.reaped-<UTC stamp>` (never deleted) after the reap and before the
  bootstrap; nothing happens on a preflight refusal (rc 2) or a fatal reap (rc 1). The cloud teardown does the same for
  each cluster it reaps through `_cloud_teardown_reap_cluster`. No product change; the guard has no `--force` by design.
  `[verified: aether/tests/integration/test/test-chaos-harness.sh K6-K12]`
