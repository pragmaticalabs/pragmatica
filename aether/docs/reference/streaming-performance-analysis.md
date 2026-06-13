# Aether Streams — Architecture & Performance Design Notes

**Status:** Post Phase 2+3 completion (2026-04-11)
**Scope:** Design reference describing the implemented streaming architecture and its performance-relevant properties. This document intentionally avoids quantitative latency/throughput numbers — no benchmarks exist in the repository at the time of writing, so all claims are structural/architectural rather than measured.

Primary implementation lives in `aether/aether-stream/`, with cold-tier storage in `aether/pg-tools/` and codec/storage primitives in `integrations/storage/`.

---

## 1. Feature Inventory (verified against source)

| Feature | Implementation file(s) |
|---|---|
| Off-heap ring buffer (per-partition hot tier) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/OffHeapRingBuffer.java` |
| Batch append | `OffHeapRingBuffer.appendBatch` (line 154) |
| REJECT_WHEN_FULL guard | `OffHeapRingBuffer.append` (line 141), `EvictionPolicy` |
| `MemorySegment` slice read (zero-copy fast path) | `OffHeapRingBuffer.readSlice` / `readSliceAtOffset` (line 330) |
| Append listener (push path for co-located consumers) | `OffHeapRingBuffer` listeners (lines 81, 176, 305) |
| Cross-node publish forwarding over QUIC | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/forward/StreamForwardMessage.java`, `StreamForwardClient`, `StreamForwardHandler` |
| `minSyncReplicas` sync ack | `DefaultStreamPublisher.publishLocalEventual` (lines 188–195) |
| Batch replication (per-partition accumulator) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/ReplicationBatcher.java` (defaults: 100 events / 1 ms, lines 22–24) |
| Read-preference (GOVERNOR / NEAREST / ANY_REPLICA) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/PartitionedStreamAccess.java` (line 256) |
| Consumer group coordination (KV-consensus backed) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/consumer/ConsumerGroupCoordinator.java` |
| Transactional cursor commit (PostgreSQL) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/consumer/PgTransactionalCursorCommit.java` |
| Segment sealer (evicted-events → sealed segment) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/segment/SegmentSealer.java` |
| Segment compression (LZ4 / ZSTD / none) | `integrations/storage/src/main/java/org/pragmatica/storage/Compression.java` wired via `StorageSegmentSink` |
| Segment encryption at rest (AES/GCM) | `StorageSegmentSink` (compress→encrypt→persist), `SegmentReader` (decrypt with `"AES/GCM/NoPadding"`, line 118) |
| Tiered read (ring buffer → sealed segments) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/segment/TieredStreamReader.java` |
| Governor failover handler | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/GovernorFailoverHandler.java` |
| Watermark tracker | `.../replication/WatermarkTracker.java` |
| Compound retention (ANY / ALL modes + tier-aware) | `aether/slice-api/.../RetentionPolicy.java`; enforcement in `OffHeapRingBuffer.applyRetention` |
| Retention enforcer scheduler | `.../segment/RetentionEnforcer.java` |
| Cursor store (cold persistence) | `.../segment/CursorStore.java`, `PgCursorStore` |
| PostgreSQL cold tier | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/pg/PgStreamStore.java` (tables `aether_stream_segments`, `aether_stream_cursors`) |
| STRONG consistency via Rabia consensus | `.../consensus/ConsensusPublishPath.java`, `.../consensus/ConsensusProposer.java` |
| Adaptive poll interval (push-path fallback) | `ConsumerRuntimeState` constants `MIN_POLL_MS = 1`, `MAX_POLL_MS = 50` (lines 34–36) |

### Explicitly not implemented (at time of writing)

- **Cross-node replica reads.** `PartitionedStreamAccess.selectReplicaAndRead` currently *falls back to local read* with a debug log noting "remote forwarding is Phase 2" (see `PartitionedStreamAccess.java:278-279`). ReadPreference today selects a replica logically but serves from the local partition. Remote read forwarding is not yet wired.
- Log compaction (keep-latest-per-key).
- CDC adapter (KV-Store → stream change feed).
- Consumer lag autoscaling.
- Full streaming metrics suite (a `streamMemoryUsedRatio` gauge exists; broader Micrometer coverage is pending).
- Cross-cluster / multi-region stream replication.

