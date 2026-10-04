### Fixed (2026-10-04 — #932, #903: node stop left the stream threads and the shared resource scope running)
- **#932: `AetherNode.stop()` never closed the stream replica-set controller or the backfill executor.**
  Each node owns one `replica-set-controller` thread and one `stream-partition-backfill` thread, both
  daemon, neither on the stop path: nine of each survived a nine-node stop. `stop()` now runs a
  shutdown that closes the controller and then the backfill executor (the controller's reconcile is
  what submits to it), after the stream partition manager closes.
- **#903: the shared (plain-overload, unattributed) resource scope had no closer.** No slice unload
  can release it, because a caller with no slice identity has no last consumer. New
  `SpiResourceProvider.closeShared()` detaches and closes exactly that scope, and `NodeStopSequence`
  calls it after the slice invoker stops and before storage and the cluster node. A failure is logged
  and skipped past, so it cannot leave the node half-stopped. `releaseAll` still cannot reach the
  shared scope.
- Pinned by `AetherNodeStopThreadsTest` (threads counted by name before boot, while up, after stop),
  `AetherNodeSharedScopeShutdownTest` (the resource's own close count through a booted node),
  `NodeStopSequenceTest` (order and failure handling) and `SpiResourceProviderLifecycleTest`.
