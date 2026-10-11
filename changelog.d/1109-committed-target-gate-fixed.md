### Fixed (2026-10-11 — #1109: the leader issued LOAD from its mirror before the target put had committed)
- **The leader allocated from `blueprints`, a mirror written when the target put is only queued.** `handleAppBlueprintChange` writes the mirror
  and queues the `SliceTargetKey` put; the next `reconcile()` tick allocated from the mirror and issued LOAD. Pending batches are ordered by
  hash, not by submission, so that LOAD could commit ahead of the put it depends on on every replica, the leader included. Measured on the
  rc4 tip by `ClusterDeploymentStateActiveTest$LeaderLoadWaitsForCommittedTarget` (red before this change). The premise of the ticket (a
  side channel not ordered behind the put) is not what happens: LOAD is an ordinary consensus put, and a follower applies strictly by slot
  (`RabiaEngineApplyOrderTest`, `KVStoreCommittedNotificationTest` pin that half and were green).
- **`SliceAllocationEngine.tryAllocate` now issues LOAD only when the leader's applied store holds a `SliceTarget` (or a `VersionRouting`
  entry) naming the version** (`CommittedSliceTarget.permits`, the predicate ACTIVATE, the node gate and the orphan sweep already use). The check
  precedes the `sliceStates` bookkeeping: a refused attempt leaves no trace, so the target put's own notification and every later tick re-attempt it.
  `[verified: ClusterDeploymentStateActiveTest$LeaderLoadWaitsForCommittedTarget]`
- **Guarantee per operation.**
  - *LOAD (leader):* issued only if, at the moment of the check, the leader's applied store names the version. Earned by the check against the
    leader's own `KVStore`. Not guaranteed: that the target is unchanged when the LOAD commits (check-then-act; a rollback in between is still
    refused by the node-side gate, #1068), and not fenced against a deposed leader's plain put (H2, filed for rc5 with an exact-value guard).
  - *LOAD/ACTIVATE (node):* unchanged. A node starts a slice only when its own applied store names the version, otherwise the start is parked and
    re-driven when the target arrives (#1083).
  - *ACTIVATE (leader), UNLOAD, DEACTIVATE, DRAIN-by-ping:* unchanged.
  - Never claimed: exactly-once, or that a follower "has seen" a put on any basis other than slot order.
