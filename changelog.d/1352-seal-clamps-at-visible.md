### Fixed (2026-09-20 — #1352: DROP_OLDEST eviction sealed events past the visible position)

- **An event the owner never acknowledged could reach the durable tier.** `OffHeapRingBuffer`'s
  eviction hand-over read `[tail, tail + count)` raw and consulted neither the visible nor the durable
  position, so with min-sync 2 and a peer that had not acknowledged, the third publish into a ring of
  capacity 2 handed offset 0 to the segment sealer with `visible = -1`. After a replica-set change made
  later offsets visible, a consumer read from 0 fell through to the tier and was served an event no
  consumer was ever allowed to see; meanwhile its publisher waited out the 5 s replication timeout and
  was told the outcome was unknown for an event that was definitively not in the log.
- **The hand-over is now clamped at the visible position.** Only `[tail, visible]` is sealed; evictees
  above it are dropped — reclaimed without the hand-over, counted in
  `StreamPartitionManager.unacknowledgedEvictionsSinceBoot()`, WARNed — and every pending
  `awaitReplication` for a dropped offset fails at once with the new `StreamError.UnacknowledgedEvicted`,
  which every writer passes through as a definite failure rather than wrapping it as
  `PublishOutcomeUnknown`. On a replica the visible position is its own durable prefix, so the one clamp
  serves both arms. A read from a dropped offset is refused with `CursorExpired`.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OffHeapRingBufferUnacknowledgedEvictionTest.java`,
  `StreamPartitionManagerUnacknowledgedEvictionTest.java` (the replica-set-change tiered read, the
  prompt await failure), `HonestPublishOutcomeTest$UnacknowledgedEvictionBarrier` (all four writers)]
  [unverified: a dropped offset leaves a permanent hole in `SegmentIndex`'s contiguous sealed
  watermark, so that partition's WAL is not truncated again until restart — `SegmentIndex` is unchanged
  pending a ruling]