---

## 2. Architectural Overview

Streams are modelled as partitioned, append-only logs. Each partition has exactly one governor node (the single writer). A governor is selected via the cluster's DHT ring / task-group assignment; this document does not restate that mechanism, it only notes that the stream module depends on a `governorResolver` (see `DefaultStreamPublisher.resolveForwardClientAndGovernor`, line 205).

**Hot tier.** Events land in an off-heap ring buffer allocated via `Arena.ofShared()` with a fixed layout: 64-byte header, 24-byte index entries, then a circular data region (see `OffHeapRingBuffer.java:22-47`). The 24-byte index entry is a fixed overhead per event and is material for small payloads.

**Cold tier.** When events are evicted from the ring buffer (count/size/age), `SegmentSealer` (wired as an `EvictionListener`) serialises them into a sealed segment and hands them to a `SegmentSink`. A sink can be backed by object storage via `StorageSegmentSink` (with optional compression + encryption) or by PostgreSQL via `PgSegmentSink`. Retention on the cold tier is applied by `RetentionEnforcer`.

**Reads.** `TieredStreamReader` serves hot reads from the ring buffer and falls back to sealed segments for offsets below the buffer tail. Segment reads transparently decrypt and decompress using per-segment metadata (`SegmentReader.java:116-127`).

**Replication.** Governors push events to replicas through `ReplicationBatcher`, which accumulates per-partition batches (default 100 events / 1 ms max delay, `ReplicationBatcher.java:22-24`). `WatermarkTracker` records per-replica progress so that `GovernorFailoverHandler` can resume from the most up-to-date replica on failover.

---

## 3. Publish Paths

### 3.1 EVENTUAL, co-located

Flow (`DefaultStreamPublisher.publishLocalEventual`, line 188):

1. Serialize the event via the configured `Codec`.
2. `StreamPartitionManager.publishLocal` → `OffHeapRingBuffer.append` writes the payload into the ring buffer.
3. If `minSyncReplicas > 0`, the returned `Promise` waits for `awaitReplication` to reach the configured ack count.
4. Append listeners are notified (push path).

Design properties:
- No network hop, no HTTP layer, no JSON.
- Serialization and memory copy into the off-heap segment are unavoidable; the "zero-copy" claim applies only to the *consumer* slice read, not the producer path.
- With `minSyncReplicas = 0`, the publish Promise resolves as soon as the local append and listener notification return.

### 3.2 EVENTUAL, remote (cross-node)

Flow (`DefaultStreamPublisher.publishRemote`, line 197):

1. Resolve the governor node via `governorResolver`.
2. `StreamForwardClient.publishRemote` sends a `PublishForward` `ProtocolMessage` over the cluster's QUIC transport.
3. Governor executes `publishLocal` and returns a `PublishForwardResponse`.

Design properties:
- Binary `ProtocolMessage` (not HTTP), routed directly to the partition governor.
- Adds one network round trip plus serialization/deserialization at each end.
- No silent fallback to local partition.

### 3.3 STRONG (consensus-committed)

Flow (`DefaultStreamPublisher.publishStrong` → `ConsensusPublishPath.publish`, line 28):

1. Wrap the event in a `StreamConsensusCommand`.
2. Submit via `ConsensusProposer` (Rabia). The returned `Promise<Long>` resolves when the command has been committed.
3. The consensus state-machine callback (wired in the node module) performs the actual local append on every node.

Design properties:
- Each `publish(event)` is one consensus proposal. The aether-stream module does **not** itself coalesce multiple STRONG events into one proposal; any batching is whatever Rabia does internally at the consensus layer. `publishBatchStrong` simply calls `publish` per event and gathers results (`DefaultStreamPublisher.publishBatchStrong`, line 163).
- STRONG guarantees total order across all nodes; every node applies the same committed command.

---

## 4. Consumer Paths

### 4.1 Push path (co-located, append listener)

`ConsumerRuntimeState.subscribePushOrPoll` wires a `LongConsumer` onto the ring buffer's append listeners (`OffHeapRingBuffer.java:176`). When an append occurs, the listener is invoked synchronously from the append thread, which then reads and dispatches to the consumer callback. This avoids any polling delay when a consumer is co-located with the partition governor and the buffer is not empty.

