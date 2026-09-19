### Fixed (2026-09-19 — #1234: segment sealing discarded its promise, the ring evicted before persistence, and WAL truncation could pass a failed segment)
- **A failed seal was silent and could become permanent loss.** `SegmentSealer` dropped the promise
  `sink.seal` returned, the ring advanced its sealed watermark and reclaimed the events at hand-over,
  and `SegmentIndex.lastSealedOffset` was the MAXIMUM sealed `endOffset` — so a later successful seal
  licensed WAL truncation and recovery seeding past a segment that had failed, leaving that range in
  no ring, no segment and no WAL.
- **Seal before reclaim.** `EvictionListener.onEviction` now returns `Promise<Unit>`, and the ring
  reclaims only events whose seal SUCCEEDED; unsealed events stay readable in the ring. Sealing is
  asynchronous (the appending thread never waits on storage), one seal is in flight per ring, and a
  ring seals an eighth of its retained events ahead of what it must reclaim, so an asynchronous sink
  normally keeps sealed room ahead of the appends. [mechanism: `OffHeapRingBuffer.notifyAndEvict` →
  `requestSeal` / `completeSeal`; pinned by `SegmentSealerTest$SealFailure`]
- **Retry and backpressure.** `SegmentSealer` retries each seal (5 attempts, exponential backoff from
  100 ms); when that budget is spent the ring logs a WARN naming the retained range, counts it
  (`OffHeapRingBuffer.sealFailureCount()`), and hands the same events over again on its next eviction
  pass. An append whose room is held by unsealed events is refused with the new transient
  `StreamError.General.SEALING_BEHIND` (counted by `sealBackpressureCount()`, one WARN per refusal
  episode) — for EVENTUAL streams too, which previously never refused an append. Operator recovery:
  restore the storage tier; the next eviction pass seals the retained range and admission resumes.
  [design intent — unverified: no multi-node run with an injected storage failure]
- **The sealed watermark is contiguous.** `SegmentIndex.lastSealedOffset` is now the highest offset
  at or below which every offset is sealed, is never lowered by retention removing a segment, and is
  re-anchored at the lowest surviving ref after a restart (a reclaimed prefix and a never-sealed
  prefix below every surviving ref are indistinguishable there). WAL truncation and recovery seeding
  therefore never pass a hole, and a restart replays the failed range from the WAL.
  [mechanism: `SegmentIndex.contiguousEnd`; pinned by `StreamPartitionManagerWalTruncateTest`,
  `StreamPartitionManagerRecoveryTest`, `SegmentIndexTest$ContiguousSealedWatermark`]
- **The cold reader surfaces a hole.** `TieredStreamReader.read` never reads across a gap between
  sealed segments, fails a read starting inside one with `SegmentError.SealedRangeMissing`, and the
  cold-read fallback in `PartitionedStreamAccess` returns the ring's `CursorExpired` for an offset
  held by neither tier instead of `[]`, which stalled the consumer forever.
  [mechanism: `TieredReader.read` / `PartitionedStreamAccess.combineWithBufferEvents`; pinned by
  `TieredStreamReaderTest$HoleInSealedRange`, `SegmentFallbackTest$UnsealedHole`]
