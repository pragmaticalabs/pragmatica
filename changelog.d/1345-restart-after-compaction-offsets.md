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
- **Recovery refuses a gap instead of renumbering.** WAL replay now checks, before appending anything,
  that the replayed records start at `lastSealedOffset + 1` and run contiguously. A WAL whose first
  survivor sits above that (refs lost after a compaction, or a mid-log hole) fails the partition's
  recovery with `StreamError.WalRecoveryGap` naming the stream, partition, watermark, expected and
  found offsets, logged at ERROR. The node never renumbers on its own. Operator recovery: restore the
  metadata snapshot covering the gap (after a power loss: re-point `LATEST` at the previous retained
  `snapshot-*.dat`), or accept the loss explicitly by removing the partition WAL. While refused the node
  stays up, the stream is absent on this node, the reconcile loop retries every tick (ERROR at the
  refusal, WARN "materialize-on-reconcile failed"), and each publish fails with the typed cause. The
  check is vacuous on an EMPTY compacted WAL `[unverified: unreachable via the ring today; #1278's
  floor closes it]`.
  Note: a partition whose every ref was reclaimed by retention (#1278, open) now refuses at boot
  instead of silently renumbering; #1278's persisted reclaimed-through floor will satisfy this check.
  [verified: `StreamPartitionManagerRestartAfterCompactionTest
  restartAfterCompaction_walStartsAboveDurableWatermark_refusesLoudly`, `restart_walWithMidLogHole_refusesLoudly`
  — both red with the check removed]
