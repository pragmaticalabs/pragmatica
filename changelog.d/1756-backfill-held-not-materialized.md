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
- **Not closed here:** a publish to such a stream still waits on its replicas, which stay paced, so the confirmation
  barrier (`confirmation_factor` 2) can time out (`REPLICATION_TIMEOUT`) until the reshuffle tick releases a slot.
- **Also fixed:** the old probe paged from offset 0 and never resumed on `CursorExpired`, so a peer whose offset 0 had aged
  out read as unreachable; it now shares the promotion gate's paging.
