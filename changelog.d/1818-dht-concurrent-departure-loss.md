### Fixed (2026-10-02 — #1818: DHT key lost from every replica after two nodes depart concurrently)
- **A copy of an existing key was refused by the owner-epoch fence.** Anti-entropy repair, migration and the departure
  push wrote each copy through the fenced `putVersioned` path. That path rejects any epoch older than the node's single
  `"core"` ownership high-water, which advances on every `DhtPartitionOwnershipKey("core")` rewrite. After such a rewrite,
  every key written before it could never be copied to a node that lacked it, and the key died with its last holder.
  Copies now go through `StorageEngine.putReplica`:
  - it keeps the per-key epoch and HLC ordering, so a copy never overwrites a newer entry;
  - it skips the high-water check;
  - it never advances the high-water, which stays driven by committed ownership state.

  Fresh writes are fenced exactly as before.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTMigrationEpochFenceTest.java`]
  [unverified: a deposed owner's write that reached one lagging replica can now spread to others by migration. This is
  input to #1777 track 3 (per-key versions).]
- **The departure push trusted a receipt the receiver never earned.** `DHTMessage.MigrationDataAck` gains `applied`. A
  receiver that fails to store a pushed batch nacks it, and the departing node reports those chunks at risk
  (`DeparturePushIncomplete`) instead of halting as though they were delivered. Wire change: re-recorded in
  `wire-assignment-baseline.txt`.
  [verified: `DHTMigrationEpochFenceTest.departurePush_toAReceiverThatFailsToApply_reportsTheChunkAtRisk`,
  `ackRequestedBatch_isNackedByAReceiverThatFailsToApply_andAckedByOneThatStores`]
- **A departure push ignored nodes draining at the same time.** It excluded only the departing node itself. A push could
  land on a co-drainer that then halted with the copy, and two holders leaving together each counted the other as a
  survivor. The drainer now reads the leader ping's global `drainNodes` set (`ClusterSyncCollector.commandedDrainNodes()`)
  when it pushes, through `DhtDeparturePush`. It excludes every node in that set both as a push target and as a
  surviving replica.
  - A wrong or stale entry in the set can only add targets, never remove a needed one.
  - If drains are staggered across pings, the later drainer relays what the earlier one handed it.

  [verified: `DHTChurnSurvivalTest.concurrentDeparture_*` and `staggeredDeparture_*`, `DhtDeparturePushTest`,
  `DrainCommandPlumbingTest$Receive`]
  [unverified: no multi-node or cloud run.]
