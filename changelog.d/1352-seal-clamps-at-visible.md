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
  `PublishOutcomeUnknown`. The clamp belongs to the OWNER's write path (`OffHeapRingBuffer.SealBound.VISIBLE`);
  a replica's `appendRecovered` keeps sealing every evictee (`SealBound.APPENDED`) — what a replica holds is
  already in the owner's log, and dropping it there would lose an acknowledged event from the replica's ring
  and tier at once (the #1234 contract, `StreamPartitionManagerWalTruncateTest.appendRecovered_zeroCap_…`,
  stays pinned). A drop is retention reclamation: the dropped range advances `SegmentIndex`'s contiguous
  sealed watermark as a sealed segment would (`SegmentIndex.markReclaimed`, through `LastSealedOffsetSource`),
  so WAL truncation proceeds past it and a read from a dropped offset is refused with `CursorExpired`
  (reclaimed), never `SealedRangeMissing` (a seal that failed).
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OffHeapRingBufferUnacknowledgedEvictionTest.java`,
  `StreamPartitionManagerUnacknowledgedEvictionTest.java` (the replica-set-change tiered read, the
  prompt await failure, WAL truncation resuming past the drop), `SegmentIndexTest$ReclaimedWithoutSeal`,
  `HonestPublishOutcomeTest$UnacknowledgedEvictionBarrier` (all four writers)]
  [unverified: a drop is recorded in memory only — a restart before the WAL is truncated past it replays
  the dropped record from the WAL as visible, as WAL replay does for every unacknowledged record]
