### Changed (2026-09-19 — #1263: the three stream write paths are one write operation)
- **`DefaultStreamPublisher` (slice `StreamPublisher`), `PartitionedStreamAccess` (`StreamAccess.publish`) and
  `StreamWriteRouter` (management publish) each carried their own copy** of owner resolution, forwarding,
  the retry wrapper, the local append and the replication barrier. #1230 had to apply the same fix in all
  three. `StreamWriteRouter` is now the only implementation; the two typed publishers encode the event,
  pick its partition and delegate.
  [mechanism: all three entry points delegate to `StreamWriteRouter.publish`; pinned at unit level by `StreamWritePathContractTest`, one contract run against each entry point]
- **The min-sync barrier is read live** from the stream's committed `min-sync-replicas` on every publish.
  Before, the typed publishers froze the value at construction. `DefaultStreamPublisher.streamPublisher`
  and `PartitionedStreamAccess.streamAccess` no longer take a `minSyncReplicas` argument (pre-GA API change).
- **An unknown self never forwards** on any path (the rule `DefaultStreamPublisher` always applied).
  `PartitionedStreamAccess`'s no-self sentinel previously compared unequal to every owner. The factories
  that use the sentinel wire no forward client, so this changes no observable behaviour today.
  [mechanism: `StreamWriteRouter.isRemote` over `Option<NodeId>`]
