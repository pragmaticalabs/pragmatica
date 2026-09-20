### Fixed (2026-09-20 — #1235 × #1244: the replica visible-advance re-added one WAL commit request per replicated record)
- **#1309 (#1235 visible watermark) and #1277 (#1244 replica WAL group commit) were never built together
  before both were on rc4**, and their composition undid #1277: `appendRecovered` requested its record's
  own WAL group commit to learn when to advance the replica's visible offset, so a replicated batch of N
  records issued N async commit requests again. The WAL coalesces requests under its sync lock, so the
  realised fsync count was "however many commits ran between writes" — 2, 3 or 10 for one batch, and 0
  for the test's own barrier when the per-record commits had already covered everything — and rc4's tip
  ran red on three #1277 pins that hold by construction only with one request per batch.
  [mechanism: `PartitionWal.groupCommit` returns without `force` when `syncedSeq >= mySeq`]
- The replica visible-advance now runs at the group-commit barrier (`syncReplicated`), not per record:
  the barrier commits the latest replicated write and then, as a sequential step of its own promise,
  makes records up to that write's offset visible — so a caller that acks after the barrier acks records
  this replica already serves. A partition with no WAL still advances at once on append. A batch of N
  records is one commit request and one fsync.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionVisibilityTest.java`
  (`appendRecovered_withWal_requestsNoCommitPerRecord_theBarrierRequestsOne`,
  `appendRecovered_withWal_visibleOffsetAdvancesOnlyWhenTheBarrierResolves`)]
- `PartitionWal.commitRequests()` counts group commits REQUESTED, beside the existing completed-fsync
  count: the request count is what a "one commit per batch" contract can be pinned on, because
  coalescing hides per-record requests behind an fsync count of anywhere from 1 to N.
- **The two failover paths, `DefaultFailoverRecovery` and `GovernorFailoverHandler`, now commit through
  the replica WAL barrier once per run, after their last recovered append** (CTO ruling 2026-09-20,
  replacing the 2026-09-19 waiver). With the advance at the barrier, a WAL-backed record those paths
  recovered would otherwise have stayed invisible on that replica until an unrelated live batch's barrier
  happened to cover it. Recovered records are visible after the run with no live batch, at exactly one
  fsync per run.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/CatchUpWalDurabilityTest.java`
  (`failoverRecovery_completedRun_isFsyncedOnce_andItsRecordsAreVisible_withoutALaterLiveBatch`),
  `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/GovernorFailoverHandlerTest.java`
  (`handleFailover_completedRun_isFsyncedOnce_andItsRecordsAreVisible_withoutALaterLiveBatch`)]
