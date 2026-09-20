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
- **Heap copies are bounded; the WAL is the holder, so pending seals never fail an EVENTUAL append.**
  Heap copies of pending segments are capped at the node's stream memory budget
  (`STREAM_MAX_MEMORY_BYTES`, default 128 MiB).
  - **Partition with a WAL (the default, crash-durable mode):** past the cap the sealer drops heap
    copies, oldest first, and keeps only the pending range. A retry rebuilds that segment from exactly
    that range of the partition WAL, which is never truncated above the contiguous sealed watermark. It
    never refuses a hand-over. The limit moves to the WAL's disk, where a failed write fail-stops the
    partition loudly (#634-7, #1231). One WARN is logged per spill episode, and `spillCount()` counts
    every dropped copy. A range the WAL cannot supply in full fails loudly with the terminal
    `SegmentError.WalRangeMissing`, and the segment stays pending with an ERROR each retry cycle. The
    sealer never seals a short or gapped segment. [mechanism: `SegmentSealer.admitSpilling` /
    `rebuildFromWal`, `WalRangeReader`; pinned by `StreamPartitionManagerWalTruncateTest`
    `storageOutagePastPendingCap_…`, `SegmentSealerTest$WalRebuildMissingRange`, `WalRangeReaderTest`]
  - **Partition WITHOUT a WAL (non-crash-durable mode: a manager built with no WAL directory, e.g.
    Ember or Forge without a data dir):** the heap copy is the only holder. Past the cap the sealer
    refuses, and the append needing the room fails with the transient
    `StreamError.General.SEALING_BEHIND` (one WARN per refusal episode, counted by `refusalCount()`).
    **This is the one case in which an EVENTUAL append can fail.** Admission resumes as soon as a
    pending seal lands. Operator recovery: restore the storage tier, or run with a WAL directory.
    [mechanism: `SegmentSealer.reserve`; pinned by `SegmentSealerTest$PendingSealCap`]
  - [design intent — unverified: no multi-node run with an injected storage outage]
- **Index before release; deleted streams release their backlog.** The sealer drops a copy only in a
  dependent continuation of the seal promise, and `StorageSegmentSink` resolves that promise after its
  index update, so an evicted offset is always in the sealer or the index. Deleting a stream cancels
  its pending seals and frees their bytes, and a cancelled retry stops (terminal `SEAL_CANCELLED`).
  `EvictionListener.lowestUnsealed(stream, partition)` reports the lowest offset still pending.
  [pinned by `SegmentSealerTest$ReleaseAfterIndex`, `StorageSegmentSinkTest$IndexBeforeResolution`,
  `SegmentSealerTest$StreamDeletion`, `SegmentSealerTest$LowestUnsealed`]
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
