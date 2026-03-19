# In-Memory Streams

## Design Specification

**Version:** 0.1
**Status:** Exploratory Draft
**Target Release:** TBD (post Passive Worker Pools Phase 1)
**Author:** Design team
**Last Updated:** 2026-03-17

---

## Table of Contents

1. [Motivation](#1-motivation)
2. [Design Principles](#2-design-principles)
3. [Stream Model](#3-stream-model)
4. [Blueprint Declaration](#4-blueprint-declaration)
5. [Partition Ownership](#5-partition-ownership)
6. [Produce Path](#6-produce-path)
7. [Consume Path](#7-consume-path)
8. [Retention Model](#8-retention-model)
9. [Consumer Groups and Cursors](#9-consumer-groups-and-cursors)
10. [Replication](#10-replication)
11. [Backpressure](#11-backpressure)
12. [Failure Scenarios](#12-failure-scenarios)
13. [Relationship to Existing Resources](#13-relationship-to-existing-resources)
14. [Design Decisions](#14-design-decisions)
15. [Prior Art Comparison](#15-prior-art-comparison)
16. [Open Questions](#16-open-questions)
17. [Persistence Path](#17-persistence-path)

---

## 1. Motivation

### 1.1 The Gap in Aether's Resource Model

Aether provides two messaging/execution primitives today:

| Resource | Semantics | Ordering | Replay | Consumer Pacing |
|----------|-----------|----------|--------|-----------------|
| **Pub/Sub** | Fire-and-forget fan-out | None guaranteed | No | No (live delivery only) |
| **Scheduled Invocation** | Time-triggered execution | N/A | N/A | N/A |

The missing primitive is an **ordered, replayable, consumer-paced** stream — where a producer appends events and multiple consumers read at their own position, at their own pace. This is the primitive that Kafka, Pulsar, Redpanda, and Kinesis provide externally.

### 1.2 Why Built-In

Adding streaming as an external dependency (Kafka, Redpanda, NATS JetStream) creates:

- **Operational surface area:** Separate cluster to deploy, monitor, upgrade, scale.
- **Consistency boundary:** State in two systems (Aether consensus + external broker). Failure modes multiply.
- **Latency floor:** Network hop to external broker. Minimum ~100μs even on localhost, typically 0.5-2ms cross-node.
- **No co-location optimization:** External broker cannot co-locate producers and consumers on the same node.

As a built-in Aether resource, streams are:

- **Lifecycle-managed:** Created, scaled, and removed via blueprint — same as slices.
- **Co-location aware:** Runtime can place producer and consumer slices on the same node for zero-copy intra-node streaming.
- **Consistency-integrated:** Stream metadata lives in Rabia consensus alongside all other cluster state.
- **Zero-ops:** No separate cluster. Scales with the Aether topology automatically.

### 1.3 In-Memory Scope

This spec targets **in-memory streams only**. No disk persistence, no tiered storage, no log compaction. The stream is a bounded buffer. Events that age out of the retention window are gone.

This covers a large class of use cases:

- Event sourcing with bounded replay windows
- Real-time analytics pipelines (windowed aggregations)
- Service-to-service async communication (decoupled microservices)
- CQRS command distribution
- Sensor/telemetry ingestion with downstream processors
- Stream processing (map/filter/join/window operations)

Use cases requiring unbounded retention, log compaction, or durable replay from epoch zero are out of scope. Those workloads justify a dedicated external system (Kafka, Pulsar).

---

## 2. Design Principles

### 2.1 Resource, Not Infrastructure

A stream is an Aether **resource** — declared in the blueprint, managed by CDM, accessed by slices. It is not a separate infrastructure component. Slices interact with streams the same way they interact with the KV-Store or pub/sub: via an API provided by the Aether runtime.

### 2.2 Consensus for Metadata, Not Data

Stream metadata (topic existence, partition count, consumer group offsets, partition-to-governor assignments) goes through Rabia consensus. Stream data (the actual events in the ring buffer) never touches consensus. This is the same split principle used throughout Aether: desired/configuration state in consensus, observed/runtime state outside.

### 2.3 Governor as Partition Owner

The two-layer DHT already assigns governors to positions on the hash ring. Stream partitions map to governors via the same ring. The governor is the sequencing authority for its partitions — it assigns offsets and coordinates replication within its worker group. This reuses existing infrastructure rather than introducing new ownership mechanisms.

### 2.4 Bounded and Ephemeral

Streams are bounded ring buffers. Retention is by time, by entry count, or by memory size. There is no unbounded growth, no disk spill, no compaction. This keeps the implementation simple and memory behavior predictable.

---

## 3. Stream Model

### 3.1 Concepts

```
Stream (topic)
  ├── Partition 0  ──  owned by Governor A
  │     └── RingBuffer [offset 1042 ... offset 2041]  (bounded)
  ├── Partition 1  ──  owned by Governor B
  │     └── RingBuffer [offset 887 ... offset 1886]
  └── Partition 2  ──  owned by Governor A
        └── RingBuffer [offset 3001 ... offset 4000]

Consumer Group "analytics"
  ├── Consumer 0 → reads Partition 0 @ offset 1500
  ├── Consumer 1 → reads Partition 1 @ offset 900
  └── Consumer 2 → reads Partition 2 @ offset 3800

Consumer Group "audit"
  └── Consumer 0 → reads all partitions (fan-in)
```

### 3.2 Core Abstractions

| Concept | Description |
|---------|-------------|
| **Stream** | Named, partitioned, ordered log. Declared in blueprint. |
| **Partition** | Unit of ordering and parallelism. Events within a partition are strictly ordered. No ordering across partitions. |
| **Event** | Immutable record appended to a partition. Has an offset (monotonic, gap-free within partition), a key (optional, for partition routing), a payload (bytes), and a timestamp. |
| **Offset** | Monotonically increasing sequence number within a partition. Assigned by the partition owner (governor). |
| **Consumer Group** | Named group of consumers that collectively read a stream. Each partition is assigned to exactly one consumer in the group. Offset tracking is per-group-per-partition. |
| **Cursor** | A consumer group's read position in a partition. Stored in consensus KV-Store. |

### 3.3 Event Structure

```java
record StreamEvent(
    long offset,          // assigned by partition owner, monotonic
    long timestamp,       // producer timestamp (millisecond precision)
    byte[] key,           // optional, used for partition routing
    byte[] payload        // opaque bytes, application-defined
) { }
```

Maximum event size: configurable per stream, default **1 MB**. Streams are not designed for large blobs — use DHT or external storage for large payloads, reference by key in the event.

---

## 4. Blueprint Declaration

### 4.1 Stream Declaration

Streams are declared in the application blueprint alongside slices, scheduled tasks, and pub/sub topics:

```toml
[streams.order-events]
partitions = 6
retention = "time"               # "time", "count", or "size"
retention-value = "5m"           # 5 minutes (for time-based)
max-event-size = "64KB"          # default 1MB
replication = 2                  # replicas within governor group

[streams.sensor-telemetry]
partitions = 12
retention = "count"
retention-value = 100000         # keep last 100K events per partition
replication = 1                  # no replication (best-effort)

[streams.audit-log]
partitions = 3
retention = "size"
retention-value = "512MB"        # per-partition memory cap
replication = 2
consistency = "strong"           # produce path goes through Rabia
```

### 4.2 Consumer Group Declaration

Consumer groups can be declared in the blueprint (static) or created at runtime by slices (dynamic):

```toml
[streams.order-events.consumer-groups.analytics]
auto-offset-reset = "latest"     # "latest" or "earliest"
commit-interval = "1s"           # cursor commit frequency

[streams.order-events.consumer-groups.audit]
auto-offset-reset = "earliest"
commit-interval = "5s"
```

### 4.3 Consistency Levels

| Level | Produce Path | Ordering | Use Case |
|-------|-------------|----------|----------|
| `standard` (default) | Governor sequences locally | Per-partition | High throughput: analytics, telemetry, logs |
| `strong` | Produce goes through Rabia consensus | Total (across all partitions in stream) | Financial events, exactly-once, audit |

`strong` consistency trades throughput for total ordering. The Rabia Decision sequence number becomes the event offset. All partitions in a `strong` stream share a single offset space — effectively a single logical partition with multiple physical shards for read parallelism.

---

## 5. Partition Ownership

### 5.1 Mapping to the Two-Layer DHT

Stream partitions are assigned to governors via the same consistent hash ring used by the DHT. The partition key is `hash(streamName + ":" + partitionIndex)`, placing each partition at a position on the ring. The governor whose ring segment contains that position owns the partition.

```
Hash Ring:

        Governor A             Governor B            Governor C
    [--- segment ---]     [--- segment ---]     [--- segment ---]

  ^          ^      ^          ^         ^           ^
  |          |      |          |         |           |
  P0       P2      P5         P1        P3          P4

  order-events:0  order-events:2  ...etc
```

### 5.2 Partition Locality

Multiple partitions of the same stream may hash to the same governor. This is acceptable and even desirable for small streams — it keeps all partitions co-located, enabling efficient local fan-in for consumers reading multiple partitions.

### 5.3 Rebalancing on Governor Change

When a governor fails and a new governor inherits the ring position (per passive worker pools spec), it also inherits partition ownership. The new governor must reconstruct the ring buffer state. See [Section 12.1](#121-governor-failure).

---

## 6. Produce Path

### 6.1 Standard Consistency (Governor-Local Sequencing)

```
Producer Slice              Governor (partition owner)        Worker Replicas
     |                              |                              |
     |--[ProduceRequest]----------->|                              |
     |   key, payload               |                              |
     |                              |                              |
     |                       [assign offset]                       |
     |                       [append to ring buffer]               |
     |                              |                              |
     |                              |--[ReplicateEvent]----------->|
     |                              |   (async, best-effort)       |
     |                              |                              |
     |<--[ProduceAck]---------------|                              |
     |   offset, timestamp          |                              |
```

The governor is the single sequencer for each partition it owns. Offset assignment is a local atomic increment — no consensus round, no network coordination. This is the hot path.

### 6.2 Strong Consistency (Rabia Path)

```
Producer Slice        Governor        Core (any)          All Nodes
     |                   |                |                    |
     |--[ProduceReq]---->|                |                    |
     |                   |--[Mutation]--->|                    |
     |                   |                |                    |
     |                   |           [Rabia round]             |
     |                   |                |                    |
     |                   |<--[Decision]---|--[Decision]------->|
     |                   |                |                    |
     |<--[ProduceAck]----|                |                    |
     |   offset = Decision.seq            |                    |
```

The produce request becomes a Rabia mutation. The Decision sequence number is the offset. Total ordering across all partitions in the stream. Throughput is bounded by Rabia consensus rate.

### 6.3 Partition Routing

If the producer provides a key, the partition is determined by `hash(key) % partitionCount`. If no key, round-robin across partitions. This is identical to Kafka's partitioning model.

If the producing slice is not co-located with the partition's governor, the request routes through the producer's local governor to the owning governor (via GovernorMesh) or through core.

### 6.4 Co-Located Producer Optimization

If a producer slice runs on the same node as the partition-owning governor (or on a worker in the same group), the produce path is intra-process:

```
Producer Slice           Governor (same node)
     |                        |
     |--[direct method call]->|
     |                        |
     |<-[offset]--------------|
```

No serialization, no network hop. The ring buffer is a shared in-memory data structure. Latency is nanoseconds.

CDM can optimize for this by preferring to place producer slices on nodes within the partition owner's group. This is a placement hint, not a hard constraint.

---

## 7. Consume Path

### 7.1 Pull-Based Consumption

Consumers pull events from the partition owner. This matches Kafka's model and gives consumers control over their read rate.

```
Consumer Slice            Governor (partition owner)
     |                           |
     |--[FetchRequest]---------->|
     |   fromOffset, maxEvents   |
     |                           |
     |<--[FetchResponse]---------|
     |   events[], highWaterMark |
     |                           |
```

### 7.2 Push Subscription (Optional)

For low-latency use cases, consumers can register a push subscription. The governor pushes new events to subscribed consumers as they arrive.

```
Consumer Slice            Governor (partition owner)
     |                           |
     |--[Subscribe]------------->|
     |   fromOffset              |
     |                           |
     |<--[EventBatch]------------|  (pushed on new events)
     |<--[EventBatch]------------|
     |<--[EventBatch]------------|
     |   ...                     |
```

Push subscriptions are governor-to-consumer TCP connections. If the consumer is slow, the governor applies backpressure (see [Section 11](#11-backpressure)).

### 7.3 Co-Located Consumer Optimization

If a consumer slice runs on a worker that holds a replica of the partition (see [Section 10](#10-replication)), it reads from the local replica. No network hop.

If the consumer runs on the same node as the governor, it reads directly from the governor's ring buffer — zero-copy, same as the co-located producer case.

### 7.4 Fan-In Consumer

A consumer that reads from all partitions of a stream (fan-in pattern) issues parallel FetchRequests to all partition owners. The consumer merges results locally. Ordering across partitions is application-defined (typically by timestamp).

---

## 8. Retention Model

### 8.1 Ring Buffer Implementation

Each partition is backed by a bounded ring buffer in memory. When the buffer is full, the oldest events are evicted.

```
Ring Buffer (capacity = N events or T time or S bytes):

  [evicted] ... [evicted] [oldest ──────────────── newest]
                              ^                       ^
                          tailOffset              headOffset
                          (first readable)        (last written)

  Produce: append at headOffset + 1
  Evict:   advance tailOffset when retention limit exceeded
  Consume: read from any offset in [tailOffset, headOffset]
```

### 8.2 Retention Modes

| Mode | Configuration | Eviction Trigger | Behavior |
|------|--------------|------------------|----------|
| **Time** | `retention-value = "5m"` | Event age exceeds duration | Periodic sweep removes events older than `now - duration`. Check interval: `min(duration / 10, 1s)`. |
| **Count** | `retention-value = 100000` | Buffer exceeds N events | Oldest event evicted on each new append beyond capacity. |
| **Size** | `retention-value = "512MB"` | Total partition memory exceeds limit | Oldest events evicted until memory is under limit. Checked on each append. |

### 8.3 Compound Retention

If multiple constraints are needed (e.g., keep 5 minutes OR 100K events, whichever is smaller), this can be expressed as two retention rules evaluated independently. The most aggressive eviction wins.

This is a Phase 2 enhancement. Phase 1 supports a single retention mode per stream.

### 8.4 Consumer Lag and Eviction

If a consumer's cursor falls behind the tail offset (events have been evicted), the consumer receives a `CURSOR_EXPIRED` error on the next fetch. The consumer must reset to `tailOffset` (earliest available) or `headOffset` (latest). The consumer group's `auto-offset-reset` policy applies.

---

## 9. Consumer Groups and Cursors

### 9.1 Cursor Storage

Cursors (consumer group offsets) are stored in the Rabia consensus KV-Store:

```java
record StreamCursorKey(
    String streamName,
    int partitionIndex,
    String consumerGroup
) implements AetherKey { }

record StreamCursorValue(
    long committedOffset,    // last committed consumer offset
    long commitTimestamp     // when the commit occurred
) implements AetherValue { }
```

Cardinality: O(S × P × G) where S = streams, P = partitions per stream, G = consumer groups. For a typical deployment (20 streams × 10 partitions × 5 consumer groups = 1,000 keys). Well within consensus KV-Store budget.

### 9.2 Cursor Commit

Consumers commit cursors periodically (configurable `commit-interval`, default 1s). The commit is a Rabia mutation — it goes through the standard mutation forwarding path (worker → governor → core → consensus).

This means cursor commits have consensus latency (2-5ms same-DC). At a 1s commit interval, this is negligible overhead.

### 9.3 Partition Assignment within Consumer Group

**Option A: Consensus-Coordinated Assignment**

CDM tracks consumer group membership and assigns partitions to consumers via the consensus KV-Store. Assignment changes go through Rabia. This is simple, consistent, and reuses existing CDM machinery.

Partition assignment key:

```java
record StreamPartitionAssignmentKey(
    String streamName,
    String consumerGroup
) implements AetherKey { }

record StreamPartitionAssignmentValue(
    Map<Integer, NodeId> partitionToConsumer  // partition index -> consumer node
) implements AetherValue { }
```

**Option B: Governor-Local Assignment**

The governor that owns a partition assigns it to a consumer in the group based on consistent hashing, similar to slice instance assignment (REQ-SLICE-01 in passive worker pools spec). No consensus round needed. Faster rebalancing on consumer join/leave.

**Option C: Consumer-Side Assignment**

Consumers within a group coordinate among themselves, Kafka-style. The group has a coordinator (elected via consensus or by convention). Coordinator assigns partitions. This is more complex but allows custom assignment strategies.

> **Recommendation:** Option A for Phase 1. Consumer groups are small and change infrequently. Consensus overhead is negligible for assignment changes. Option B is a viable optimization if assignment latency becomes a concern at scale.

### 9.4 Consumer Rebalancing

When a consumer in a group joins or leaves:

1. CDM (Option A) or governor (Option B) detects the membership change.
2. New partition assignment is computed.
3. Consumers draining old partitions complete in-flight processing and commit cursors.
4. New partition assignment takes effect.
5. Consumers begin fetching from new partitions at committed offset.

**Cooperative rebalancing:** Only partitions that change ownership are paused. Consumers that keep their partitions continue uninterrupted. This avoids Kafka's historical stop-the-world rebalance problem.

---

## 10. Replication

### 10.1 Purpose

Replication serves two goals:
1. **Availability:** If the governor fails, replicas have the data needed to continue serving.
2. **Read scaling:** Consumers can read from replicas instead of the governor, reducing governor load.

### 10.2 Replication Factor

Configured per stream: `replication = N`. N = 1 means no replication (governor-only). N = 2 means one additional replica. Replicas are workers within the governor's group.

### 10.3 Replication Path

**Option A: Governor-Push Replication**

The governor pushes each event to N-1 replica workers after appending to its own ring buffer. Replication is asynchronous by default (producer gets ACK before replicas confirm). Synchronous replication (producer waits for N-1 ACKs) is configurable for higher durability.

```
Governor                    Replica Workers
   |                            |
   |  [append event locally]    |
   |                            |
   |--[ReplicateEvent]--------->|  (to N-1 replicas)
   |                            |  [append to local buffer]
   |                            |
   |<-[ReplicateAck]------------|  (if sync mode)
   |                            |
```

**Option B: Decision-Stream Replication**

For `strong` consistency streams, events go through Rabia and arrive at all nodes via the Decision stream. Replication is automatic — every worker that receives the Decision has the event. No additional replication mechanism needed.

For `standard` consistency streams, the governor could publish events as lightweight non-consensus messages piggybacked on the Decision relay path. Workers apply them to local ring buffer replicas. This reuses existing infrastructure but mixes data-plane and control-plane channels.

**Option C: SWIM-Piggybacked Replication (Small Events Only)**

For small events (< 1KB), replication metadata could piggyback on SWIM messages within the group, similar to how membership changes piggyback on ping/ack. This is elegant but only viable for low-throughput, small-event streams.

> **Recommendation:** Option A for `standard` streams (simple, governor controls replication). Option B is inherent for `strong` streams. Option C is a future optimization for specific workloads.

### 10.4 Replica Assignment

The governor selects replica workers using consistent hashing (same pattern as slice instance assignment). Replica assignments change when group membership changes.

### 10.5 Replica Consistency

Replicas may lag behind the governor by a small number of events (async replication). The governor tracks each replica's confirmed offset. Consumers reading from replicas see the replica's high-water mark, not the governor's.

For consumers that need the latest data: read from the governor. For consumers that tolerate slight lag: read from any replica. The consumer's FetchRequest can specify `read-preference = "leader"` (governor) or `read-preference = "any"` (governor or replica).

---

## 11. Backpressure

### 11.1 Producer Backpressure

If the ring buffer is full and retention policy has not yet evicted enough events (possible with count-based retention during a burst), the governor has three options:

| Strategy | Behavior | Configuration |
|----------|----------|---------------|
| `block` | Producer blocks until space is available | `backpressure = "block"` |
| `drop-oldest` (default) | Evict oldest events to make room | `backpressure = "drop-oldest"` |
| `reject` | Return error to producer, producer retries or drops | `backpressure = "reject"` |

### 11.2 Consumer Backpressure (Push Mode)

For push subscriptions, if the consumer is slow:

1. Governor tracks unacknowledged event batches per consumer.
2. If unacknowledged batches exceed `max-in-flight` (default 5), governor pauses push for that consumer.
3. Consumer ACKs previously delivered batches, governor resumes push.
4. If consumer remains slow beyond `push-timeout` (default 30s), governor revokes push subscription and consumer falls back to pull mode.

### 11.3 Integration with CDM

The governor's `WorkerGroupHealthReport` includes stream metrics:

```
StreamPartitionMetrics:
  streamName:      String
  partitionIndex:  int
  headOffset:      long
  tailOffset:      long
  consumerLag:     Map<String, long>   // consumer group -> lag (events behind head)
  producerRate:    double              // events/second (windowed average)
  memoryUsed:      long                // bytes
```

CDM uses consumer lag to trigger scaling of consumer slice instances. If consumer group "analytics" has sustained lag > threshold across partitions, CDM increases instance count for the analytics consumer slice.

---

## 12. Failure Scenarios

### 12.1 Governor Failure (Partition Owner Lost)

```
Scenario: Governor that owns stream partitions crashes.

Detection:
  - SWIM detects within 1-2 seconds.
  - New governor inherits ring position (per passive worker pools spec).

Recovery:
  1. New governor takes ownership of the ring position, inheriting
     all partition assignments.
  2. New governor reconstructs partition state from replicas:
     - Queries replica workers for their current ring buffer contents.
     - Replica with highest confirmed offset becomes the authoritative source.
     - New governor copies buffer from best replica.
  3. If replication = 1 (no replicas):
     - Unreplicated events since last consumer commit are lost.
     - New governor starts with empty buffer.
     - Consumers resume from last committed cursor (in consensus KV-Store).
  4. New governor begins accepting produces and serving fetches.

Impact:
  - Produce unavailable for partition: 2-5 seconds (detection + election + recovery).
  - Consume unavailable: same window, then resumes from replica or committed cursor.
  - Data loss: zero with replication >= 2. Up to commit-interval worth of
    uncommitted consumer progress with replication = 1.
  - Other partitions owned by different governors: unaffected.

Duration: 2-5 seconds
Data loss: None (replicated) or bounded (unreplicated)
```

### 12.2 Worker Failure (Replica or Consumer Lost)

```
Scenario: Worker holding a partition replica or running a consumer slice crashes.

Detection:
  - SWIM detects within 1-2 seconds.

Recovery (replica):
  1. Governor selects replacement replica from remaining group members.
  2. Governor replicates current ring buffer to new replica.
  3. No impact on producers or other consumers.

Recovery (consumer):
  1. Consumer group detects member loss (CDM or governor).
  2. Partition reassignment: orphaned partitions assigned to remaining consumers.
  3. New consumer resumes from last committed cursor offset.
  4. Events between last commit and failure are reprocessed (at-least-once).

Impact:
  - Replica loss: no impact on availability. Reduced redundancy until replacement syncs.
  - Consumer loss: rebalancing latency (~2-5s). Brief reprocessing of uncommitted events.

Duration: 2-5 seconds
Data loss: None
```

### 12.3 Core Quorum Loss

```
Scenario: Rabia quorum lost. Consensus unavailable.

Impact on streams:
  - Standard consistency streams: CONTINUE operating.
    - Governor-local sequencing does not require consensus.
    - Produces and consumes proceed normally.
    - Consumer cursor commits fail (consensus unavailable).
    - Consumers track cursors in memory, commit when consensus recovers.
  - Strong consistency streams: STALL.
    - Produce requires Rabia round — unavailable.
    - Consume of already-committed events continues from replicas.
  - New stream creation: unavailable (blueprint changes need consensus).
  - Consumer rebalancing (Option A): unavailable until consensus recovers.

Duration: Until quorum restored.
Standard stream disruption: cursor commits deferred, otherwise fully operational.
Strong stream disruption: produces stall.
```

### 12.4 Network Partition: Producer Isolated from Partition Owner

```
Scenario: Producer slice cannot reach the governor that owns the target partition.

Recovery:
  1. Producer's local governor routes produce request through core
     (core has connections to all governors).
  2. If core is also unreachable: produce fails with timeout.
  3. Producer retries with backoff.

Impact:
  - Increased produce latency (extra hop through core).
  - No data loss if produce eventually succeeds.
  - Producer application decides retry/drop policy.
```

---

## 13. Relationship to Existing Resources

### 13.1 Streams vs. Pub/Sub

| Dimension | Pub/Sub | Streams |
|-----------|---------|---------|
| Delivery | Push (fire-and-forget) | Pull (consumer-paced) or push subscription |
| Ordering | None guaranteed | Per-partition strict ordering |
| Replay | No | Yes, within retention window |
| Consumer groups | No (all subscribers get all messages) | Yes (partition-parallel consumption) |
| Retention | None (delivered and gone) | Time, count, or size-bounded |
| Cursor tracking | N/A | Per-group-per-partition in consensus |
| Use case | Notifications, events, broadcasts | Event sourcing, stream processing, async pipelines |

Pub/Sub remains the right choice for fire-and-forget notifications where ordering and replay don't matter. Streams are for workloads that need ordered, replayable, consumer-paced event processing.

### 13.2 Streams vs. KV-Store

The KV-Store is a key-value map with last-writer-wins semantics. Streams are append-only ordered logs. They serve fundamentally different purposes:

- KV-Store: current state ("what is the latest value for key X?")
- Streams: event history ("what happened, in order, since offset Y?")

Streams could be used to build a KV-Store (event sourcing pattern), and KV-Store entries could trigger stream events (change data capture pattern). The two resources are complementary.

### 13.3 Interaction Patterns

```
                     +-----------+
                     |  Pub/Sub  |  (notifications, broadcasts)
                     +-----+-----+
                           |
                           v
+----------+        +------+------+        +----------+
| Producer |------->|   Stream    |------->| Consumer |
|  Slice   |        | (ordered,   |        |  Slice   |
+----------+        |  replayable)|        +----------+
                    +------+------+
                           |
                           v
                    +------+------+
                    |  KV-Store   |  (materialized view)
                    +-------------+
```

A typical pattern: producer slice writes to stream → consumer slice processes events → consumer writes materialized view to KV-Store → pub/sub notifies downstream slices of state change.

---

## 14. Design Decisions

This section catalogs the key design decisions, options considered, and (where applicable) recommendations. Decisions marked **OPEN** require further design or benchmarking.

### DD-1: Partition Buffer Location

**Context:** Where does the partition ring buffer physically reside?

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| **A: Governor-only** | Ring buffer lives on governor node only. All produces and consumes hit governor. | Simplest. Single sequencing point. No distributed buffer coordination. | Governor is SPOF for reads and writes. Governor memory bounds total partition capacity. |
| **B: Governor-primary, worker replicas** | Governor holds authoritative buffer. N-1 workers hold read replicas. Consumers can read from replicas. | Read scaling. Survivable governor failure (replica promotion). | Replication overhead. Replica lag management. More complex failure recovery. |
| **C: Worker-sharded** | Governor assigns sub-ranges of the offset space to workers. Workers sequence locally within their sub-range. Governor merges for global ordering. | Distributes write load. Higher aggregate throughput. | Complex. Ordering guarantees require governor coordination. Sub-range merge on read path. Offset space management. |

> **Recommendation:** Option A for Phase 1 (simplest, validates the model). Option B for Phase 2 (read scaling and availability). Option C only if benchmarks show governor write throughput is a bottleneck — unlikely for in-memory bounded buffers.

### DD-2: Produce Acknowledgment Semantics

**Context:** When does the producer receive an ACK?

| Option | Description | Durability | Latency |
|--------|-------------|------------|---------|
| **A: Governor-append** | ACK after governor appends to local buffer. Replication is async. | Events lost if governor crashes before replication. | Lowest (~μs co-located, ~1ms remote). |
| **B: N-replica-confirm** | ACK after N replicas confirm. Configurable N. | Events survive governor failure if N >= 2. | Higher (replication RTT added). |
| **C: Consensus-commit** | ACK after Rabia Decision commits the event. | Maximum durability (consensus-replicated). | Highest (consensus round latency, 2-5ms+). |

> **Recommendation:** Option A as default (`standard` consistency). Option C for `strong` consistency. Option B as an intermediate tier if users need better durability than A without the throughput cost of C. Configurable per stream via `acks` parameter:

```toml
[streams.my-stream]
acks = "governor"     # Option A (default)
# acks = "replicas"   # Option B, requires replication >= 2
# acks = "consensus"  # Option C, equivalent to consistency = "strong"
```

### DD-3: Consumer Group Partition Assignment

**Context:** How are partitions assigned to consumers within a group? (Detailed in [Section 9.3](#93-partition-assignment-within-consumer-group))

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| **A: Consensus-coordinated** | CDM assigns partitions via KV-Store mutation. | Consistent. Reuses CDM. Simple. | Consensus latency for rebalancing. |
| **B: Governor-local** | Governor assigns via consistent hashing. | Fast rebalancing. No consensus round. | Multiple governors must agree on cross-governor assignment. |
| **C: Consumer-side** | Consumers coordinate among themselves (Kafka-style). | Flexible. Custom strategies. | Complex. Reinvents Kafka's hardest problem. |

> **Recommendation:** Option A for Phase 1. Rebalancing is infrequent; consensus latency is acceptable. Option B for Phase 2 if rebalancing latency matters at scale.

### DD-4: Cross-Governor Produce Routing

**Context:** Producer is on a different governor's group than the partition owner. How does the produce reach the owner?

| Option | Description | Latency | Complexity |
|--------|-------------|---------|------------|
| **A: Via core** | Producer → local governor → core → owning governor. | +1 hop through core. | Low. Uses existing mutation forwarding. |
| **B: Via GovernorMesh** | Producer → local governor → owning governor (direct). | Direct governor-to-governor. | Medium. Requires GovernorMesh routing table for stream partitions. |
| **C: Direct producer-to-governor** | Producer slice connects directly to owning governor. | Lowest (no intermediary). | High. Producer needs to discover and connect to remote governor. Bypasses group topology. |

> **Recommendation:** Option A for Phase 1 (simplest, works with existing infrastructure). Option B for Phase 2 (GovernorMesh already exists for DHT relay; extend it to stream produce routing). Option C is unlikely to be needed — the extra hop in A/B is negligible for most workloads.

### DD-5: Replication Strategy

**Context:** How are events replicated to worker replicas? (Detailed in [Section 10.3](#103-replication-path))

| Option | Description | Best For |
|--------|-------------|----------|
| **A: Governor-push** | Governor pushes events to replicas after local append. | Standard streams. Simple, governor controls timing. |
| **B: Decision-stream** | Strong-consistency events arrive via Decision stream — inherent replication. | Strong streams. No additional mechanism. |
| **C: SWIM-piggyback** | Small events piggybacked on SWIM messages. | Low-throughput, small-event streams. |

> **Recommendation:** Option A for standard streams, Option B for strong streams (inherent). Option C is a niche optimization, not for Phase 1.

### DD-6: Slice API Style

**Context:** How do slices interact with streams?

| Option | Description |
|--------|-------------|
| **A: Imperative API** | Slices call `streamClient.produce(event)` and `streamClient.fetch(offset)` explicitly. Full control. |
| **B: Declarative annotation** | Slices annotate methods with `@StreamConsumer("order-events")` and the runtime invokes them per event. Similar to `@Scheduled`. |
| **C: Both** | Imperative API for producers (and advanced consumers). Declarative annotations for simple consumers. |

> **Recommendation:** Option C. Producers are typically imperative (application logic decides when and what to produce). Simple consumers benefit from declarative style (runtime handles polling, offset commits, rebalancing). Advanced consumers that need batching, manual offset management, or multi-stream joins use the imperative API.

```java
// Declarative consumer
@StreamConsumer(stream = "order-events", group = "analytics")
Result<Void> processOrder(StreamEvent event) {
    // called per event, runtime handles fetch loop and commits
}

// Imperative producer
StreamProducer producer = runtime.stream("order-events").producer();
producer.send(key, payload);

// Imperative consumer (advanced)
StreamConsumer consumer = runtime.stream("order-events")
    .consumer("analytics");
List<StreamEvent> batch = consumer.fetch(maxEvents: 100);
// ... process batch ...
consumer.commit();
```

---

## 15. Prior Art Comparison

### 15.1 Comparison Matrix

| Feature | Aether Streams (proposed) | Kafka | Redpanda | NATS JetStream | Pulsar |
|---------|--------------------------|-------|----------|----------------|--------|
| **Storage** | In-memory ring buffer | Disk (page cache) | Disk (direct I/O) | File/memory | Disk (BookKeeper) |
| **Ordering** | Per-partition | Per-partition | Per-partition | Per-subject | Per-partition |
| **Total ordering option** | Yes (`strong` via Rabia) | No | No | No | No |
| **Partition ownership** | Governor (DHT ring) | Broker (controller-assigned) | Broker (Raft leader) | Meta server | Broker (ZK/Raft) |
| **Replication** | Governor-push within group | ISR (leader-follower) | Raft per-partition | Raft per-stream | BookKeeper quorum |
| **Consumer groups** | Consensus-coordinated | Coordinator broker | Coordinator broker | Server-side | Subscription types |
| **Failure detection** | SWIM (~2s) | Session timeout (~10s+) | Raft heartbeat | Server monitoring | ZK session / Raft |
| **Co-location optimization** | Yes (zero-copy same-node) | No | No | No | No |
| **Deployment** | Built into Aether runtime | Separate cluster | Separate cluster | Separate cluster | Separate cluster (+ BookKeeper) |
| **Retention** | Bounded (time/count/size) | Unbounded + compaction | Unbounded + compaction | Limits + discard | Unbounded + tiered |

### 15.2 Key Differentiators

**vs Kafka / Redpanda:** Aether streams are in-memory and integrated into the application runtime. No separate cluster, no disk I/O, no ZooKeeper/KRaft. The tradeoff is bounded retention — events that age out are gone. Kafka is better for unbounded event history; Aether streams are better for real-time windowed processing at lower latency.

**vs NATS JetStream:** Closest in spirit — JetStream is also embeddable and lightweight. Aether streams add per-partition ordering (JetStream is per-subject), total ordering via Rabia (`strong` consistency), and integrated co-location optimization.

**vs Pulsar:** Pulsar separates storage (BookKeeper) from serving (brokers). Aether streams collapse both into the governor/worker layer. Simpler topology, lower latency, but no durable storage.

### 15.3 Northguard Comparison

LinkedIn's Northguard (the article that prompted this exploration) addresses Kafka's scaling problems by introducing segment-level replication and DS-RSM for decentralized metadata. Aether streams take a fundamentally different approach:

| Dimension | Northguard | Aether Streams |
|-----------|-----------|----------------|
| **Problem framing** | Kafka partitions too large to move → make smaller segments | Streaming doesn't need a separate system → integrate into the runtime |
| **Metadata management** | DS-RSM (Raft-sharded) | Rabia consensus (small, bounded metadata) |
| **Data management** | Segment replication across brokers | In-memory ring buffers on governors + worker replicas |
| **Failure recovery** | Segment-level failover | Governor inherits ring position, reconstructs from replicas |
| **Abstraction layer** | xInfra (retrofit to abstract Kafka vs Northguard) | Native resource (no abstraction needed — it's the same system) |
| **Target scale** | Hyperscale (LinkedIn's 32T records/day) | Application-scale (bounded by memory, not disk) |

Northguard and Aether streams are not competing designs — they target different points in the design space. Northguard is for hyperscale persistent streaming. Aether streams are for application-integrated in-memory streaming where the streaming primitive is as natural as a function call.

---

## 16. Open Questions

| # | Question | Impact | Notes |
|---|----------|--------|-------|
| OQ-1 | **Stream-to-stream joins:** Should the runtime support native stream joins (windowed, temporal), or leave this to application-level slice logic? | Phase 2+ | Joins are complex. May be better as a library pattern (slice reads from two streams, joins in application code) than a runtime primitive. |
| OQ-2 | **Dead letter stream:** What happens when a consumer repeatedly fails to process an event? Should there be a built-in dead letter mechanism? | Phase 1 | Could leverage existing pub/sub for dead letter notification, with the failed event available in the stream at its offset. |
| OQ-3 | **Schema enforcement:** Should streams enforce event schema (e.g., via schema registry or Aether's own artifact mechanism)? | Phase 2 | Schema-on-read is simpler. Schema-on-write catches errors earlier. Both have precedent (Kafka Schema Registry, Pulsar native schemas). |
| OQ-4 | **Stream-triggered slices:** Should a stream consumer be a first-class CDM scheduling trigger (like scheduled invocation), where CDM automatically scales consumer instances based on lag? | Phase 1 | Natural extension of CDM's existing autoscaling. Consumer lag is already reported in health metrics. |
| OQ-5 | **Cross-cluster streaming:** Should streams span Aether clusters (multi-cluster replication)? | Phase 3 | Depends on multi-cluster bridge (Phase 3 of passive worker pools). Governor-to-governor relay could carry stream events across cluster boundaries. |
| OQ-6 | **Exactly-once semantics:** For `strong` consistency streams, can we guarantee exactly-once produce and consume? Rabia's idempotent command application handles produce dedup. Consumer-side exactly-once requires transactional cursor commit + side-effect. | Phase 2 | Produce-side exactly-once is achievable via Rabia dedup. Consumer-side is harder — requires application-level idempotency or a transaction model spanning cursor commit and KV-Store mutation. |
| OQ-7 | **Memory accounting:** How is stream memory accounted for in CDM's resource tracking? Should streams have per-node memory budgets? | Phase 1 | Governor health reports already include memory metrics. Stream memory is part of the node's total memory. Explicit per-stream budgets prevent a single stream from starving slices. |
| OQ-8 | **Compression:** Should events be compressed in the ring buffer (reducing memory footprint at CPU cost)? Batch compression (like Kafka's record batch compression) amortizes overhead. | Phase 2 | In-memory streams are already bounded. Compression extends effective capacity at the cost of produce/consume latency. Configurable per stream. |

---

## 17. Persistence Path

### 17.1 Transparent Storage Upgrade

The in-memory ring buffer is a deliberate Phase 1 scope constraint, not an architectural limitation. The governor already owns the partition, sequences events, and manages replication. Adding a persistent backend behind the ring buffer is a configuration change, not an architecture change.

```toml
[streams.order-events]
partitions = 6
retention = "time"
retention-value = "7d"
storage = "persistent"       # ring buffer backed by persistent storage
buffer-size = 100000         # in-memory read cache (most recent events)
```

In this model:
- The governor writes to persistent storage after sequencing (append-only, sequential writes).
- The ring buffer becomes a **read cache** in front of the persistent log.
- Consumers reading recent events hit memory. Consumers replaying from earlier offsets fall back to storage.
- The slice API does not change. Producer and consumer code is identical for in-memory and persistent streams.

### 17.2 PostgreSQL as Persistent Backend

Aether already has an async PostgreSQL driver with built-in pipelining. Stream partitions map naturally to database tables (one table per partition, append-only inserts). The pipelining driver makes sequential appends efficient — multiple partition writes in flight over a single connection.

This opens a critical capability: **transactional cursor commits alongside application state mutations**.

```
Consumer Slice                  PostgreSQL
     |                              |
     |  BEGIN                       |
     |--[INSERT INTO results]------>|   (application side effect)
     |--[UPDATE stream_cursors]--->|   (cursor commit)
     |  COMMIT                      |
     |                              |
```

Consumer offset and business side effect in one transaction. If the transaction fails, both roll back. If it commits, both are durable. This is **exactly-once consumer semantics** without requiring application-level idempotency — the hardest problem in stream processing, solved by the database's existing ACID guarantees.

### 17.3 Infrastructure Elimination Progression

Each phase eliminates external infrastructure for a broader class of streaming use cases:

| Phase | Capability | Eliminates |
|-------|-----------|------------|
| **Phase 1: In-memory** | Real-time windowed processing, analytics pipelines, async communication | Kafka for non-durable use cases, Redis Streams |
| **Phase 2: Persistent** | Durable event sourcing, unbounded replay, long-retention audit logs | Kafka for durable streaming, Pulsar |
| **Phase 3: Transactional** | Exactly-once processing, transactional outbox, CDC | Kafka + Debezium + Kafka Connect, transactional outbox libraries |

At the end of this progression, the entire event-driven architecture stack — Kafka, ZooKeeper/KRaft, Schema Registry, Kafka Connect, Debezium, consumer frameworks — collapses into Aether + PostgreSQL.

### 17.4 Design Constraints for Phase 1

The in-memory Phase 1 design must not preclude the persistence path. Key constraints to preserve:

1. **Governor sequencing is authoritative.** Offsets are assigned by the governor before any storage write. Persistent storage is append-only — it never assigns offsets.
2. **Ring buffer interface is abstract.** The ring buffer implementation must be swappable. Phase 1 uses `InMemoryRingBuffer`. Phase 2 introduces `PersistentRingBuffer` that writes through to storage and uses the in-memory buffer as a read cache.
3. **Cursor commit is a separate operation.** Cursor storage (consensus KV-Store in Phase 1) must be replaceable with database-transactional commits in Phase 3 without changing the consumer API.
4. **Event serialization is opaque bytes.** The ring buffer stores `byte[]` payloads. Persistent storage writes the same bytes. No format coupling between the buffer layer and the storage layer.
5. **Retention policy is storage-aware.** Time/count/size retention applies regardless of backend. Persistent storage adds a `compact` retention mode (keep latest per key) — log compaction, Phase 2+.

---

## References

### Internal
- [Passive Worker Pools Spec](passive-worker-pools-spec.md) — Two-layer topology, governor protocol, SWIM integration, KV-Store split
- [KV-Store Scalability Analysis](../internal/kv-store-scalability.md) — Consensus data budget analysis

### External
- [Apache Kafka Design](https://kafka.apache.org/documentation/#design) — Partitioned log, consumer groups, ISR replication
- [Redpanda Architecture](https://docs.redpanda.com/current/get-started/architecture/) — Raft-per-partition, thread-per-core, no JVM
- [NATS JetStream](https://docs.nats.io/nats-concepts/jetstream) — Embedded streaming, Raft-based
- [Apache Pulsar Architecture](https://pulsar.apache.org/docs/concepts-architecture-overview/) — Broker/BookKeeper separation
- [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/) — In-memory ring buffer, mechanical sympathy
- [Chronicle Queue](https://chronicle.software/chronicle-queue/) — Off-heap, memory-mapped persistent queue for Java
- [LinkedIn Northguard](https://engineering.linkedin.com/) — Segment-level replication, DS-RSM metadata (see Kapil Khatik article, Feb 2026)