### 4.2 Adaptive poll fallback

When push is unavailable (e.g. remote read, replica catch-up), `ConsumerRuntimeState` uses an adaptive poll interval bounded by `MIN_POLL_MS = 1` and `MAX_POLL_MS = 50` (lines 34–36). Poll batch size is bounded by `MAX_POLL_BATCH = 100` (line 38).

### 4.3 Zero-copy read fast path

`OffHeapRingBuffer.readSliceAtOffset` returns a `MemorySegment` slice directly into the ring buffer's arena when the event does not wrap the circular boundary (`readSliceAtOffset`, line 336: `segment.asSlice(...)`). When the stored bytes wrap, a contiguous copy is made (`copyWrappedToContiguous`, line 340). Consumers that deserialize directly from `MemorySegment` avoid the `byte[]` allocation on the non-wrapping path; consumers using `read(...)` still receive a `byte[]` copy via `RawEvent`.

### 4.4 Read-preference — current behaviour

`PartitionedStreamAccess.readWithPreference` dispatches to:
- `GOVERNOR` → read the local partition buffer.
- `ANY_REPLICA` / `NEAREST` → `readFromReplicaOrLocal` → `selectReplicaAndRead`, which *logs* the selected replica and then **falls back to `readPartition` (local)** (see `PartitionedStreamAccess.java:268-283`). The debug log at line 278 explicitly notes "remote forwarding is Phase 2".

**Consequence for this document:** any claim that read-preference scales reads by routing to remote replicas is currently aspirational. The wiring for remote replica reads is not in place.

### 4.5 Transactional cursor commit

`PgTransactionalCursorCommit.commitWithLogic` wraps business-logic writes and a cursor UPSERT in a single `SqlConnector.transactional` block against PostgreSQL. The UPSERT uses `ON CONFLICT (consumer_group, stream_name, partition_id) DO UPDATE` (`PgTransactionalCursorCommit.java:18`). Atomicity of business writes and cursor advancement is delegated to the PostgreSQL transaction, supporting exactly-once processing for workloads whose side effects live in the same database.

---

## 5. Replication Model

`ReplicationBatcher.add` routes each appended event into a per-partition `BatchAccumulator`. The accumulator flushes when it hits `maxEvents` (default 100) or on the periodic scheduled flush with `maxDelay` (default 1 ms). Flushes send a `ReplicateEvents` message to every replica registered for the partition via `ReplicationTransport.send` (`ReplicationBatcher.java:91`).

- **Async replication.** Publish resolves as soon as the local append completes; the batcher dispatches on its own cadence.
- **Sync replication.** If the publisher is configured with `minSyncReplicas > 0`, `publishLocalEventual` chains `awaitReplication(...)` on the append, so the Promise completes only after that many replica acks.
- **Consistency with consensus.** STRONG publishes bypass the batcher; the consensus state machine applies the commit locally on every node (no separate replication step).

**Bandwidth behaviour.** Batching amortises per-message overhead but does not reduce total bytes on the wire. Replication fan-out at high event rates is bandwidth-bound, not count-bound. The practical replication ceiling is `NIC_bandwidth / (event_rate * event_size)` minus overhead; that formula is stated without a measured constant because no throughput benchmark exists.

---

## 6. Storage Tiers

### 6.1 Hot: OffHeapRingBuffer

Fixed per-partition budget configured by `(capacity, dataRegionSize)`. Each event occupies 24 bytes of index plus its payload bytes inside the data region. For small payloads the index overhead is a non-trivial fraction of the total footprint (e.g. 24 / 124 for a 100-byte payload); for large payloads it is negligible.

Retention is driven by `RetentionPolicy`:
- `ANY` mode: evict when any single limit (count / bytes / age) is exceeded.
- `ALL` mode: evict only when all configured limits are exceeded simultaneously (`OffHeapRingBuffer.applyAllModeRetention`, line 250).
- Tier-aware retention (`TierAwareRetention`) retains a configurable window of already-sealed events in the hot tier for fast rewind (`applyTierAwareRetention`, line 277).

### 6.2 Cold: SegmentSink implementations

