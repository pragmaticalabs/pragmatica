### Fixed (2026-09-13 — #1049: auto-heal re-provisions a replacement it is still waiting on)
- **The leader forgot an in-flight cloud replacement 45s after minting it and minted a duplicate.** The
  in-flight entry expired at `split_timeout × 3` (45s at the default) while a Hetzner JVM replacement takes
  50–63s from mint to membership, so the deficit re-opened, a second node was minted, and the surplus was
  then drained as `OVERPROVISION_PARTITION_HEAL`.
- In-flight replacements are now tracked by what the compute provider reports about the instance, not by
  a timer. The leader keeps the entry while the provider lists the instance as provisioning or running. It
  drops the entry when the provider reports the instance stopped or terminated, when the provider no
  longer lists an instance it has already listed, or when the provider has still never listed it four
  `split_timeout` intervals after dispatch (one minute at the default). That first-listing grace absorbs a
  provider listing that lags creation without leaving the cluster under capacity until the ceiling. After
  a drop, the deficit re-opens and re-dispatches after the normal deficit debounce.
  [design intent — unverified: unit-tested on the real reconcile path with a fake provider in aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/ntt/LeaderReconcilerTest.java; no multi-node or cloud run yet]
- A hard per-source ceiling bounds every in-flight replacement, including one whose provider cannot
  answer. It is set by the new `[source.<name>] replacement_ceiling` key (default `10m`) and read from the
  cloud source that backs the replacement. It is refused on non-cloud sources (PF-26) and when it is not
  a positive duration.
  [design intent — unverified: unit-tested in aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerReplacementStateTest.java and the aether-config parser, validator and diff tests]
- A new leader asks the provider about the replacements it inherits from the prior leader, instead of
  ageing them out. Their ceiling and first-listing grace keep running from the mint time each
  replacement's node id carries (the timestamp of its ULID), so a leader change does not restart them: a
  replacement minted longer ago than the ceiling is re-dispatched one debounce after the new leader's first
  pass. An id without a ULID (a configured `<prefix>-<ordinal>` id) restarts both at inheritance, and a
  mint time in the future counts as now. A new leader whose clock runs ahead of the prior leader's ends the
  grace and ceiling early by that skew.
  [mechanism: the inherited set carries node ids, a minted node id carries its mint time, and the provider lookup is by the node-id tag stamped at create, so no wire or KV change is involved]
- Provider status is polled once per in-flight replacement every `split_timeout`, on the leader only,
  and never from a reconcile pass. A slow provider cannot pile up queries: one is outstanding at a time.
- **AWS, GCP and Azure node-id lookups matched nothing, and fixing them is part of this fix.** Those
  providers stamp the `aether-node-id` tag but passed the upper-layer `aether.node-id` key through
  untranslated. Without the translation, every in-flight replacement on those clouds would read as deleted
  and be re-dispatched, which is the duplicate-mint storm this entry fixes. The same lookup backs
  terminate and restart by node, which also found no instance on those clouds before.
  [design intent — unverified: each provider's filter builder is pinned by a unit test in its `*ComputeProviderTest`; no call against a live cloud API]
- Operator action: none required. To wait longer for slow boots (for example a container runtime pulling
  an image on a fresh VM), raise `replacement_ceiling` on the cloud source.
