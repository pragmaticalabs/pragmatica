# Aether Streams — Architecture & Performance Design Notes

**Status:** Re-verified against source at `ccba0dba5` on 2026-09-19 (#1248) for §1, §3, §4, §5, §6.3, §10 and §11. §2, §6.1–6.2, §7, §8 and §9 carry over from the 2026-04-11 original and were **not** re-verified at that read point; each says so.
**Measurements:** **None.** This document contains no measured latency, throughput or recovery figure. No benchmark harness for `aether-stream` exists in the repository (searched `git ls-files aether` for `bench|perf|jmh` at `ccba0dba5`; the one code hit, `ObservabilityStep0BenchTest` in `aether/node`, is not a stream benchmark). Every statement is structural (read from source) or analytical (derived from structure).
**Scope:** The implemented streaming architecture and its performance-relevant properties.

Primary implementation lives in `aether/aether-stream/`, WAL in `aether/aether-stream/.../stream/wal/`, cold-tier storage wiring in `aether/node/`, and codec/storage primitives in `integrations/storage/`.

### How to read the figures and tags

Every number in this document is labelled:

- **(code constant)** — a literal in source at `ccba0dba5`; the symbol is named so it can be re-checked.
- **(analytical)** — derived from the structure of the code; not observed on any machine.
- **(measured)** — observed on a stated machine and run. **There are none in this revision.**

Guarantee statements carry the evidence tags from the aether claim discipline: `[mechanism: …]` for what follows from the code path, `[design intent — unverified]` for what is believed but not demonstrated. No statement here carries `[verified: …]`: nothing below was exercised end-to-end on a multi-node live path for this document.

---

## 1. Feature Inventory (re-verified at `ccba0dba5`)

Line numbers are deliberately omitted — they rot; symbols do not.

| Feature | Implementation |
|---|---|
| Off-heap ring buffer (per-partition hot tier) | `OffHeapRingBuffer` (`Arena.ofShared()`, floor-reserve + segmented lazy growth) |
| Batch append | `OffHeapRingBuffer.appendBatch` |
| REJECT_WHEN_FULL / DROP_OLDEST | `OffHeapRingBuffer.append`, `EvictionPolicy` |
| `readSlice` returning a `MemorySegment` | `OffHeapRingBuffer.readSlice` → `readSliceAtOffset`. **Not a zero-copy read:** it returns `MemorySegment.ofArray(readDataBytes(...))`, a heap segment over a fresh `byte[]` copy. `PartitionedStreamAccess.readSlice` is its only caller and has no production caller. See §4.3. |
| Append listener (push path for co-located consumers) | `OffHeapRingBuffer.addAppendListener` / `notifyAppendListeners`, fired inside `append` |
| Per-partition crash-durable WAL (owner) | `StreamPartitionManager.durablyLog` → `PartitionWal.append` (group-commit `force(false)`) |
| Replica WAL append + ack barrier | `StreamPartitionManager.appendRecovered` → `walReplicated` → `chainWalWrite`; barrier `syncReplicated`, awaited by `ReplicationReceiveHandler` before acking |
| Cross-node publish forwarding over QUIC | `stream/forward/`: `StreamForwardMessage`, `StreamForwardClient`, `StreamForwardHandler` |
| `min-sync-replicas` write-ack floor | `DefaultStreamPublisher.publishLocalEventual`, `PartitionedStreamAccess.publishLocal`, `StreamWriteRouter`, `StreamForwardHandler` → `awaitReplication(..., minSyncReplicas - 1)` |
| Owner → replica push | `DefaultReplicationManager.replicateEvent`. The node wires the **non-batching** manager (`ReplicationManager.replicationManager(...)` in `AetherNode`), so each event is sent by `replicateImmediately`. `ReplicationBatcher` (`DEFAULT_MAX_EVENTS = 100`, `DEFAULT_MAX_DELAY = 1 ms`, **code constants**) exists but `batchingReplicationManager` has no production caller. |
| Read routing (GOVERNOR / NEAREST / ANY_REPLICA / LINEARIZABLE) | `PartitionedStreamAccess.readWithPreference` → `ForwardingReadRouter` |
| Consumer group coordination (KV-consensus backed) | `consumer/ConsumerGroupCoordinator` |
| Transactional cursor commit (PostgreSQL) | `consumer/PgTransactionalCursorCommit` — **library only: no production caller** |
| Segment sealer (evicted events → sealed segment) | `segment/SegmentSealer` |
| Segment compression / encryption | `StorageSegmentSink` (compress → encrypt → `putRef`), `SegmentReader` |
| Tiered read (ring buffer → sealed segments) | `segment/TieredStreamReader` |
| Governor failover | `replication/GovernorFailoverHandler`, `WatermarkTracker`, `StreamPartitionRecovery` |
| Retention | `RetentionPolicy`, `OffHeapRingBuffer.applyRetention`, `segment/RetentionEnforcer` |
| STRONG publish via Rabia | `consensus/ConsensusPublishPath`, `ConsensusProposer` — **implemented, not wired**: see §3.3 |
| Adaptive poll interval | `ConsumerRuntimeState` `MIN_POLL_MS = 1`, `MAX_POLL_MS = 50`, `MAX_POLL_BATCH = 100` **(code constants)** |
| Stream memory gauge | `aether.streams.memory.used.ratio` (`ManagementServer`) |

### Not implemented at `ccba0dba5`

Searched by name across `aether/**/*.java` (non-test) for keep-latest/compaction policy, CDC/change-feed, and consumer-lag autoscaling; none found. Positive control: the same search form finds the two `aether.streams.*` gauges.

- Log compaction (keep-latest-per-key).
- CDC adapter (KV-Store → stream change feed).
- Consumer lag autoscaling.
- Cross-cluster / multi-region stream replication. `[design intent — unverified]` that nothing partial exists; not searched beyond name.

The 2026-04-11 revision listed cross-node replica reads here. That is no longer true: reads route through `ForwardingReadRouter` (§4.4).

---

## 2. Architectural Overview

*Carried over from 2026-04-11; not re-verified at `ccba0dba5` except where marked.*

Streams are modelled as partitioned, append-only logs. Each partition has one owner (the single writer). App EVENTUAL publishes route to the partition's HRW owner via the partition-aware owner resolver; the arg-less leader resolver is a fallback, and a self-resolved owner falls back to a local append (`DefaultStreamPublisher.resolveOwner` / `publishRemote`, re-verified).

**Hot tier.** Events land in an off-heap ring buffer allocated via `Arena.ofShared()`: a 64-byte header (`HEADER_SIZE`, **code constant**), 24-byte index entries (`INDEX_ENTRY_SIZE`, **code constant**), then a data region grown in segments up to the stream's cap.

**Durable log.** On a node with a writable WAL directory, each partition also has a `PartitionWal`. An owner publish is not acknowledged until its record is fsynced there (§3.1, §5). A node whose WAL directory is unwritable refuses to boot unless `aether.allowNonDurableStreams=true` / `AETHER_ALLOW_NON_DURABLE_STREAMS=true` is set; with that opt-in, publishes ack without any fsync `[mechanism: AetherNode.resolveStreamWalDir; durablyLog returns success(offset) when walFor is empty]`.

**Cold tier.** Evicted events are serialised into a sealed segment by `SegmentSealer` and handed to a `SegmentSink`. The node wires `StorageSegmentSink` (optional compression + encryption). `PgSegmentSink` exists but has no production caller.

**Reads.** `TieredStreamReader` serves hot reads from the ring and falls back to sealed segments below the ring tail.

**Replication.** Owners push each event to its replicas as it is published (§5.1; re-verified). `WatermarkTracker` records per-replica progress for failover.

---

## 3. Publish Paths (re-verified at `ccba0dba5`)

### 3.1 EVENTUAL, owner-local

Flow (`DefaultStreamPublisher.publishLocalEventual` → `StreamPartitionManager.publishLocal`):

1. Serialize the event via the configured codec (one `byte[]`).
2. `appendToPartition` → `OffHeapRingBuffer.append`: copy the payload into the off-heap data region, write the index entry, and **fire append listeners** (the co-located push path, §4.1).
3. `durablyLog`: if the partition has a WAL, `PartitionWal.append(offset, payload, timestamp)` and **block the calling thread** (`.await()`, a `@TerminalOperation`) until the group-commit fsync covering that record completes. With no WAL, pass the offset through unchanged.
4. On success, hand the event to `ReplicationManager.replicateEvent` (asynchronous; §5).
5. If `min-sync-replicas ≥ 2`, the returned `Promise` additionally awaits `awaitReplication(..., minSyncReplicas - 1)`: that many distinct non-self replica acks, each sent only after that replica's own WAL sync (§5). With `min-sync-replicas ≤ 1` there is no peer-ack wait.

Consequences:

- A successful owner-local publish means the record was fsynced to the owner's WAL `[mechanism: publishLocal chains durablyLog before success; PartitionWal.append resolves only after force(false) covering its record]`, and for `min-sync-replicas ≥ 2`, that `min-sync-replicas − 1` replicas reported their WAL sync for it `[mechanism: ReplicationReceiveHandler acks only on durability.sync success]`. Neither has been exercised by a multi-node crash test for this document.
- **Visibility precedes durability.** Steps 2 and 3 are ordered ring-append-then-fsync, so a co-located consumer can be notified of, and read, an event whose publish then fails on fsync or replication `[mechanism: notifyAppendListeners runs inside OffHeapRingBuffer.append, before durablyLog]`. Tracked as #1235.
- Copies on this path: the codec's `byte[]`, the copy into the off-heap ring, and the WAL write (**analytical**). The producer path is not zero-copy and does not claim to be.
- Recovery action when the WAL fail-stops (a failed fsync): every later append on that partition is refused with `WalError.FailStopped`; restart the node, and recovery trims to the valid prefix. Nothing acked is lost because refused appends were never acked `[mechanism: PartitionWal fail-stop, per its class docs]`.

### 3.2 EVENTUAL, remote (cross-node)

Flow (`DefaultStreamPublisher.publishRemote`):

1. Resolve the partition owner.
2. `StreamForwardClient` sends a `PublishForward` `ProtocolMessage` over QUIC.
3. The owner's `StreamForwardHandler` runs `publishLocal` (§3.1 steps 2–4) and, for `min-sync-replicas ≥ 2`, `awaitReplication`, then replies.

Adds one network round trip plus serialization/deserialization at each end (**analytical**).

### 3.3 STRONG (consensus) — implemented, not wired

`DefaultStreamPublisher.publishStrong` requires a `ConsensusPublishPath`. Every production construction of a publisher (`StreamPublisherFactory`, `SystemStreamFactories`, `AetherNode`) passes `Option.none()` for it, and `ConsensusPublishPath.consensusPublishPath(...)` is called only from tests. A STRONG publish therefore fails with `CONSENSUS_PATH_UNAVAILABLE` `[mechanism: consensusPath.async(StreamError.General.CONSENSUS_PATH_UNAVAILABLE)]`. **No cross-node total-order guarantee is available at `ccba0dba5`.** When wired, `publishBatchStrong` issues one proposal per event (`Promise.allOf` over `publish`).

---

## 4. Consumer Paths (re-verified at `ccba0dba5`)

### 4.1 Push path (co-located, append listener)

`ConsumerRuntimeState.subscribePushOrPoll` registers a `LongConsumer` on the ring's append listeners when the partition is local. On append, the listener calls `onAppend` → `pollCycle` on the appending thread, which reads up to `MAX_POLL_BATCH` events from the cursor and delivers them. The listener fires at ring append, before the WAL fsync (§3.1).

### 4.2 Adaptive poll fallback

When the partition is not local, `ConsumerRuntimeState` polls with an interval bounded by `MIN_POLL_MS = 1` and `MAX_POLL_MS = 50`, batch `MAX_POLL_BATCH = 100` (**code constants**).

### 4.3 Delivery copies — there is no zero-copy read

Storage is off-heap; **delivery copies to heap.** The declarative-consumer path makes three `byte[]` copies per event and then a decode → re-encode → decode round trip `[mechanism: traced below at ccba0dba5]`:

1. `OffHeapRingBuffer.readDataBytes` — `new byte[dataLen]`, filled from the off-heap data region (called by `readSingleEvent`).
2. The `RawEvent` compact constructor — `data = data.clone()`.
3. The `RawEvent.data()` accessor — a second `clone()`, called by `ConsumerRuntimeState.deliverSingleEvent`.
4. `StreamConsumerManager.deliver` → `bridge.decode(payload)` into the subscriber's type, then `SliceInvoker.invokeLocal` → `invokeViaBridge`, which **re-encodes** the decoded object (`senderBridge.encode`) for the target slice to decode again (`targetBridge.invoke`).

`OffHeapRingBuffer.readSlice` does not avoid this: `readSliceAtOffset` returns `MemorySegment.ofArray(readDataBytes(...))`, a heap segment over copy 1, and has no production caller. The `StreamConsumerAdapter` that once advertised zero-copy reads was dead code and was deleted in #577.

Allocation per delivered event is therefore at least three payload-sized `byte[]` arrays plus the decoded object twice (**analytical**).

### 4.4 Read routing

`PartitionedStreamAccess.readWithPreference` delegates to `ForwardingReadRouter`. `GOVERNOR` reads the local partition; `NEAREST` / `ANY_REPLICA` read locally when this node is a caught-up replica and otherwise forward to a caught-up replica or the HRW owner; `LINEARIZABLE` runs the committed-owner routing pipeline when its components are wired, and degrades to the replica-routed read when they are not. The 2026-04-11 statement that non-GOVERNOR preferences always fall back to a local read is no longer true.

### 4.5 Transactional cursor commit

`PgTransactionalCursorCommit.commitWithLogic` wraps business writes and a cursor UPSERT in one PostgreSQL transaction, so for side effects that live in that same database, the cursor advances if and only if they commit `[mechanism: single SqlConnector.transactional block]`. **It has no production caller at `ccba0dba5`;** a deployed slice cannot reach it through the runtime. This is not an end-to-end exactly-once delivery guarantee: redelivery before the cursor commit is still possible, and effects outside that database are not covered.

---

## 5. Replication and Durability Model (re-verified at `ccba0dba5`)

### 5.1 Owner side

After the WAL gate, `publishLocal` calls `ReplicationManager.replicateEvent`. The node constructs its manager with `ReplicationManager.replicationManager(...)`, which has no batcher, so `DefaultReplicationManager.replicateImmediately` sends one `ReplicateEvents` message per event to every registered non-self replica `[mechanism: AetherNode wires the non-batching factory; batchingReplicationManager has no production caller]`. The 2026-04-11 revision described a 100-event / 1 ms `ReplicationBatcher` on this path. That class exists and is unit-tested, but it is not wired.

- **`min-sync-replicas ≤ 1`.** Publish resolves once the owner's WAL fsync completes (or immediately after the ring append when the node runs without a WAL). Replication is sent but not awaited; the caller is not told whether any replica has the event.
- **`min-sync-replicas ≥ 2`.** Publish additionally awaits `min-sync-replicas − 1` distinct non-self acks (`awaitReplication`). Fewer registered non-self replicas than required fails the await immediately with `NOT_ENOUGH_REPLICAS` rather than acking `[mechanism: DefaultReplicationManager.awaitReplication]`. A failed or timed-out wait does **not** remove the event: it is already in the owner's ring and WAL and continues to replicate, so a caller that retries can publish it twice `[mechanism: publishLocal commits before awaitReplication is called]`.

### 5.2 Replica side

`ReplicationReceiveHandler` applies a batch via `appendRecovered`, which enqueues each record's WAL append through `walReplicated` → `chainWalWrite`, then awaits `syncReplicated` and only then acks the highest applied offset. A failed sync withholds the ack.

`chainWalWrite` chains appends per `(stream, partition)` in `lastReplicatedWalWrite`: each record's `PartitionWal.append` starts only after its predecessor's append has resolved, because unchained appends would race the file order that recovery depends on. A failed append poisons the chain: every later `syncReplicated` fails and that replica stops acking until it is repaired or restarted `[mechanism: previous.flatMap(_ -> wal.append(...)) in chainWalWrite]`.

### 5.3 WAL mechanics that bound throughput

- **Group commit.** `PartitionWal.append` writes under a lock, then `groupCommit` issues one `force(false)` covering every write completed so far, so concurrent appenders to the same WAL can share an fsync. It resolves an append only after a force that happened after its own write.
- **One WAL per partition.** Each `(stream, partition)` has its own file, so fsyncs are never shared across partitions (**analytical**).
- **Owner, single publisher per partition.** `durablyLog` blocks its caller until the fsync, and `DefaultStreamPublisher.publishGroupInOrder` starts each event of a batch only after the previous one resolves. A batch to one partition therefore pays one fsync (plus, for `min-sync-replicas ≥ 2`, one replication round trip including the replica's fsync) **per event, serially**. Upper bound: events/s per partition per publisher ≤ 1 / (owner fsync latency + [replication RTT + replica fsync latency]) (**analytical**). Tracked as #1245.
- **Replica.** Because `chainWalWrite` starts each append after the previous one resolves, group commit never sees more than one pending record per partition: about one fsync per replicated record per partition. Upper bound: durable replicated events/s per partition ≤ 1 / replica fsync latency (**analytical**). Tracked as #1244.
- **Blocking.** The owner's `.await()` holds a thread for the fsync duration; how many publishes can batch into one fsync depends on how many threads are blocked concurrently on the same partition (**analytical**).

None of the latencies in these bounds has been measured for this document.

### 5.4 Pending changes (not shipped at `ccba0dba5`)

The following open tickets change the behaviour described in §3.1, §4.1 and §5.3. **None is merged at `ccba0dba5`.** Until they merge, the text above is what ships; after they merge, this section must be rewritten from the merged code, not from the tickets.

- **#1235** — reads and push notifications expose events before they are WAL-durable or replicated (§3.1, §4.1).
- **#1244** — replica WAL appends are chained one record at a time, defeating group commit (§5.2, §5.3).
- **#1245** — `publishBatch` serialises every event through its own fsync and replication round trip (§5.3).
- **#1236 / #1237** (PR #1257, open) — the outcomes a durable publish reports (§5.1).

---

## 6. Storage Tiers

### 6.1 Hot: OffHeapRingBuffer

*Carried over; not re-verified.* Each event occupies 24 bytes of index plus its payload bytes. For small payloads the index is a material fraction of the footprint: 24 / 124 for a 100-byte payload (**analytical**).

Retention is driven by `RetentionPolicy`: `ANY` evicts when any configured limit (count / bytes / age) is exceeded; `ALL` only when all are (`applyAllModeRetention`); tier-aware retention keeps a window of already-sealed events in the hot tier (`applyTierAwareRetention`).

### 6.2 Cold: SegmentSink implementations

*Carried over; not re-verified.* `StorageSegmentSink` compresses and optionally encrypts before `putRef`; per-segment metadata lets `SegmentReader` invert the transformations. `PgSegmentSink` persists segments in `aether_stream_segments` but has no production caller at `ccba0dba5`.

### 6.3 Segment sealing runs partly on the evicting thread (re-verified)

`OffHeapRingBuffer.notifyAndEvict` calls `SegmentSealer.onEviction` on the thread that triggered eviction. That thread builds and serialises the segment and, in `StorageSegmentSink.seal`, compresses and encrypts it. The sealer then **discards** the `Promise` returned by `sink.seal`, so it does not wait for the storage write `[mechanism: SegmentSealer.onEviction does not use the Promise from sink.seal]`. Whether `putRef` does any blocking I/O before returning its `Promise` was not traced `[design intent — unverified]`. Serialisation, compression and encryption costs therefore land on the append path (**analytical**).

---

## 7. Governor Failover

*Carried over from 2026-04-11; not re-verified at `ccba0dba5`.*

`GovernorFailoverHandler` delegates to `StreamPartitionRecovery`, which uses `WatermarkTracker` to find the most advanced replica. Owner selection is decided outside the stream module.

- Detection speed is governed by the cluster's failure detector (SWIM), an aether-wide property.
- Replay cost is proportional to the events between the best replica watermark and the head. No replay rate has been measured.
- With `min-sync-replicas ≤ 1`, events acked by the old owner but not yet replicated are a loss window if that owner's disk is also lost; its local WAL covers a process crash with the disk intact `[design intent — unverified]`.

---

## 8. Compression & Encryption

*Carried over; not re-verified.* Compression is implemented at the segment sink (`Compression` enum `{NONE, LZ4, ZSTD}` in `integrations/storage/`); hot-tier events are never compressed. Encryption is opt-in per sink via `StorageSegmentSink`; `SegmentReader` uses `"AES/GCM/NoPadding"`. Key management is outside the stream module. Note that `streaming-spec.md` records the `compression` stream key as unwired and rejected by blueprint validation (#576).

---

## 9. Consumer Group Coordination

*Carried over; not re-verified.* `ConsumerGroupCoordinator` is backed by the consensus KV store. Membership and assignment live under KV keys; the coordinator recomputes assignment on join/leave. It is active on the leader and dormant elsewhere, and inherits leader re-election semantics.

---

## 10. Performance Characteristics (qualitative; re-verified at `ccba0dba5`)

No benchmarks for `aether-stream` exist in the repository. This section describes **design properties** only. No comparison with Kafka or any other system is supported by this document.

### What the design avoids

- **The network, when producer and owner share a node.** Owner-local publish is an in-process call, an off-heap copy and a WAL write; remote publish is one QUIC `ProtocolMessage`.
- **Disk reads for recent events.** Hot reads are served from the ring; tier-aware retention keeps sealed events available for rewind.

### What the design does *not* avoid

- **Per-event replication messages.** The production manager sends one `ReplicateEvents` per event; `ReplicationBatcher` is not wired (§5.1).
- **Copies on delivery.** Three `byte[]` copies plus a decode → re-encode → decode round trip per consumed event (§4.3).
- **An fsync per durable publish.** Every acked owner publish on a WAL-backed node waits for a `force(false)` covering its record. Group commit amortises that only across concurrent appenders to the same partition (§5.3).

### Known latency cliffs and risk surfaces

- **Per-partition serial fsync** on the owner for batched publishes and on replicas for every record (§5.3; #1244, #1245).
- **Visibility before durability**: consumers can act on events whose publish later fails (§3.1; #1235).
- **Segment build, compression and encryption on the evicting thread** (§6.3).
- **Cold-tier reads** replay sealed segments. The size of the step has not been measured.
- **Index overhead on small events** — 24 bytes per event (**code constant**).
- **STRONG** is unavailable at `ccba0dba5` (§3.3).

---

## 11. Open Questions / Unmeasured

Each item needs a benchmark harness, which does not exist:

1. Owner-local publish latency, split into ring append, WAL fsync, and (for `min-sync-replicas ≥ 2`) replication wait.
2. EVENTUAL publish throughput per partition, single publisher and concurrent publishers (group-commit effectiveness).
3. Replica durable throughput per partition under `chainWalWrite` (before and after #1244).
4. Remote publish (QUIC forward) round-trip latency.
5. Push-path delivery latency (append → listener → callback), and the cost of the three delivery copies plus the re-encode.
6. Replication message rate and owner CPU per event with the unbatched manager, and the effect of wiring `ReplicationBatcher`.
7. Owner failover recovery time: detection, watermark resolution, replay.
8. Cold-tier read step, and the cost of synchronous segment build/compress/encrypt on append latency.
9. Compression ratio and CPU cost of LZ4 vs ZSTD on representative segments.

### Benchmark acceptance — required before any figure here is labelled (measured)

A throughput or latency figure is admissible only if the same run also checks correctness, so that a faster result cannot hide dropped or overlapping work:

- **Unique offsets.** Every acked publish returned an offset, and no offset was returned twice within a partition.
- **Payload integrity.** Every consumed event's bytes equal the bytes published at that offset (e.g. a per-event checksum carried in the payload).
- **Acknowledged-write survival.** After the run, including any injected crash or restart, every acked event is readable at its acked offset. For `min-sync-replicas ≥ 2`, this must hold after losing the owner.
- **Bounded backlog.** Consumer lag and ring occupancy stay bounded over the measurement window. A run whose backlog grows without bound is measuring the buffer, not the system.

The report must also state the machine, the stream configuration (`partitions`, `replicas`, `min-sync-replicas`, WAL on/off), the commit SHA, and the sample size.

---

## 12. Reference: Key Source Files

| Concern | Path |
|---|---|
| Ring buffer, append/read, listeners, retention | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/OffHeapRingBuffer.java` |
| Publisher (EVENTUAL / STRONG, local / remote, min-sync await) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/DefaultStreamPublisher.java` |
| Partition manager, WAL gate, replica WAL chain | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamPartitionManager.java` |
| Per-partition WAL (group commit, fail-stop, recovery) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/wal/PartitionWal.java` |
| Read routing | `PartitionedStreamAccess.java`, `ForwardingReadRouter.java` |
| Consumer runtime (push + adaptive poll) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/ConsumerRuntimeState.java` |
| Declarative consumer delivery (decode, invoke) | `aether/node/src/main/java/org/pragmatica/aether/node/stream/StreamConsumerManager.java`, `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java` |
| Replication | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/` |
| Segments (seal, read, index) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/segment/` |
| Consensus bridge (STRONG, unwired) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/consensus/` |
| WAL directory boot gate | `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (`resolveStreamWalDir`) |
