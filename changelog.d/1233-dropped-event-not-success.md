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
  rule is derived. A drop **fails the publish** when `minSyncReplicas >= 2` (durable topics and their DLQs
  are parse-enforced to `min-sync == replicas >= 2`) **or** the partition has a WAL. On every other stream
  (best-effort) the drop is absorbed: the publish is acked at the unchanged head, the event is not stored,
  a WARN is logged, and `StreamPartitionManager.droppedEventsSinceBoot()` is incremented. Each clause has
  its own test. [verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerFrozenRingDropTest.java]
- On a replica, a replicated event that a frozen ring cannot fit now fails `appendRecovered`. The receive
  handler logs it and stops applying the batch, so nothing is acked. Before this change the replica
  WAL-wrote the payload at its own previous head. [mechanism: `appendRecovered` runs `walReplicated` only
  on a successful append; `ReplicationReceiveHandler.applyBatch` breaks on the first failed append]
- The drop counter is an accessor only. It is not yet exported to the metrics pipeline or the Management
  API. [design intent — unverified]
- Operator recovery: a failed publish of this kind means the stream's ring is frozen below the event size
  because the node's off-heap pool was exhausted when it tried to grow. The freeze lasts for the ring's
  lifetime, so freeing pool budget alone does not clear it. The ring has to be rebuilt with budget
  available: free budget (destroy or right-size other streams) or raise the node's stream-memory limit,
  then restart the node. [mechanism: `OffHeapRingBuffer.growthFrozen` is set on a refused growth and is
  never cleared] [design intent — unverified: the restart-clears-it path has not been exercised]
