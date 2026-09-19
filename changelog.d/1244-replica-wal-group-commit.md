### Performance (2026-09-19 — #1244: replica WAL appends were chained one fsync per record, defeating group commit)
- **A replica fsynced its WAL once per replicated record.** Each replicated append was chained on its
  predecessor's fsync, so nothing ever shared a group commit. Measured before the fix: one 100-record
  `ReplicateEvents` batch cost the replica **100 fsyncs**. Replica frames are now written inside the
  partition's ordered append section with no per-record fsync, and `syncReplicated` (the barrier the
  receive handler awaits before acking) commits everything written so far in **one** group commit. The same
  batch now costs one fsync, and replay order equals offset order.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/ReplicaWalGroupCommitTest.java]`
  (unit level, one JVM.)
- **Backfill now commits before it promotes.** Replica frames no longer carry their own fsync, so a
  `PartitionBackfill` run awaits the same barrier after applying the pulled events and **before** marking
  the replica CAUGHT_UP and acking the owner. A failed commit fails the run, and the replica stays SYNCING.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillDurabilityTest.java]`
- A failed replica frame write still stops acks for that partition: the failure is sticky against later
  writes to the same WAL. A rebuilt partition, with a new WAL instance, starts clean; previously its
  predecessor's failure poisoned it until restart.
- `[unverified: live replication sends one record per ReplicateEvents message (#263), so each message
  still pays one barrier; batching fsyncs across back-to-back messages depends on group-commit timing and
  is not bounded by a test]`
