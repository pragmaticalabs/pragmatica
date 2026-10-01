### Fixed (2026-10-01 — #1805: a materialize refused as paced was never retried, leaving the partition held but unmaterialized)
- **A partition whose materialize was refused by `reshuffle_concurrency` could stay held-but-not-materialized forever**,
  refusing every write with `ForwardRefused "partition N not held"` (cloud run 1, `02w-entity-crash`: 180 log lines over
  12 minutes). The refusal lived only in a transient queue — a stale-purge (the role flapped through NONE for one tick, the
  #1734 re-mint shape) or a failed drain attempt removed it, and every producer of a new attempt is edge-triggered (the
  registry-add edge behind `onBecameReplica`, an owner append), so nothing re-drove it. The `ReshufflePaced` documentation
  said it would be re-driven; it was not.
  Every paced refusal is now also recorded on a level-triggered ledger; each reconcile tick re-queues every ledger
  partition that is still held and unmaterialized, through the same `materializePartition` path, so the
  `reshuffle_concurrency` bound and the off-heap budget-AND are unchanged. An entry survives a role flap and is dropped
  only when the ring exists, the stream is gone, or the role stayed NONE for ~60 s. A partition held but unmaterialized for
  ~60 s logs a WARN (repeating every ~60 s). A queued partition that became OWNER no longer waits for a free permit
  (an owner materialize is never paced).
  [verified: `PacedMaterializeRetryTest` — role flap, owner-without-permit, WARN threshold, burst bound, lost-ownership drop; each red with its hunk reverted]
  [unverified: which of the loss paths fired in the cloud incident — its node logs were not retained; the role-flap path is reproduced, the incident's re-mint of partition 6 is consistent with it]
