### Fixed (2026-09-25 — #1505: a replica catch-up that raced a live batch appended one offset too high, and the replica acked an event it did not hold)

- **The catch-up apply appended at the local ring tail, and it took its position from before the request
  went out.** `PartitionBackfill` computed `fromOffset` from the local head before the request. When the
  response arrived, it appended each event wherever the ring's next offset happened to be. If the owner's
  live batch for the same offset landed first, the catch-up copy went one offset too high. The receiver
  then treated the next live event as a stale duplicate and acked it without holding it. That ack counted
  toward min-sync, so the event was lost when this replica was promoted.
  [verified: `PartitionBackfillLiveInterleaveTest.backfill_liveBatchLandsDuringCatchup_nextLiveEventIsHeldAtItsOwnerOffset`,
  red on `8530a4a0e` with `14=marker-13`]
- **Every replica-side apply now lands each event at its own owner offset, through one ordered section.**
  Four paths now call `StreamPartitionManager.appendRecovered(stream, partition, offset, …)`:
  - the live receive;
  - the catch-up apply;
  - governor-failover segment replay (`GovernorFailoverHandler`), which appended at the tail on every
    consensus-leader change until review F1;
  - catch-up failover recovery (`DefaultFailoverRecovery`).

  Under the partition's append lock (`OffHeapRingBuffer.appendOrderedAt`), that call appends at the head,
  verifies an already-held record, or refuses. The tail-append seam `StreamPartitionRecovery` is deleted.
  After this change the ring is written only by:
  - the owner's own publish paths (`appendOrdered` / `appendBatchOrdered`);
  - WAL replay, which checks each record's offset;
  - `seedHead` on a fresh ring.

  [verified: `GovernorFailoverHandlerTest$OffsetAlignedReplay.handleFailover_replayOverlapsHeldRing_keepsEveryEventAtItsOwnOffset`
  (the reviewer's S3: before the fix, event 10 landed at 13), and `StreamPartitionManagerAlignedAppendTest`
  (two writers race the same 2,000 events). The race test reddened 20/20 in every measured run with the offset
  ignored. With the head read outside the lock and a `Thread.yield` widening the window, it went 80/80 over four
  full runs (20 in the first round, then 3 × 20). Two reviewer variants went 19/20 and 17/20. It is a
  probabilistic pin.]
- **An already-held offset is skipped only after its payload and timestamp are compared.** A response that
  starts past the local head is refused as `StreamError.ReplicaOffsetGap`. An overlapping re-delivery whose
  prefix the ring has already evicted still applies its new tail, because an evicted offset is passed over
  rather than stopping the batch (review F3).
  [verified: `OffHeapRingBufferAppendAtTest`; `ReplicationReceiveHandlerTest.overlappingBatch_evictedPrefix_appliesNewTail_andAcks`;
  `GovernorFailoverHandlerTest$OffsetAlignedReplay.handleFailover_evictedOffsets_arePassedOver_restAreReplayed`]
- **A divergent held entry QUARANTINES the partition on that replica (review F2, CTO ruling: quarantine, not
  repair).** When an offered event differs from the one held at offset `N`:
  - The partition manager records `N`, inside the same section that found it. From then on, every offer at
    an offset `>= N` is refused as `StreamError.ReplicaQuarantined`, live or duplicate, from any path. So
    nothing at or past `N` is appended, verified or acked.
  - The first round only refused the one batch, and a later batch's cumulative ack covered `N`.
  - It is logged at ERROR once and counted by `StreamPartitionManager.quarantinedPartitionsSinceBoot()`. Like
    the neighbouring drop counters, that is a manager getter and is not exported as a metric.

  [verified: `PartitionBackfillLiveInterleaveTest.quarantine_divergentHeldEntry_laterLiveBatch_isNotAckedPastIt`
  (the reviewer's S1, red on the first round with acks `[12, 14]`); `OffHeapRingBufferAppendAtTest.appendOrderedAt_quarantined_refusesAtOrPastDivergence_verifiesBelowIt`;
  `ReplicationReceiveHandlerTest.laterBatchPastDivergence_isRefused_notAcked`]
- **A quarantined partition is never promoted CAUGHT_UP on that node, by any path.**
  - `PartitionBackfill` refuses at entry, holding self SYNCING below `N`. That demotes a replica that was
    already CAUGHT_UP, and a divergence found by the live path reaches this refusal through `onGap`.
  - It refuses again at every terminal promotion step, because each is reached asynchronously:
    - `promote` after a pull;
    - `ownerSelfPromote`;
    - the #559 at-owner-tail promotion;
    - the cold-start contest (`decidePromotion`);
    - the re-verify completion re-ack (`reverifyNoOp`).

  [verified: `…quarantine_divergentHeldEntry_backfillMustNotPromoteCaughtUp` (the reviewer's S2, with one step
  added, see below); `…quarantine_caughtUpReplicaMeetsDivergence_isDemotedBelowIt_sendsNoCompletionAck`;
  `…quarantine_recordedWhileRunInFlight_blocksTheAtOwnerTailPromotion`, which pins the terminal gate alone.
  Removing the entry gate reddens the first two; removing the terminal gate reddens only the third.]
- **The terminal check, the promotion and its completion ack are atomic with recording a divergence (re-review
  R3).**
  - `QuarantineView.unlessQuarantined` runs the check and then the registry write plus the ack under the
    partition manager's quarantine lock. `recordDivergence` takes the same lock.
  - A divergence is therefore recorded either before the check, which refuses, or after the ack has left.
  - That closes the window where one completion ack could leave after a divergence was recorded.
  - The lock order is always ring section → quarantine lock, because a promotion never appends to a ring.

  [verified: `…quarantine_divergenceRecordedDuringCompletionAck_waitsForThePromotionToFinish`. Inside the ack it
  meets a divergence on another thread, then waits 300 ms for the quarantine to appear. It reddens when the lock
  is removed from the guard (in 2 of 2 full runs); with the lock the wait always times out.]
- **The fence is READ inside the ordered section, not only recorded there (re-review R2).**
  [verified: `OffHeapRingBufferAppendAtTest.appendOrderedAt_fenceReadAndAppend_shareOneSection_nothingAppendsAfterARecordedDivergence`.
  Writer B's fence read keeps the value it saw, then parks until writer A records a divergence. It is
  deterministic: with the read inside the section, A cannot record while B parks. It reddens when the read is
  hoisted outside the lock (mutation M11, verified by content), which the first round's suite could not catch.]
- **The ack reflects the batch's verified prefix, and it can lower the owner's view (review F4, kept and
  documented).** The owner stores the last ack it receives. After a divergence at `N`, a lower ack is
  deliberate: the old ack for `N` covered an entry now known to differ. For an old duplicate it is
  conservative, and the next live ack restores it. [mechanism: `ReplicationReceiveHandler.applyAligned` doc]
- **Fixtures changed and why.**
  - `overlappingBatch_skipsAppliedPrefix_appliesTailOnly` "re-delivered" `p-0` at offset 1, where `p-1` was
    held. It now sends the same events.
  - `TrackingAppender` held no content, so it could not model verification. It now models verification,
    eviction and quarantine.
  - The reproduction's `doesNotContain(14L)` forbade a correct ack. It became "every acked offset holds the
    owner's event there", and now also pins `.contains(14L)` so it cannot pass vacuously (review F6).
  - The first round's two divergence tests asserted no `onGap`. Under quarantine, `onGap` is the demotion
    route.
  - The reviewer's S2 planted a divergent entry that nothing ever compares with the owner's, so no mechanism
    can detect it. The pinned version adds the live batch that meets it.
  [mechanism: stated at each site]
- **[unverified: no multi-node or cloud run]** Everything above is established in-JVM against real partition
  managers, rings, WALs and the production receive-handler, backfill and failover factories. Only the
  network is stubbed.
- **[unverified: quarantine has no repair and no persistence]** Clearing a quarantine needs a
  truncate-and-refetch repair, which does not exist and is out of scope: **#1514**. The record lives in memory for the
  life of the partition manager: it survives a ring release, but not a process restart, because persisting
  it would be a new persisted-state format. After a restart, the entry is caught again only when an offer
  meets it. Persisting it is **#1513**.
- **[unverified: quarantine is node-local]** It stops THIS node from promoting itself, acking past `N` or
  re-acking. It cannot remove the node from HRW owner election or from other nodes' source choice: they
  learn nothing of it without a wire change, and none was made. A quarantined node that is elected owner
  stays SYNCING in its own registry, but its owner publish path is not gated. Excluding it from owner election
  is **#1513**.