- **`StorageSegmentSink`** compresses (`CompressionCodec`) and optionally encrypts (`ContentEncryptor` with AES/GCM) before writing to a storage backend (`StorageSegmentSink.java:55-95`). Per-segment metadata (compression ordinal, `encrypted` flag, original size, IV) is stored in `SegmentIndex` so readers can invert the transformations (`SegmentReader.decrypt` / `decompress`, lines 114–129).
- **`PgSegmentSink`** persists segments as raw bytes in the `aether_stream_segments` PostgreSQL table keyed by `(stream_name, partition_id, start_offset)` (`PgStreamStore.java:39-43`). A `DELETE_EXPIRED` statement supports time-based cleanup.

### 6.3 Segment sealing is synchronous

`SegmentSealer.onEviction` is invoked directly from `OffHeapRingBuffer.notifyAndEvict` (`OffHeapRingBuffer.java:470-478` → `SegmentSealer.java:28`). The call to `SegmentSink.seal` therefore executes on the append thread that triggered the eviction. If the configured sink is slow (network storage, busy database), a hot append can be delayed until the seal completes. This is a real risk surface worth noting; there is no built-in async offloading inside the sealer.

---

## 7. Governor Failover

`GovernorFailoverHandler` is a sealed interface with a default implementation that delegates to `StreamPartitionRecovery`. The recovery path uses `WatermarkTracker` to find the most advanced replica watermark and replays sealed segments from that offset into a new ring buffer. The governor selection itself is determined by the cluster's DHT / task-group assignment (outside the stream module); the stream module inherits whatever the cluster decides.

What this means in practice:
- Detection speed is governed by the cluster's failure detector (SWIM). That number is an aether-wide property, not a streams-specific one.
- Replay cost is proportional to the number of events between the best replica watermark and the current head, read from the configured cold tier. No quantitative replay rate exists in this repository.
- Events that were accepted by the old governor but never replicated (when `minSyncReplicas = 0`) are a potential data-loss window. `minSyncReplicas > 0` trades latency for a tighter bound on that window.

---

## 8. Compression & Encryption

- **Compression** is opt-in per stream and implemented at the segment sink level. The codecs live in `integrations/storage/`: `Lz4Codec`, `ZstdCodec`, `NoOpCodec`; `Compression` is a closed enum `{NONE, LZ4, ZSTD}` (`Compression.java:4-14`). Hot-tier events are never compressed — compression only affects sealed segments going to the cold tier.
- **Encryption** is opt-in per sink. `StorageSegmentSink` encrypts after compression using an injected `ContentEncryptor`; `SegmentReader` reverses this with the IV and length prefix embedded in the segment bytes. The cipher parameter string in `SegmentReader.java:118` is `"AES/GCM/NoPadding"`. Keys and key management are outside the stream module.

---

## 9. Consumer Group Coordination

`ConsumerGroupCoordinator` is a sealed interface with a `DefaultConsumerGroupCoordinator` backed by the cluster's consensus KV store (`ClusterNode<KVCommand<AetherKey>>`). Membership and assignment live under KV keys; on join/leave, the coordinator recomputes partition assignment for the group and writes the new assignment back through KV commands (`ConsumerGroupCoordinator.java:105-171`). Rebalancing is round-based on member lists rather than per-partition streaming.

Design properties:
- Assignment state is reconstructible from KV at any time.
- Coordinator is dormant on non-leader nodes and active on the leader (standard aether leader-scoped pattern).
- Failure and recovery of the coordinator inherit the cluster's leader re-election semantics.

---

## 10. Performance Characteristics (qualitative)

No benchmarks for aether-stream exist in this repository at the time of writing (verified by searching for `*Benchmark*`, `*Perf*`, and JMH harnesses under `aether/` — none found). This section describes **design properties** only. Any numeric comparison to Kafka or other systems requires measurements that do not yet exist.

### What the design is optimised for

- **Avoiding the network in the common case.** Co-located publish is a single in-process call plus an off-heap write; remote publish is one QUIC `ProtocolMessage` rather than an HTTP request.
- **Avoiding allocation in the consumer fast path.** `readSlice` returns a `MemorySegment` directly into the ring buffer arena when the event does not wrap the circular data region.
- **Amortising replication cost.** `ReplicationBatcher` coalesces up to 100 events / 1 ms before sending, reducing per-event QUIC frame overhead on the wire and governor CPU per send.
- **Keeping hot reads off disk.** Retention on the ring buffer is tuneable; tier-aware retention keeps already-sealed events around for rewind without hitting the cold tier.
- **Mixed-consistency from the same API.** A single `StreamPublisher<T>` exposes both EVENTUAL and STRONG through a configuration flag, with the STRONG path routed through Rabia consensus inside the node.

