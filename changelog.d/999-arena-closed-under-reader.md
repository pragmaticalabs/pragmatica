### Fixed (2026-09-11 — #999: FFM arena closed under in-flight readers dropped a replication message behind a green check)
- **`OffHeapRingBuffer`'s primitive header accessors had no native-access boundary**, so a concurrent
  `close()` freeing the shared arena under an in-flight reader threw `IllegalStateException: Already
  closed` out of a `long`-returning method with no failure channel. It killed the
  `stream-partition-backfill` thread outright and, on a Netty event loop, was swallowed by
  `RabiaNode.dispatchLoudly`, **dropping a `ReplicateEvents` replication message** while the check
  stayed green.
- The close is **not shutdown-specific**: `StreamPartitionManager.reconcileReshuffle`, scheduled every
  5s by `AetherNode`, releases a materialized ring on confirmed role loss, and `destroyStream` closes
  one from the Management API — so a live cluster could drop replication traffic on any placement
  change. [mechanism: `AetherNode.java:3946` schedules `reconcileReshuffle`; `evaluateReleaseCandidates`
  → `releasePartitionRing` → `completeRelease` → `ring.close()` → `arena.close()`]
- `headOffset`, `tailOffset`, `eventCount`, `applyRetention`, `evictByAge` and `seedHead` now read
  through a guard that reports the empty-ring encoding (`-1`/`-1`/`0`) instead of throwing — the values
  every caller already treats as "this node does not hold the partition", which is true once the ring is
  released, so the racy path converges on the behaviour the non-racy path always had.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OffHeapRingBufferCloseRaceTest.java`]
- Internal callers keep raw, unguarded reads and still abort into the enclosing `guardedAccess`: a
  refusal sentinel there would have persisted a **negative event count**, since `updateHeaderAfterAppend`
  writes `rawEventCount() + 1` and `evictOldest` writes `rawEventCount() - 1`.
  [mechanism: raw/public split in `OffHeapRingBuffer`]
- The genuine race is now reported — a WARN naming stream and partition, plus a
  `closedUnderReaderCount()` counter — where it previously surfaced only as an anonymous "message
  dropped by this handler" line or as nothing at all. The benign late arrival (ring already closed when
  the reader arrives) stays silent, so the two are distinguishable.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OffHeapRingBufferCloseRaceTest.java`]
