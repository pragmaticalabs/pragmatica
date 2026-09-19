### Performance (2026-09-19 — #1244: replica WAL appends were chained one fsync per record, defeating group commit)
- **A replica fsynced its WAL once per replicated record.** Each replicated append was chained on its
  predecessor's fsync, so nothing ever shared a group commit. Measured before the fix: one 100-record
  `ReplicateEvents` batch cost the replica **100 fsyncs**. Replica frames are now written inside the
  partition's ordered append section with no per-record fsync, and `syncReplicated` (the barrier the
  receive handler awaits before acking) commits everything written so far in **one** group commit. The same
  batch now costs one fsync, and replay order equals offset order.
  `[mechanism: frames are written in-section and the barrier commits the latest write sequence once; pinned
  in one JVM by ReplicaWalGroupCommitTest]`
- **Backfill commits before it promotes.** Replica frames no longer carry their own fsync, so a
  `PartitionBackfill` run commits what it applied, exactly once and never per record, **before** it
  marks the replica CAUGHT_UP and acks the owner. This holds even on a quiet partition that receives no
  later live batch. A failed commit fails the run, and the replica stays SYNCING. (The failover replay
  paths are exempt by CTO waiver: `FailoverRecovery` has no production caller, and `GovernorFailoverHandler`
  replays already-sealed segments and never acks.)
  `[mechanism: the run awaits syncReplicated before promote; pinned against a real WAL's fsync counter by
  CatchUpWalDurabilityTest and PartitionBackfillDurabilityTest]`
- A failed replica frame write or fsync still stops acks for that partition, because it fail-stops that
  WAL. The latest-write entry is forgotten when its WAL is released, so a rebuilt partition's first
  barrier never targets the closed WAL. Previously the per-key chain stayed poisoned until restart.
- `[unverified: live replication sends one record per ReplicateEvents message (#263), so each message
  still pays one barrier; batching fsyncs across back-to-back messages depends on group-commit timing and
  is not bounded by a test]`