### Known latency cliffs and risk surfaces

- **Synchronous segment sealing** blocks the append thread during eviction. Slow sinks (remote object storage, busy PostgreSQL) will backpressure publishes.
- **Cold-tier reads** replay sealed segments and are dramatically slower than hot reads (disk / PostgreSQL vs. off-heap). There is no production benchmark for the size of the step.
- **Read-preference cross-node reads are not yet implemented.** Setting `NEAREST` or `ANY_REPLICA` selects a replica logically but still serves from the local partition. Treat as a future optimisation, not a current property.
- **STRONG throughput is proposal-bound.** Each event becomes one consensus proposal at the publisher layer; any amortisation depends on Rabia's internal behaviour, which lives outside the stream module.
- **Index overhead on small events.** The 24-byte index entry is a non-trivial fraction of the effective footprint for sub-kilobyte payloads and should be accounted for when sizing `dataRegionSize`.

### Known structural gaps vs. general-purpose streaming systems

- Log compaction, CDC, consumer lag autoscaling, full metrics, and cross-cluster replication are not in this release (see §1 "Explicitly not implemented").
- JVM-only client surface.
- No external schema registry, connector framework, or cross-language client ecosystem.

---

## 11. Open Questions / Unmeasured

The following would require a benchmark harness (not present) to make any quantitative statement:

1. **End-to-end co-located publish latency** for realistic event sizes and serializers.
2. **EVENTUAL publish throughput per partition** on the current `OffHeapRingBuffer` implementation, including index-overhead effects.
3. **Remote publish (QUIC forward) round-trip latency** in the current transport and the overhead of the `PublishForward` / `PublishForwardResponse` codec path.
4. **Push-path consumer delivery latency** (append → listener → callback) including the cost of notification under load.
5. **Replication batcher effectiveness** under bursty producer patterns (actual flush sizes, CPU cost).
6. **Sync replication latency distribution** as a function of `minSyncReplicas` and cluster size.
7. **Governor failover recovery time**, broken into detection, watermark resolution, and segment replay.
8. **STRONG publish latency and throughput** — both directly (per-event Rabia proposal) and for `publishBatchStrong`, which currently fans out `n` independent proposals rather than coalescing them.
9. **Cold-tier read step change** between hot buffer reads and sealed-segment reads, for object-storage and PostgreSQL sinks.
10. **Impact of synchronous segment sealing** on append latency when the cold sink is slow or temporarily unavailable.
11. **Compression ratio and CPU cost** of LZ4 vs. ZSTD on representative segment payloads.

Any performance comparison against other streaming systems should be deferred until the above are measured on this codebase; the architecture supports several claims that are plausible but not currently verifiable from source alone.

---

## 12. Reference: Key Source Files

| Concern | Path |
|---|---|
| Ring buffer, append/read, listeners, retention | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/OffHeapRingBuffer.java` |
| Publisher (EVENTUAL / STRONG, local / remote, sync ack) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/DefaultStreamPublisher.java` |
| Partition manager and read routing | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/StreamPartitionManager.java`, `PartitionedStreamAccess.java` |
| Consumer runtime (push + adaptive poll) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/ConsumerRuntimeState.java`, `StreamConsumerRuntime.java` |
| Consumer groups | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/consumer/ConsumerGroupCoordinator.java`, `ConsumerGroupRegistry.java` |
| Transactional cursor | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/consumer/PgTransactionalCursorCommit.java` |
| Cross-node forwarding | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/forward/` |
| Replication | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/` |
| Segments (hot → cold seal, read, index) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/segment/` |
| PostgreSQL cold tier | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/pg/` |
| Consensus bridge (STRONG) | `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/consensus/` |
| Compression codecs | `integrations/storage/src/main/java/org/pragmatica/storage/Compression.java`, `Lz4Codec.java`, `ZstdCodec.java` |
