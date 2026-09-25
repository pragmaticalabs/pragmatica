### Fixed (2026-09-25 — #1505: a replica catch-up that raced a live batch appended one offset too high, and the replica acked an event it did not hold)

- **The catch-up apply appended at the local ring tail, and it took its position from before the request
  went out.** `PartitionBackfill` computed `fromOffset` from the local head before the request. When the
  response arrived, it appended each event wherever the ring's next offset happened to be. If the owner's
  live batch for the same offset landed first, the catch-up copy went one offset too high. The receiver
  then treated the next live event as a stale duplicate and acked it without holding it. That ack counted
  toward min-sync, so the event was lost when this replica was promoted.
  [verified: `PartitionBackfillLiveInterleaveTest.backfill_liveBatchLandsDuringCatchup_nextLiveEventIsHeldAtItsOwnerOffset`,
  red on `8530a4a0e` with `14=marker-13`]
- **Every replica event now lands at its own owner offset, through one ordered section.** The catch-up
  apply and the live receive both call `StreamPartitionManager.appendRecovered(stream, partition, offset, …)`.
  That method decides inside the partition's append lock (`OffHeapRingBuffer.appendOrderedAt`) whether to
  append at the head, verify an already-held record, or refuse. The two paths therefore share one offset
  authority rather than two checks that must agree.
  [verified: `StreamPartitionManagerAlignedAppendTest` (two writers race the same 2,000 events, 20 repetitions,
  and the ring and WAL each hold every event once); it reddens 20/20 when the head is read outside the lock or
  the offset is ignored]
- **An already-held offset is skipped only after its payload and timestamp are compared.** A different held
  record is refused as `StreamError.ReplicaEntryConflict`. A catch-up that meets one fails, and the replica
  stays SYNCING. A response starting past the local head is refused as `StreamError.ReplicaOffsetGap`.
  Neither case appends anything.
  [verified: `OffHeapRingBufferAppendAtTest`, and the three new `PartitionBackfillLiveInterleaveTest` backfill cases]
- **A duplicate live batch is acked only when every event in it is verified held.** The old stale-duplicate
  branch re-acked by offset alone and has been removed. A divergent held record gets no ack. It also gets no
  catch-up request: a catch-up pulls only past the local head and cannot replace a held entry, and an empty
  response would take the #559 at-owner-tail promotion. The refusal is logged at ERROR, which is how #1230
  treats a batch from an unauthorized sender. A duplicate's ack now also waits for the durability barrier.
  [verified: `ReplicationReceiveHandlerTest.duplicateBatch_heldEventDiffers_isRefused_noAck_noRepair_noAppend`,
  `…overlappingBatch_heldPrefixDiffers_acksOnlyVerifiedPrefix_appendsNothingPastIt`,
  `PartitionBackfillLiveInterleaveTest.liveDuplicate_heldEventDiffers_isNotAcked`; all three redden when the
  blind re-ack or the unverified skip is restored]
- **Three fixtures encoded the old behaviour and were corrected.**
  - `overlappingBatch_skipsAppliedPrefix_appliesTailOnly` "re-delivered" `p-0` at offset 1, where `p-1` was
    held. The blind skip could not notice; it now sends the same events.
  - `TrackingAppender` held no content, so it could not model verification; it now does.
  - The reproduction's `doesNotContain(14L)` forbade acking 14 at all. The base never reached that line,
    because the log assertion fails first. It is replaced by "every acked offset holds the owner's event there".
  [mechanism: stated at each site]
- **[unverified: no multi-node or cloud run]** Everything above is established in-JVM against real partition
  managers, rings, WALs and the production receive-handler and backfill factories. Only the network is stubbed.
- **[unverified: a divergent held entry has no repair path]** The divergence is refused and surfaced, not
  repaired. A later redrive pulls from past the local head, and so can still promote a replica whose log
  holds the conflicting entry. Governor-failover recovery (`StreamPartitionRecovery`) still appends at the
  tail; it was not examined for the same race.
