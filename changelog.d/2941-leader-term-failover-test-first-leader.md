### Fixed (2026-11-09 — #2941: `LeaderTermFailoverTest` read the first leader's activation snapshot after its first reconcile pass)
- **Test timing assumption, not a product change.** The first leader of a fresh cluster runs a `CONFIG_CHANGE` reconcile
  pass about one debounce after activation (the bootstrap seeds `ClusterConfigKey.CURRENT`), not the quiesce-delayed
  activation pass the test assumed, so a single read under load landed after it. The test now samples the first leader
  every 5 ms until its first pass and reports the pre-latch only when observed. No term assertion failed: #1527 and #1797
  are not recurring.
