### Fixed (2026-09-19 — #1233: EVENTUAL publish reported success when a frozen ring could not fit the event, then WAL-wrote it at the previous head's offset)
- **A dropped event was acknowledged, WAL-written and replicated under another event's offset.** When the
  off-heap pool refused growth, a DROP_OLDEST (EVENTUAL) ring frozen at its allocation dropped any event
  larger than that allocation but reported success at the *existing* head offset. `publishLocal` then
  appended the dropped payload to the partition WAL under that offset and replicated it; replicas
  discarded it as a duplicate, and a restart replayed it as `head + 1`, shifting every later offset.
  Durable topics (EVENTUAL) were affected.
- The ring now reports a distinct `StreamError.General.EVENT_DROPPED` outcome instead of success, for
  both the single-event and the batch gate. A drop never reaches the WAL or replication, because both
  run only on a successful append. [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerFrozenRingDropTest.java]
- **Rule for which streams may absorb a drop.** `StreamConfig` has no explicit best-effort field, so the
  rule is derived. A drop **fails the publish** on any stream with durability semantics:
  `minSyncReplicas >= 2`, **or** a partition WAL, **or** an entity keyspace log (`entity:<keyspace>`),
  **or** a durable-topic or DLQ stream (`topic:<address>`, `topic:<address>.dlq`). The last two are
  identified by name, so an entity keyspace at replication factor 1 without a WAL (Forge, embedded, or the
  non-durable opt-in) can no longer ack a lost write. A refused drop is logged at WARN and counted in
  `StreamPartitionManager.refusedPublishDropsSinceBoot()`. Only the remaining app streams are
  best-effort: the drop is absorbed, the publish is acked at the unchanged head, the event is not stored,
  a WARN is logged, and `StreamPartitionManager.droppedEventsSinceBoot()` is incremented. Each clause has
  its own test. [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerFrozenRingDropTest.java]
  [verified: aether/node/src/test/java/org/pragmatica/aether/node/StreamEntityLogSubstrateTest.java]
- **Replicas.** A replicated event that a replica's frozen ring cannot fit now fails `appendRecovered`
  with `EVENT_DROPPED`. It is never applied or WAL-written on that replica, and the refusal is logged at
  WARN and counted in `StreamPartitionManager.refusedReplicaDropsSinceBoot()`.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerFrozenRingDropTest.java]
  The receive handler still acks the events of the same batch that were applied before the refused one,
  then stops. Nothing at or after the refused event is acked.
  [mechanism: `ReplicationReceiveHandler.applyBatch` breaks on the first failed append, and the handler
  acks the highest applied offset] That partition then stalls on the replica. Backfill hits the same
  refusal, so the replica stays SYNCING. An owner whose min-sync barrier needs that replica (every durable
  topic, which is parse-enforced to `min-sync == replicas`) fails every later publish on the partition
  with a replication timeout, until the replica's ring is rebuilt with pool budget. This fails closed, the
  same as a dead replica. Before this change the replica acked an offset it did not hold, and every later
  offset on it was shifted. [mechanism: traced through `ReplicationReceiveHandler` and
  `DefaultReplicationManager`] [unverified: multi-node — not run on a cluster]
- **Partition bring-up.** Rebuilding a partition ring from its WAL now **fails the bring-up** when the
  WAL tail holds a record larger than the ring can grow to, because the pool refuses growth. The stream is
  then absent on that node rather than served with a hole. Before this change the record was silently
  skipped and every later record shifted down one offset.
  [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerFrozenRingDropTest.java]
- The three counters are accessors only. They are not yet exported to the metrics pipeline or the
  Management API. [design intent — unverified]
- **Operator recovery.** A refused publish, a refused replicated append, or a failed partition bring-up of
  this kind all mean the node's off-heap pool cannot give the ring the room the event needs. A ring that
  has frozen stays frozen for its lifetime. [mechanism: `OffHeapRingBuffer.growthFrozen` is set on a
  refused growth and is never cleared]
  - **Restart alone does not help.** Restarting the node without freeing budget brings the partition up
    frozen again. If the WAL already holds such a record, bring-up fails outright and the partition does
    not materialize on that node.
  - **To clear it:** first free pool budget (destroy or right-size other streams) or raise the node's
    stream-memory limit, then restart the node so the ring is rebuilt with room to grow.
  [design intent — unverified: the restart-with-budget path has not been exercised]
