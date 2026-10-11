### Fixed (2026-10-01 — #1805: a materialize refused as paced was never retried, leaving the partition held but unmaterialized)
- **A partition whose materialize was refused by `reshuffle_concurrency` could stay held-but-not-materialized forever**,
  refusing every write with `ForwardRefused "partition N not held"` (cloud run 1, `02w-entity-crash`: 180 log lines over
  12 minutes). The refusal lived only in a transient queue — a stale-purge (the role flapped through NONE for one tick, the
  #1734 re-mint shape) or a failed drain attempt removed it, and every producer of a new attempt is edge-triggered (the
  registry-add edge behind `onBecameReplica`, an owner append), so nothing re-drove it. The `ReshufflePaced` documentation
  said it would be re-driven; it was not.
  The re-drive is now level-triggered: every reconcile tick re-queues EVERY partition this node holds (role OWNER or
  REPLICA) that has no ring, through the same `materializePartition` path, so the `reshuffle_concurrency` bound and the
  off-heap budget-AND are unchanged and no memory of a past refusal is needed (a NONE stretch of any length is covered).
  A partition held but unmaterialized for ~60 s logs a WARN (repeating every ~60 s). A queued partition that became OWNER
  no longer waits for a free permit (an owner materialize is never paced); that can transiently exceed
  `reshuffle_concurrency` when an owner-materialized ring turns REPLICA (the same class exists at base whenever a permit
  is free; the off-heap budget is still enforced).
  [verified: `PacedMaterializeRetryTest` — role flap, long NONE stretch, owner-without-permit, WARN threshold, burst bound, lost-ownership drop; each red with its hunk reverted]
  [mechanism: the core-0 log of cloud run 1 (`02w`, 8,930 lines) shows partition 6 paced at 17:57:31 with no slot-preemption WARN and no budget WARN, and base drains the no-loss shape within a tick (v1809 probe p3), so the queue entry was lost; the two loss paths are a role-NONE purge and a DEBUG-only drain build failure, both re-driven now]
  [unverified: which of those two fired]
