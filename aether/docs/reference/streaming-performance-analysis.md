# Aether Streams — Architecture & Performance Design Notes

**Status:** Re-verified against source at `ccba0dba5` on 2026-09-19 (#1248) for §1, §3, §4, §5, §6.3, §10 and §11; §1 (table), §2 (routing paragraph), §3.1, §3.3, §4.1, §5.1, §5.4 and §10 (risk list) re-verified at `f802f4153` on 2026-09-20 (#1360) against the merges §5.4 now lists. §2, §6.1–6.2, §7, §8 and §9 carry over from the 2026-04-11 original and were **not** re-verified at that read point; each says so.
**Measurements:** **None.** This document contains no measured latency, throughput or recovery figure. No benchmark harness for `aether-stream` exists in the repository (searched `git ls-files aether` for `bench|perf|jmh` at `ccba0dba5`; the one code hit, `ObservabilityStep0BenchTest` in `aether/node`, is not a stream benchmark). The integration suite's `04-streaming/test-stream-under-load.sh` exercises streams under load but records no figure used here. Every statement is structural (read from source) or analytical (derived from structure).
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
| Per-partition crash-durable WAL (owner) | `StreamPartitionManager.logAndReplicate` → `PartitionWal.write` + `commit` inside the append section (frame write, group commit started, no fsync); `awaitDurable` awaits the group-commit `force(false)` outside it |
| Replica WAL frame write + ack barrier | `StreamPartitionManager.appendRecovered` → `logReplicated` (the frame is written inside the partition's ordered append section, with no fsync); barrier `syncReplicated` → one `PartitionWal.commit` group commit covering every frame written so far, awaited by `ReplicationReceiveHandler` once per batch before acking (#1244) |
| Cross-node publish forwarding over QUIC | `stream/forward/`: `StreamForwardMessage`, `StreamForwardClient`, `StreamForwardHandler` |
| `min-sync-replicas` write-ack floor | `DefaultStreamPublisher.publishLocalEventual`, `PartitionedStreamAccess.publishLocal`, `StreamWriteRouter.publishLocal`, `StreamForwardHandler` → `publishLocalAtFloor(..., minSyncReplicas - 1)` (the pre-append replica floor, #1236) then `awaitReplication(..., minSyncReplicas - 1)`; a barrier that does not confirm maps to `PublishOutcomeUnknown` (#1257). The former `StreamWriteRouter` fallback that appended without the await is gone: its `publishLocal` runs the same floor and barrier (#1290). |
| Owner → replica push | `DefaultReplicationManager.replicateEvent`. The node wires the **non-batching** manager (`ReplicationManager.replicationManager(...)` in `AetherNode`), so each event is sent by `replicateImmediately`. `ReplicationBatcher` (`DEFAULT_MAX_EVENTS = 100`, `DEFAULT_MAX_DELAY = 1 ms`, **code constants**) exists but `batchingReplicationManager` has no production caller. |
| Read routing (GOVERNOR / NEAREST / ANY_REPLICA / LINEARIZABLE) | `PartitionedStreamAccess.readWithPreference` → `ForwardingReadRouter` |
| Consumer group coordination (KV-consensus backed) | `consumer/ConsumerGroupCoordinator` |
| Transactional cursor commit (PostgreSQL) | `consumer/PgTransactionalCursorCommit` — **library only: no production caller** |
| Segment sealer (evicted events → sealed segment) | `segment/SegmentSealer` |
| Segment compression / encryption | `StorageSegmentSink` supports compress → encrypt → `putRef`; the node wires it with neither (`CompressionCodec.NONE`, no encryptor). `SegmentReader` inverts both. |
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

Streams are modelled as partitioned, append-only logs, each partition with one intended writer, its owner. App EVENTUAL publishes (`DefaultStreamPublisher.publishEventual`, re-verified at `f802f4153`) route by **authority, never by ring presence** (#1230, merged as #1290): `resolveOwner` asks the partition-aware HRW resolver first and the arg-less governor resolver only when no HRW resolver is wired; an owner known to differ from this node is write-forwarded, otherwise the publish appends locally through `publishLocalAtFloor`. The local append is admitted only for the committed owner `[mechanism: admitOwnerWrite → OwnerWriteAdmission.remoteCommittedOwner refuses with StreamError.NotOwnerAppend, which StreamForwardRetry.redirectNotOwner forwards to that owner]`, so a replica holding a materialised ring is no longer a second writer. The epoch fence runs before admission `[mechanism: appendToPartition: ensureNotStale, then admission]`, so a deposed writer is told it is deposed (`StaleEpochAppend`, permanent) rather than redirected (`NotOwnerAppend`, transient).

**Hot tier.** Events land in an off-heap ring buffer allocated via `Arena.ofShared()`: a 64-byte header (`HEADER_SIZE`, **code constant**), 24-byte index entries (`INDEX_ENTRY_SIZE`, **code constant**), then a data region grown in segments up to the stream's cap.

**Durable log.** On a node with a writable WAL directory, each partition also has a `PartitionWal`. An owner publish is not acknowledged until its record is fsynced there (§3.1, §5). A node whose WAL directory is unwritable refuses to boot unless `aether.allowNonDurableStreams=true` / `AETHER_ALLOW_NON_DURABLE_STREAMS=true` is set. The refusal is `AetherNode.verifyWalBootable` → `decideWalAvailability`, called from the production entrypoint (`Main`) only. Forge and embedded nodes skip it and degrade via `resolveStreamWalDir` with a WARN. Without a WAL, publishes ack with no fsync `[mechanism: writeWalFrame returns a LoggedAppend whose barrier is already resolved when walFor is empty, so awaitDurable passes the offset through]`.

**Cold tier.** Evicted events are serialised into a sealed segment by `SegmentSealer` and handed to a `SegmentSink`. The node wires `StorageSegmentSink` through its two-argument factory: `CompressionCodec.NONE` and no encryptor (re-verified). `PgSegmentSink` exists but has no production caller.

**Reads.** `TieredStreamReader` serves hot reads from the ring and falls back to sealed segments below the ring tail.

**Replication.** Owners push each event to its replicas as it is published (§5.1; re-verified). `WatermarkTracker` records per-replica progress for failover.

---

## 3. Publish Paths (re-verified at `ccba0dba5`)

### 3.1 EVENTUAL, owner-local

Flow (`DefaultStreamPublisher.publishLocalEventual` → `StreamPartitionManager.publishLocalAtFloor` → `publishLocal`):

1. Serialize the event via the configured codec (one `byte[]`).
2. Pre-checks, before the partition's ordered section: the epoch fence (`ensureNotStale`), owner admission (`admitOwnerWrite`, #1230) and the replica floor (`ensureReplicaFloor`, #1236: fewer than `minSyncReplicas − 1` in-sync peers refuses with `NOT_ENOUGH_REPLICAS`, and nothing is appended).
3. `publishInSection` → `appendToPartition` → `OffHeapRingBuffer.appendOrdered`: copy the payload into the off-heap data region and write the index entry under the ring's append lock. **No listener fires here** (#1235, §4.1).
4. `logAndReplicate`, still inside the section: if the partition has a WAL, `PartitionWal.write(offset, payload, timestamp)` writes the frame and `commit(writeSeq)` starts its group commit (no fsync yet); then `ReplicationManager.replicateEvent` sends the event to the replicas. With no WAL the barrier is already resolved. A failed frame write fails the publish and sends nothing.
5. `awaitDurable`, outside the section: **block the calling thread** (`.await()`, a `@TerminalOperation`) until the group-commit fsync covering that record completes; then `ownerDurable` marks the ring durable and refreshes the visible watermark.
6. If `min-sync-replicas ≥ 2`, the returned `Promise` additionally awaits `awaitReplication(..., minSyncReplicas - 1)`: that many distinct non-self replica acks, each sent only after that replica's own WAL sync (§5). A barrier that fails or times out after the append is reported as `PublishOutcomeUnknown` (#1236: the event may already be in the log), never as a clean failure. With `min-sync-replicas ≤ 1` there is no peer-ack wait.

Consequences:

- A successful owner-local publish means the record was fsynced to the owner's WAL `[mechanism: publishLocal chains awaitDurable after publishInSection; the LoggedAppend barrier from PartitionWal.commit resolves only after force(false) covering its record]`, and for `min-sync-replicas ≥ 2`, that `min-sync-replicas − 1` replicas reported their WAL sync for it `[mechanism: ReplicationReceiveHandler acks only on durability.sync success]`. Neither has been exercised by a multi-node crash test for this document.
- **Visibility follows durability and the min-sync acks** (#1235, merged as #1309). The ring keeps three positions — appended, durable, visible — and reads and push notifications are bounded by *visible* `[mechanism: OffHeapRingBuffer.read reads up to visibleOffset; appendOrdered notifies no listener; StreamPartitionManager.refreshVisible advances visible to min(durableOffset, replicatedThrough(minSyncReplicas − 1)) after the owner's fsync and on each replica ack; listeners learn the new visible offset on the ring's serial notifier thread]`. A co-located consumer therefore cannot read, or be notified of, an event whose publish then fails on fsync or replication. With `min-sync-replicas ≤ 1` visible advances at the owner's fsync `[mechanism: replicatedThrough(..., 0) is Long.MAX_VALUE]`, or at append when there is no WAL.
- Copies on this path: the codec's `byte[]`, the copy into the off-heap ring, and the WAL write (**analytical**). The producer path is not zero-copy and does not claim to be.
- Recovery action when the WAL fail-stops (a failed fsync): every later append on that partition is refused with `WalError.FailStopped`. Restart the node, and recovery trims to the valid prefix. Nothing acked is lost, because refused appends were never acked `[mechanism: a failed force sets PartitionWal.syncFailure; append refuses while it is set]`.

### 3.2 EVENTUAL, remote (cross-node)

Flow (`DefaultStreamPublisher.publishRemote`):

1. Resolve the partition owner.
2. `StreamForwardClient` sends a `PublishForward` `ProtocolMessage` over QUIC.
3. The owner's `StreamForwardHandler` runs `publishLocal` (§3.1 steps 2–4) and, for `min-sync-replicas ≥ 2`, `awaitReplication`, then replies.

Adds one network round trip plus serialization/deserialization at each end (**analytical**).

### 3.3 STRONG (consensus) — implemented, not wired

`DefaultStreamPublisher.publishStrong` requires a `ConsensusPublishPath`. Every production construction of a publisher (`StreamPublisherFactory`, `SystemStreamFactories`, `AetherNode`) passes `Option.none()` for it, and `ConsensusPublishPath.consensusPublishPath(...)` is called only from tests. A STRONG publish through a `StreamPublisher` therefore fails with `CONSENSUS_PATH_UNAVAILABLE` `[mechanism: consensusPath.async(StreamError.General.CONSENSUS_PATH_UNAVAILABLE)]`. Every write entry point — `DefaultStreamPublisher.publishEventual`, `PartitionedStreamAccess.publish`, the REST route (`StreamWriteRouter.publish`) and the owner side of a forwarded publish — refuses a stream whose committed consistency is `STRONG` with the same `CONSENSUS_PATH_UNAVAILABLE`, and an `UNKNOWN` mode with `UNREADABLE_CONSISTENCY_MODE`, before routing `[mechanism: StreamPartitionManager.ensureWritableConsistency, called by each entry point and re-checked by publishForwarded on both attempts]` (#1262, merged as #1301); `StreamResourceValidator` rejects a STRONG declaration at deploy time first. **No cross-node total-order guarantee is available at `f802f4153`; STRONG fails closed rather than degrading to EVENTUAL.** When wired, `publishBatchStrong` issues one proposal per event (`Promise.allOf` over `publish`).

---

## 4. Consumer Paths (re-verified at `ccba0dba5`)

### 4.1 Push path (co-located, append listener)

`ConsumerRuntimeState.subscribePushOrPoll` registers a `LongConsumer` on the ring's append listeners when the partition is local, and polls otherwise. The listener fires when the ring's **visible** watermark advances (#1235, §3.1) — after the owner's fsync and, for `min-sync-replicas ≥ 2`, the peer acks — on the ring's serial notifier thread, never on the publisher's thread or under the append lock. It only marks the consumer dirty (`onAppend` → `requestDrain`); the delivery pass (`pollCycle`, up to `MAX_POLL_BATCH` events from the cursor) is dispatched through `SharedScheduler`, single-flight per `(group, partition)`, and is kicked once at install so events already in the ring are delivered without waiting for the next append (#1238, merged as #1285). The publisher's thread therefore pays for neither the decode nor the consumer invocation (**analytical**; not measured).

### 4.2 Adaptive poll fallback

When the partition is not local, `ConsumerRuntimeState` polls with an interval bounded by `MIN_POLL_MS = 1` and `MAX_POLL_MS = 50`, batch `MAX_POLL_BATCH = 100` (**code constants**).

### 4.3 Delivery copies — there is no zero-copy read

Storage is off-heap; **delivery copies to heap.** When the consumer runs on the node that holds the ring, the declarative-consumer path makes three `byte[]` copies per event and then a decode → re-encode → decode round trip `[mechanism: traced below at ccba0dba5]`:

1. `OffHeapRingBuffer.readDataBytes` — `new byte[dataLen]`, filled from the off-heap data region (called by `readSingleEvent`).
2. The `RawEvent` compact constructor — `data = data.clone()`.
3. The `RawEvent.data()` accessor — a second `clone()`, called by `ConsumerRuntimeState.deliverSingleEvent`.
4. `StreamConsumerManager.deliver` → `bridge.decode(payload)` into the subscriber's type, then `SliceInvoker.invokeLocal` → `invokeViaBridge`, which **re-encodes** the decoded object (`senderBridge.encode`) for the target slice to decode again (`targetBridge.invoke`).

`OffHeapRingBuffer.readSlice` does not avoid this: `readSliceAtOffset` returns `MemorySegment.ofArray(readDataBytes(...))`, a heap segment over copy 1, and has no production caller. The `StreamConsumerAdapter` that once advertised zero-copy reads was dead code and was deleted in #577.

When the assigned consumer does **not** hold the ring, the node's consumer reader (`streamReadRouter.read(..., GOVERNOR)`) forwards the read to the owner. The copies then roughly double: copies 1–3 plus a `RawEventDto` clone on the owner (`StreamForwardHandler`), the wire encode/decode, and on the consumer side a `RawEventDto` clone, a `dto.data()` clone (`StreamReadRouter.toRawEvent`), and the `RawEvent` constructor and accessor clones, about eight array copies in all. Durable-topic streams add a `TopicEventEnvelope` decode (`StreamConsumerManager.deliverTopicEvent`).

Allocation per delivered event is therefore at least three payload-sized `byte[]` arrays (local ring) or about eight plus the wire buffers (forwarded read), plus the decoded object twice (**analytical**).

### 4.4 Read routing

`PartitionedStreamAccess.readWithPreference` delegates to `ForwardingReadRouter.route`:

- `GOVERNOR` reads locally and forwards to the owner on `PARTITION_NOT_LOCAL`.
- `NEAREST` serves any non-empty local read and otherwise goes to the owner.
- `ANY_REPLICA` prefers a caught-up remote replica, even when this node is itself caught up.
- `LINEARIZABLE` runs the committed-owner pipeline. It degrades to the replica-routed read when its components are unwired or no committed ownership record exists. The 2026-04-11 statement that non-GOVERNOR preferences always fall back to a local read is no longer true.

### 4.5 Transactional cursor commit

`PgTransactionalCursorCommit.commitWithLogic` wraps business writes and a cursor UPSERT in one PostgreSQL transaction, so for side effects that live in that same database, the cursor advances if and only if they commit `[mechanism: single SqlConnector.transactional block]`. **It has no production caller at `ccba0dba5`;** a deployed slice cannot reach it through the runtime. This is not an end-to-end exactly-once delivery guarantee: redelivery before the cursor commit is still possible, and effects outside that database are not covered.

---

## 5. Replication and Durability Model (re-verified at `ccba0dba5`)

### 5.1 Owner side

`logAndReplicate` calls `ReplicationManager.replicateEvent` inside the partition's ordered section, right after the WAL frame write and **before** the owner's fsync (§3.1), so replicas receive events in offset order and the replica fsync overlaps the owner's. The node constructs its manager with `ReplicationManager.replicationManager(...)`, which has no batcher, so `DefaultReplicationManager.replicateImmediately` sends one `ReplicateEvents` message per event to every registered non-self replica `[mechanism: AetherNode wires the non-batching factory; batchingReplicationManager has no production caller]`. The 2026-04-11 revision described a 100-event / 1 ms `ReplicationBatcher` on this path. That class exists and is unit-tested, but it is not wired.

- **`min-sync-replicas ≤ 1`.** Publish resolves once the owner's WAL fsync completes (or immediately after the ring append when the node runs without a WAL). Replication is sent but not awaited; the caller is not told whether any replica has the event.
- **`min-sync-replicas ≥ 2`.** Every write path — `DefaultStreamPublisher`, `PartitionedStreamAccess`, `StreamWriteRouter` and the owner side of a forwarded publish — checks the replica floor before the append `[mechanism: publishLocalAtFloor → ensureReplicaFloor; fewer registered in-sync non-self replicas than minSyncReplicas − 1 refuses with NOT_ENOUGH_REPLICAS and leaves nothing in the ring or the WAL]` (#1236), then awaits `min-sync-replicas − 1` distinct non-self acks (`awaitReplication`). The former `StreamWriteRouter` fallback bypass is gone (#1290). A failed or timed-out barrier does **not** remove the event: it is already in the owner's ring and WAL and continues to replicate, so it is reported as `PublishOutcomeUnknown` — retry only with the same message ID (#1237's caller-stable ID) `[mechanism: awaitMinSync .mapError(PublishOutcomeUnknown.FACTORY) at each entry point; StreamForwardHandler answers outcomeUnknownResponse so a forwarding publisher sees the same class]`.

### 5.2 Replica side

`ReplicationReceiveHandler` applies a batch via `appendRecovered`, which writes each record's WAL frame inside the partition's ordered append section — on the receiving thread, with no fsync — then awaits `syncReplicated` once for the batch and only then acks the highest applied offset. A failed sync withholds the ack.

`syncReplicated` is the durability barrier (#1244). `lastReplicatedWalWrite` records, per `(stream, partition)`, the latest frame write (its WAL instance and write sequence), and the barrier issues one `PartitionWal.commit` for it: a `force(false)` that covers every frame written before it, so a batch of N records costs one fsync, not N `[mechanism: logReplicated → PartitionWal.write inside the section; ReplicatedWrite.commit → PartitionWal.commit(writeSeq); pinned in one JVM by ReplicaWalGroupCommitTest]`. File order is offset order because the replica's frame write shares the owner's section `[mechanism: appendReplicatedInSection; pinned under 16 concurrent appenders by StreamPartitionManagerOrderedAppendTest.appendRecovered_walFileOrderEqualsOffsetOrder_underConcurrentAppends]`. A failed frame write or fsync fail-stops the WAL: the recorded write holds the failure, the barrier fails, and every later write on that WAL instance is refused, so the replica stops acking until the node restarts and reopen recovers the valid prefix `[mechanism: PartitionWal fail-stop; StreamPartitionManager.ReplicatedWrite]`. Releasing a partition (role loss, destroy, idle reap) closes its WAL and only then forgets its entry, so a barrier racing the release meets the closed channel and fails rather than resolving without an fsync `[mechanism: releaseEntry / completeRelease order; StreamPartitionManagerWalTest.syncReplicated_racingTheRelease_neverResolvesBeforeTheClosingFsync]`.

### 5.3 WAL mechanics that bound throughput

- **Group commit.** `PartitionWal.append` writes under a lock, then `groupCommit` issues one `force(false)` covering every write completed so far, so concurrent appenders to the same WAL can share an fsync. It resolves an append only after a force that happened after its own write.
- **One WAL per partition.** Each `(stream, partition)` has its own file, so fsyncs are never shared across partitions (**analytical**).
- **Owner, single publisher per partition.** `awaitDurable` blocks its caller until the fsync, and `DefaultStreamPublisher.publishGroupInOrder` starts each event of a batch only after the previous one resolves. A batch to one partition therefore pays one fsync (plus, for `min-sync-replicas ≥ 2`, one replication round trip including the replica's fsync) **per event, serially**. Upper bound: events/s per partition per publisher ≤ 1 / (owner fsync latency + [replication RTT + replica fsync latency]) (**analytical**). Tracked as #1245.
- **Replica.** Frames are written without an fsync and the barrier commits them together, so a replicated batch to one partition pays one fsync per `syncReplicated` — one per received batch and one per backfill run — whatever its record count (#1244). Concurrent batches to the same partition can share a force, because `commit` resolves on any force that happened after the record's own write. Upper bound: durable replicated batches/s per partition ≤ 1 / replica fsync latency, with records/s scaling with batch size (**analytical**). The owner sends one event per message (§5.1), so on the live replication path a batch is one record and the bound stays one record per fsync per partition until owner-side batching is wired; backfill batches are where the group commit pays today (**analytical**).
- **Blocking.** The owner's `.await()` holds a thread for the fsync duration; how many publishes can batch into one fsync depends on how many threads are blocked concurrently on the same partition (**analytical**).

None of the latencies in these bounds has been measured for this document.

### 5.4 Pending changes (not shipped at `f802f4153`)

The following open tickets change the behaviour described in the sections they cite. **Neither is merged at `f802f4153`.** Until they merge, the text above is what ships; after they merge, this section must be rewritten from the merged code, not from the tickets.

- **#1245** — `publishBatch` serialises every event through its own fsync and replication round trip (§5.3).
- **#1261** — sealed-segment codec metadata and raw-bytes fallbacks (§6.3). Latent.

**Merged since `ccba0dba5`** (each named section was rewritten from the merged code): #1244 (PR #1277) — replica WAL group commit (§1, §5.2, §5.3, §11); #1230 (PR #1290) — writes routed by committed ownership, replicas refuse application writes, the REST fallback bypass removed (§2, §3.1, §5.1); #1235 (PR #1309) — reads and push notifications bounded by the visible watermark (§3.1, §4.1); #1236 / #1237 (PR #1257) — `PublishOutcomeUnknown` and the caller-stable message ID (§3.1, §5.1); #1238 (PR #1285) — serial single-flight consumer delivery (§4.1); #1262 (PR #1301) — STRONG and UNKNOWN fail closed on every write path (§3.3).

---

## 6. Storage Tiers

### 6.1 Hot: OffHeapRingBuffer

*Carried over; not re-verified.* Each event occupies 24 bytes of index plus its payload bytes. For small payloads the index is a material fraction of the footprint: 24 / 124 for a 100-byte payload (**analytical**).

Retention is driven by `RetentionPolicy`: `ANY` evicts when any configured limit (count / bytes / age) is exceeded; `ALL` only when all are (`applyAllModeRetention`); tier-aware retention keeps a window of already-sealed events in the hot tier (`applyTierAwareRetention`).

### 6.2 Cold: SegmentSink implementations

*Carried over; not re-verified except as noted.* `StorageSegmentSink` can compress and encrypt before `putRef`, though the node's instance does neither (re-verified, §2); per-segment metadata lets `SegmentReader` invert the transformations. `PgSegmentSink` persists segments in `aether_stream_segments` but has no production caller at `ccba0dba5`.

### 6.3 Segment sealing runs partly on the evicting thread (re-verified)

`OffHeapRingBuffer.notifyAndEvict` calls `SegmentSealer.onEviction` on the thread that triggered eviction. That thread builds and serialises the segment and calls `StorageSegmentSink.seal`. The node's sink neither compresses nor encrypts (§2), so `seal` goes straight to `putRef`. The sealer then **discards** the `Promise` returned by `sink.seal`, so it does not wait for the storage write `[mechanism: SegmentSealer.onEviction does not use the Promise from sink.seal]`. Any at-rest encryption happens below `putRef`, in the storage tier. Which thread runs it, and whether `putRef` blocks before returning its `Promise`, was not traced `[design intent — unverified]`. Segment serialisation therefore lands on the append path (**analytical**).

A sink built with an encryptor (not the node's today) writes **plaintext** if encryption fails `[mechanism: StorageSegmentSink.encryptData falls back via .or(ProcessedData.unencrypted(data))]`. Both this fallback and the node's uncompressed, unencrypted sealing are tracked as #1261. They are latent, because blueprint validation rejects the `compression` and non-default `encryption-key-id` stream keys (#576; see `streaming-spec.md` header).

---

## 7. Governor Failover

*Carried over from 2026-04-11; not re-verified at `ccba0dba5`.*

`GovernorFailoverHandler` delegates to `StreamPartitionRecovery`, which uses `WatermarkTracker` to find the most advanced replica. Owner selection is decided outside the stream module.

- Detection speed is governed by the cluster's failure detector (SWIM), an aether-wide property.
- Replay cost is proportional to the events between the best replica watermark and the head. No replay rate has been measured.
- With `min-sync-replicas ≤ 1`, events acked by the old owner but not yet replicated are a loss window if that owner's disk is also lost; its local WAL covers a process crash with the disk intact `[design intent — unverified]`.

---

## 8. Compression & Encryption

*Carried over; not re-verified except as noted.* The node's sink uses `CompressionCodec.NONE` and no encryptor (re-verified, §2). Compression is implemented at the segment sink (`Compression` enum `{NONE, LZ4, ZSTD}` in `integrations/storage/`); hot-tier events are never compressed. Encryption is opt-in per sink via `StorageSegmentSink`; `SegmentReader` uses `"AES/GCM/NoPadding"`. Key management is outside the stream module. Note that `streaming-spec.md` records the `compression` stream key as unwired and rejected by blueprint validation (#576).

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
- **Copies on delivery.** Three `byte[]` copies with a local ring, about eight on a forwarded read, plus a decode → re-encode → decode round trip per consumed event (§4.3).
- **An fsync per durable publish.** Every acked owner publish on a WAL-backed node waits for a `force(false)` covering its record. Group commit amortises that only across concurrent appenders to the same partition (§5.3).

### Known latency cliffs and risk surfaces

- **Per-partition serial fsync** on the owner for batched publishes and on replicas for every record (§5.3; #1244, #1245).
- **Visibility waits for durability and the min-sync acks** (§3.1; #1235 merged as #1309): a consumer on a `min-sync-replicas ≥ 2` stream sees nothing until the slowest of the required peers has acked, so a lagging replica is a delivery-latency cliff, not a loss.
- **Segment build and serialisation on the evicting thread** (§6.3).
- **Cold-tier reads** replay sealed segments. The size of the step has not been measured.
- **Index overhead on small events** — 24 bytes per event (**code constant**).
- **STRONG** is unavailable at `f802f4153` and fails closed (§3.3).

---

## 11. Open Questions / Unmeasured

Each item needs a benchmark harness, which does not exist:

1. Owner-local publish latency, split into ring append, WAL fsync, and (for `min-sync-replicas ≥ 2`) replication wait.
2. EVENTUAL publish throughput per partition, single publisher and concurrent publishers (group-commit effectiveness).
3. Replica durable throughput per partition under the group-commit barrier (#1244): fsyncs per received batch and per backfill run, and how far concurrent single-record batches share one force.
4. Remote publish (QUIC forward) round-trip latency.
5. Push-path delivery latency (append → listener → callback), and the cost of the three delivery copies plus the re-encode.
6. Replication message rate and owner CPU per event with the unbatched manager, and the effect of wiring `ReplicationBatcher`.
7. Owner failover recovery time: detection, watermark resolution, replay.
8. Cold-tier read step, and the cost of synchronous segment build on append latency.
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
| WAL directory boot gate | `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (`verifyWalBootable`, `decideWalAvailability`; degrade path `resolveStreamWalDir`) |
