### Fixed (2026-09-30 — a paced replica no longer reads as unreachable to its partition owner's backfill probe)
- **A node that holds a stream partition but has not materialized it (paced by `reshuffle_concurrency`, or deferred for
  budget) answered the owner's watermark probe `PARTITION_NOT_LOCAL`, and `PartitionBackfill` read any probe failure as
  "unreachable".** The owner then stayed non-authoritative for the whole 20 s source wait before promoting at its local
  watermark with a `POSSIBLE DATA LOSS` warning, for a brand-new partition with no history to lose. A replica probing an
  owner that had not promoted yet was refused the same way (the plain read class is promotion-gated).
  The probe now runs over the catch-up read class, and a replication-class read of a held-but-unmaterialized partition
  fails with the new typed `StreamError.PartitionHeldNotMaterialized`, carrying the node's durable watermark (WAL head or
  sealed bound, `-1` when none) that the prober reads as a REACHABLE answer. A genuine non-holder (`PARTITION_NOT_LOCAL`)
  and transport failures stay unreachable; a node that cannot inspect its log keeps answering `PARTITION_NOT_LOCAL`.
  [mechanism: `StreamForwardHandler#nameHeldPartition` names the refusal, `OwnerPeerReads#replicaWatermark` settles it;
  pinned by `HeldNotMaterializedProbeTest`, `OwnerPeerReadsTest.replicaWatermark_*` and
  `PartitionBackfillTest.backfill_freshOwner_bothPeersHeldNotMaterialized_selfPromotesOnTheFirstAttempt`, each red with the
  settle step or the handler's naming reverted]
- **The owner promotion gate re-drives a failed activation itself** (250 ms doubling to 2 s while the node still claims
  the partition), instead of waiting for the next demand; before, a first attempt that failed (e.g. a peer that had not
  applied the stream's config) left the partition refused until something else probed it (~25 s in cloud run 1).
  [pinned by `OwnerActivationTest.admit_firstAttemptFails_redrivesWithoutAnotherDemand`, red with the re-drive removed]
- **A replica's first replicate append at offset 0 of an empty partition is no longer paced by `reshuffle_concurrency`**,
  so a `confirmation_factor` 2 publish to a new stream is acknowledged while the slots are busy; a partition with durable
  data stays paced. [pinned by `HeldNotMaterializedProbeTest.appendRecovered_atOffsetZeroOfAnEmptyPartition_*`, and by
  `EmberHeldPartitionPublishTest`, both red with the bypass reverted]
- `Stream not found` from a peer stays UNREACHABLE (a cold-restarted peer may hold data it has not yet applied config for).
- **Also fixed:** the old probe paged from offset 0 and never resumed on `CursorExpired`, so a peer whose offset 0 had aged
  out read as unreachable; it now shares the promotion gate's paging.
