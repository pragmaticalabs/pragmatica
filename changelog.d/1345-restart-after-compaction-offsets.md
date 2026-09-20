### Fixed (2026-09-20 — #1345: restart after WAL compaction but before the metadata snapshot silently reassigned offsets)
- **A restart in the snapshot window renumbered survivors.** Segment refs reach disk only through
  the streams metadata snapshot (every 100 mutations or 30 s), while `truncateWalsToSealed` ran off
  the in-memory `SegmentIndex` and `PartitionWal` compacted once the file passed 8 MiB. A restart
  between the compaction and the snapshot lost the refs, rebuilt a lower watermark, seeded the ring
  below the compaction point and appended the survivors at fresh offsets: measured watermark 195,
  then `head=3 tail=0 count=4` with offset 0 carrying event 196's payload. No error, no log line.
- **Truncation is bounded by the watermark on disk.** `StreamPartitionManager` now takes a
  `DurableSealedOffsetSource` — the sealed watermarks a restart would REBUILD — and the truncation
  tick uses it instead of the live index. `AetherNode` supplies
  `DurableSealedOffsetSource.fromLatestSnapshot(streams.snapshotManager())`: the latest metadata
  snapshot on disk, parsed the way boot parses it, read once per tick. No snapshot yet means nothing
  is truncated. Truncation therefore lags the snapshot cadence by at most 30 s; the WAL keeps that
  much more. **Reclamation is thereby coupled to the snapshot:** while the streams snapshot cannot be
  written or read, nothing is reclaimed and the WAL grows, bounded by the disk. The tick makes that
  visible — from the second consecutive tick in which a partition's on-disk bound sits below its live
  watermark without moving it WARNs (then every 10 ticks) with the partitions and their WAL bytes, and
  `walReclamationHeldBackTicks()` counts the consecutive held-back ticks.
  [verified: `StreamPartitionManagerRestartAfterCompactionTest
  restartAfterCompaction_refsNotYetSnapshotted_recoversOriginalOffsets`; the wiring by
  `WalTruncationDurableBoundBootTest` on a booted node — the WAL does not shrink before the
  snapshot and does after it; both red when the live index is handed back]
  `[unverified: power loss — `DefaultSnapshotManager` writes the snapshot without fsync, so "on
  disk" here means process-crash-durable]`
- **A WAL that starts above the rebuilt watermark keeps its stored offsets; a hole inside the tail refuses
  before anything is appended.** With (a) in place the lost-refs restart needs a snapshot directory
  restored from before the compaction (or lost). Recovery then follows #1258: the leading gap is
  accepted as reclaimed history — survivors sit at their STORED offsets (offset 0 is absent, never
  another event's payload), the range is WARNed and `walRecoveryHeadGapsAccepted` counts it — and the
  sealed history below is unreachable until the snapshot is restored (after a power loss: re-point
  `LATEST` at the previous retained `snapshot-*.dat`). A gap or duplicate INSIDE the tail is
  `StreamError.WalReplayMismatch` (#1258), and #1345 adds the pass that raises it BEFORE any record is
  appended: a refused recovery hands the sink nothing, so no renumbered segment can be sealed from it.
  While refused the node stays up, the stream is absent on this node, the reconcile loop retries every
  tick (ERROR at the refusal, WARN "materialize-on-reconcile failed"), and each publish fails with the
  typed cause. Nothing is renumbered on any path. An EMPTY compacted WAL against a lower watermark is
  accepted vacuously `[unverified: unreachable via the ring today; #1278's floor closes it]`.
  Note: a partition whose every ref was reclaimed by retention (#1278, open) is indistinguishable from
  lost refs and is accepted with the same WARN on every restart until #1278 persists a reclaimed-through
  floor.
  [verified: `StreamPartitionManagerRestartAfterCompactionTest
  restartAfterCompaction_walStartsAboveDurableWatermark_survivorsKeepStoredOffsets_headGapWarned`,
  `restart_walWithMidLogHole_refusesLoudly_andSealsNothing` (red with the pre-append pass removed or moved
  after the appends), `restartAfterRetentionReclaimedEveryRef_acceptedAsReclaimedHistory_warnedUntil1278`]
