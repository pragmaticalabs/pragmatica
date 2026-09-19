### Fixed (2026-09-19 — #1235: stream reads and push notifications exposed events before they were WAL-durable or replicated)
- **A consumer could act on an event that owner failover then erased.** The ring notified push listeners
  inside `append`, before the WAL fsync and before replication, and every consumer read served the raw
  ring head. A durable-topic subscriber could deliver event N and commit past it; the owner could then be
  killed before the fsync, and the promoted replica would reuse offset N for a different event, which the
  group then skipped. Each partition now keeps three positions: **appended** (the ring head),
  **durable** (the owner's WAL fsync, or a replica's own WAL write) and **visible**. On the owner,
  visible = min(durable, the highest offset acknowledged by `minSyncReplicas - 1` distinct peers).
  Consumer reads (`StreamPartitionManager.readLocal`, `OffHeapRingBuffer.read`/`readSlice`, and forwarded
  consumer reads) are bounded by visible. Push listeners are notified when visible advances, never on the bare
  append, and always on the ring's serial notifier: an advance only queues the new visible offset, so
  no publisher, WAL-commit or replica-ack thread runs a listener. A stream with `minSyncReplicas <= 1` and no WAL makes an event visible as soon as it is
  appended; with a WAL, as soon as it is fsynced.
  `[mechanism: per-partition AtomicLong watermarks in OffHeapRingBuffer; visibility is recomputed when the owner's publish sees its fsync, and on every ReplicateAck through a ReplicationManager ack observer that runs before the registry records the ack (reading it through an overlay) and so before any waiter resolves, so an acknowledged publish is already readable; the read path takes no lock]`
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionVisibilityTest.java]`
  (unit level, one JVM, no multi-node run.)
- **An owner WAL failure after the ring append no longer exposes the event.** A failed frame write or a
  failed fsync leaves the event in the ring but never makes it visible, even when a peer acknowledges it
  later. The publish still fails with the WAL's own error. Classifying that failure as outcome-unknown is
  a follow-up that needs #1257.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionVisibilityTest.java]`
- **Replication reads are served from the appended head.** Replica catch-up and survivor pulls now send
  `ReadForward` with a new `catchup` flag (`StreamForwardClient.readRemoteCatchup`). The source answers
  them up to its appended head ONLY when the sender is a registered replica of that partition; a flag
  from any other node is served as a consumer read, up to the visible position. Without the flag, a lagging replica could never fetch the
  events that only its own ack would make visible. This is a wire change to `ReadForward`. The durable
  entity log fold also reads the appended head (`StreamPartitionManager.readAppended`), because its
  replay bound is the appended head; entity-log visibility is part of #1274.
- **A replica-local read is bounded by the replica's own durability**, not by the owner's min-sync
  acknowledgements, because a replica does not learn the owner's visible position; carrying the owner's visible
  position on replication frames is #1274. `[unverified: a
  replica can still serve an event the owner never made visible; whether survivor re-drive always
  promotes a replica that holds it after a single owner loss was not exercised]`
- `[unverified: after an owner restart, WAL records whose publish failed to reach min-sync are recovered
  as visible — the same at-least-once territory as an un-acknowledged but durable record]`
