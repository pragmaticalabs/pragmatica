### Fixed (2026-09-13 — #1049: auto-heal re-provisions a replacement it is still waiting on)
- **The leader forgot an in-flight cloud replacement 45s after minting it and minted a duplicate.** The
  in-flight entry expired at `split_timeout × 3` (45s at the default) while a Hetzner JVM replacement takes
  50–63s from mint to membership, so the deficit re-opened, a second node was minted, and the surplus was
  then drained as `OVERPROVISION_PARTITION_HEAL`.
- In-flight replacements are now tracked by what the compute provider reports about the instance, not by
  a timer. The leader keeps the entry while the provider lists the instance as provisioning or running,
  and drops it when the provider reports it stopped or terminated, or no longer lists an instance it has
  already seen. After a drop, the deficit re-opens and re-dispatches after the normal deficit debounce.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/ntt/LeaderReconcilerTest.java — unit level, real reconcile path with a fake provider; no multi-node or cloud run yet]
- A hard per-source ceiling bounds every in-flight replacement, including one whose provider cannot
  answer. It is set by the new `[source.<name>] replacement_ceiling` key (default `10m`) and read from the
  cloud source that backs the replacement. It is refused on non-cloud sources (PF-26) and when it is not
  a positive duration.
  [verified: aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerReplacementStateTest.java — unit level]
- A new leader asks the provider about the replacements it inherits from the prior leader, instead of
  ageing them out. [mechanism: the inherited set carries node ids, and the provider lookup is by the
  node-id tag stamped at create, so no wire or KV change is involved]
- Provider status is polled once per in-flight replacement every `split_timeout`, on the leader only,
  and never from a reconcile pass. A slow provider cannot pile up queries: one is outstanding at a time.
- AWS, GCP and Azure node-id lookups now translate the upper-layer `aether.node-id` key to the
  `aether-node-id` tag they stamp. Before this, a node-id lookup on those providers matched nothing, so
  terminate/restart-by-node also found no instance.
- Operator action: none required. To wait longer for slow boots (for example a container runtime pulling
  an image on a fresh VM), raise `replacement_ceiling` on the cloud source.
