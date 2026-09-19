### Fixed (2026-09-19 — #1234: segment sealing discarded its promise, and WAL truncation could pass a failed segment)
- **A failed seal was silent and could become permanent loss.** `SegmentSealer` dropped the promise
  `sink.seal` returned, and `SegmentIndex.lastSealedOffset` was the MAXIMUM sealed `endOffset`. A later
  successful seal therefore allowed WAL truncation and recovery seeding to pass a segment that had
  failed, leaving that range in no ring, no segment and no WAL.
- **Space reclamation stays immediate; the sealer owns durability.** The ring still reclaims evicted
  events at once. `EvictionListener.onEviction` now returns `Result<Unit>`: success means the listener
  has taken the events. `SegmentSealer` keeps a heap copy of each pending segment, seals one segment at
  a time per partition in offset order, and retries a failed seal with exponential backoff (core
  `Retry`). Every failure logs a WARN and increments `sealFailureCount()`. Each exhausted cycle of 10
  attempts logs an ERROR, and a new cycle starts 30 s later. A pending segment is never dropped. While
  a seal is pending, the partition WAL holds its offsets.
  [mechanism: `SegmentSealer.sealWithRetry` / `PendingSeals`; pinned by
  `SegmentSealerTest$OrderedSealing`, `$RetryFromRetainedCopy`, `$SlowSinkUnderCap`]
- **Appends are refused only past a bounded backlog.** Pending copies are capped at the node's stream
  memory budget (`STREAM_MAX_MEMORY_BYTES`, default 128 MiB). Once that cap is reached, the sealer
  refuses further segments and the append that needed the room fails with the transient
  `StreamError.General.SEALING_BEHIND` (one WARN per refusal episode, counted by `refusalCount()`). The
  ring keeps those events, and admission resumes as soon as a pending seal lands. A slow sink under
  the cap never refuses an EVENTUAL append. Operator recovery: restore the storage tier, and the queued
  seals drain in order. [mechanism: `SegmentSealer.reserve`; pinned by
  `SegmentSealerTest$PendingSealCap`] [design intent — unverified: no multi-node run with an injected
  storage outage]
- **The sealed watermark is contiguous.** `SegmentIndex.lastSealedOffset` is now the highest offset at
  or below which every offset is sealed. Retention removing a segment never lowers it. After a
  restart it is re-anchored at the lowest surviving ref. WAL truncation and recovery seeding therefore
  never pass a hole, and a restart while sealing is failing replays the pending range from the WAL.
  `StorageSegmentSink` updates the index before its promise succeeds, so the sealer releases a copy
  only once the index holds the segment. [mechanism: `SegmentIndex.contiguousEnd`; pinned by
  `StreamPartitionManagerWalTruncateTest`, `StreamPartitionManagerRecoveryTest`,
  `SegmentIndexTest$ContiguousSealedWatermark`]
- **Cold reads classify an unreadable offset instead of returning `[]` or skipping it.** An evicted
  offset whose seal is pending fails with the transient `SegmentError.SealInFlight`: back off and
  re-read. An offset above the contiguous watermark that no segment holds, with a later offset sealed,
  fails with the terminal `SegmentError.SealedRangeMissing`, which should surface to the operator. The
  recovery is a partition recovery that replays the range from the WAL. An offset at or below the
  watermark that retention reclaimed fails with `CursorExpired`, naming the next sealed offset.
  [mechanism: `PartitionedStreamAccess.readEvicted` / `TieredReader.unheld`; pinned by
  `SegmentFallbackTest$UnsealedHole`, `TieredStreamReaderTest$HoleInSealedRange`]
