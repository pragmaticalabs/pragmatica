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

  If the exclusion leaves fewer than RF nodes, a stale extra entry would remove the only newcomer. The push then falls
  back to every remaining node.
  [verified: `DHTChurnSurvivalTest.concurrentDeparture_*` and `staggeredDeparture_*`, `DhtDeparturePushTest`,
  `DrainCommandPlumbingTest$Receive`, `DHTDepartureStaleDrainSetTest`]
  [unverified: no multi-node or cloud run.]
- **Anti-entropy applied any migration batch it received.** It checked neither the sender, the correlation id, nor the
  partition. A batch is now applied only when it is authentic:
  - a pull answer must match an outstanding pull: the same correlation id, from the node asked, and only entries of the
    partition asked for;
  - a departure push must come from a node the leader's drain set names, or that membership saw enter DEPARTING; any
    other push is nacked;
  - a survivor-rebalance push must come from a co-replica of every entry's partition.

  Everything else is dropped with a WARN and counted.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTMigrationResponseAuthenticityTest.java`,
  `DhtDeparturePushTest.departingSenders_*`]
  A departure push is also accepted from a current replica, in the receiver's ring, of every entry's partition. A
  node that drains itself on quorum loss is in neither set, and it halts with its in-memory store, so without this
  rule a last copy it held would be lost. An accepted push places copies only on partitions the receiver replicates,
  before or after the departure. Any other entry is nacked, so the pusher keeps it at risk.
- **A deposed owner's DHT write could survive its own refusal (owner ruling, the Dynamo stance).**
  - A put whose quorum was lost to owner-epoch fences now fails `DHTError.WriteIndeterminate`: transient, and "may
    have been applied". `PutResponse` gains `fenced`.
  - The coordinator compare-and-deletes its own accept (`StorageEngine.removeIfExactly`), so anti-entropy cannot
    spread it. This also applies when a fence refusal is followed by a lost or slow reply. Until anti-entropy
    refills the key, the coordinator reads it as absent.
  - Each copy applied below the receiver's high-water is counted (`DHTNode.belowHighWaterCopyCount`) and logged at
    INFO with its partition.
  - ArtifactStore retries `WriteIndeterminate`. The guarantee is documented in `ownership-fence-spec.md` §7.1.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTDeposedWriterRollbackTest.java`,
  `ArtifactStoreTest$WriteIndeterminateTests`]
  [unverified: another lagging replica that also accepted the deposed write can still spread it, on keys the new
  owner never rewrites. This closes with #1777 track 3.]
