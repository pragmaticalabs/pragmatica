# Cluster Scalability via Passive Worker Pools

## Design Specification

**Version:** 1.0
**Status:** Draft
**Target Release:** Phase 1 in 0.20.x, Phase 2 in 0.21.x, Phase 3 future
**Author:** Design team
**Last Updated:** 2026-03-08

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Architecture Overview](#2-architecture-overview)
3. [SWIM Protocol Integration](#3-swim-protocol-integration)
4. [Governor Protocol](#4-governor-protocol)
5. [Group Formation and Splitting](#5-group-formation-and-splitting)
6. [KV-Store Split](#6-kv-store-split)
7. [CDM Scheduling Enhancement](#7-cdm-scheduling-enhancement)
8. [Mutation Forwarding](#8-mutation-forwarding)
9. [Decision Stream Relay](#9-decision-stream-relay)
10. [Worker Slice Lifecycle](#10-worker-slice-lifecycle)
11. [Spot Pool](#11-spot-pool)
12. [Failure Scenarios](#12-failure-scenarios)
13. [Phased Implementation Plan](#13-phased-implementation-plan)
14. [Prior Art Comparison](#14-prior-art-comparison)
15. [Open Questions](#15-open-questions)
16. [Automatic Topology Growth](#16-automatic-topology-growth)
17. [SWIM as Universal Health Detection](#17-swim-as-universal-health-detection)

---

## 1. Problem Statement

### 1.1 Current Limitation

Aether clusters are bounded by the Rabia consensus protocol. Rabia uses all-to-all broadcast where every node sends proposals to every other node and every node receives Decisions. The message complexity is O(N^2) per consensus round.

Practical Rabia cluster sizes: **5, 7, 9, or 11 nodes**. Beyond 11, the O(N^2) message overhead degrades throughput and latency unacceptably.

### 1.2 The Gap

Production workloads need tens, hundreds, or thousands of compute nodes:

| Scenario | Nodes Needed | Current Max | Gap |
|----------|-------------|-------------|-----|
| Small SaaS | 10-20 | 9 | 2x |
| Mid-tier platform | 50-100 | 9 | 10x |
| Large-scale service | 200-1000 | 9 | 100x |
| Hyperscale / multi-region | 1000-10000+ | 9 | 1000x |

### 1.3 Why Not Bigger Consensus?

Increasing Rabia cluster size is not viable:

- **Message volume:** 11 nodes = 110 messages/round. 100 nodes = 9,900 messages/round.
- **Latency:** Every consensus round requires all-to-all exchange. More nodes = higher p99 latency.
- **Failure tolerance:** Rabia tolerates f < N/2 failures. A 9-node cluster tolerates 4 failures -- already sufficient.

### 1.4 Design Goal

Scale Aether to **10,000+ nodes** while:
- Preserving the existing Rabia consensus core (5-9 nodes) unchanged
- Maintaining strong consistency for cluster state (blueprints, config, routing)
- Providing eventual consistency for service discovery (endpoints, routes)
- Keeping O(1) per-node overhead for membership and health monitoring
- Enabling elastic scaling (spot instances, auto-scaling)

### 1.5 Existing Foundation

Aether already has the building blocks:

| Component | Location | Status |
|-----------|----------|--------|
| `PassiveNode<K,V>` | `integrations/cluster/.../passive/PassiveNode.java` | Working |
| `AetherPassiveLB` | `aether/lb/AetherPassiveLB.java` | Working |
| `NodeRole.ACTIVE/PASSIVE` | `integrations/consensus/net/NodeRole.java` | Working |
| Decision stream to passive nodes | `PassiveNode.applyDecision()` | Working |
| KV-Store replication | `KVStore<K,V>` | Working |
| CDM placement | `ClusterDeploymentManager` | Working |
| Endpoint discovery | `EndpointRegistry` | Working |

---

## 2. Architecture Overview

### 2.1 Two-Layer Topology

```
+---------------------------------------------------------------+
|                    CORE LAYER (5-9 nodes)                      |
|                                                                |
|  +--------+   +--------+   +--------+   +--------+   +------+ |
|  | Core-1 |<->| Core-2 |<->| Core-3 |<->| Core-4 |<->|Core-5| |
|  +--------+   +--------+   +--------+   +--------+   +------+ |
|       All-to-all Rabia consensus (O(N^2), N<=11)               |
|       Owns: Blueprints, Config, SliceTargets, Routing rules    |
|       Runs: CDM (leader only), DHT, Artifact Repository        |
+------+------------------+------------------+------------------++
       |                  |                  |
       v                  v                  v
+------+------+   +-------+------+   +------+------+
|  Governor   |   |  Governor    |   |  Governor   |
|  Group A    |   |  Group B     |   |  Group C    |
| (zone-us-1) |   | (zone-us-2)  |   | (zone-eu-1) |
+------+------+   +------+------+   +------+------+
       |                 |                  |
  +----+----+       +----+----+       +----+----+
  |  Workers |      |  Workers |      |  Workers |
  |  (SWIM)  |      |  (SWIM)  |      |  (SWIM)  |
  | w1..w150 |      | w1..w100 |      | w1..w80  |
  +----------+      +----------+      +----------+
```

### 2.2 Node Roles

| Role | Count | Consensus | DHT | Slices | SWIM | Description |
|------|-------|-----------|-----|--------|------|-------------|
| **Core** | 5-9 | Yes (Rabia) | Owner | Optional | Participant | Consensus participant, state authority |
| **Governor** | 1 per group | No | Client | Yes | Coordinator | Elected worker that relays Decisions |
| **Worker** | 0-10000+ | No | Client | Yes | Participant | Runs slices, reports health |

**Key insight:** Governor is NOT a separate node type. It is a regular worker that additionally performs relay and aggregation duties. Any worker can become governor through election.

### 2.3 Data Flow Summary

```
                    WRITES (mutations)
Worker ---[mutation]--> Governor ---[forward]--> Any Core Node
                                                     |
                                                  Rabia consensus
                                                     |
                    READS (Decisions)                 v
Worker <--[relay]--- Governor <---[Decision stream]-- Core Nodes

                    HEALTH
Worker <--[SWIM ping/ack]--> Workers in same group
Governor ---[health summary]--> Core (periodic)
```

### 2.4 Connection Topology

```
Core <-> Core:       Full mesh (Rabia requirement)
Core <-> Governor:   Each governor connects to ALL core nodes
Governor <-> Worker: Each worker connects to its governor
Worker <-> Worker:   SWIM protocol within group (UDP, random targets)
Worker <-> Core:     Fallback only (during governor re-election)
```

---

## 3. SWIM Protocol Integration

### 3.1 SWIM Overview

SWIM (Scalable Weakly-consistent Infection-style Process Group Membership) is a membership protocol with two key properties that make it ideal for worker pools:

1. **O(1) message overhead per member** regardless of group size
2. **O(log N) convergence time** for membership change dissemination

### 3.2 Protocol Mechanics

Each SWIM period (configurable, default 1 second):

```
1. Node A picks random target B
2. A sends PING to B
3. If B responds with ACK within timeout:
   - B is alive, done
4. If no ACK:
   - A picks K random nodes (K=3 default)
   - A sends PING-REQ(B) to each
   - Each K node sends PING to B independently
   - If any K node receives ACK from B, it forwards ACK to A
5. If still no ACK after all indirect probes:
   - A marks B as SUSPECT
   - After suspect timeout (5 seconds default):
     - If no refutation received, B declared FAULTY
     - Faulty notification disseminated to all members
```

### 3.3 Dissemination via Piggybacking

Membership changes (join/leave/suspect/faulty) piggyback on existing PING/ACK messages. No separate gossip messages needed.

```
+------------------+
| PING / ACK       |  <- normal protocol message
+------------------+
| Piggyback buffer |  <- membership updates ride along
| [join: W42]      |
| [faulty: W17]    |
| [suspect: W99]   |
+------------------+
```

Each update has an incarnation number. Higher incarnation = more recent. Nodes merge piggybacked updates into local membership list.

Propagation: epidemic spread. After O(log N) protocol periods, all nodes have received the update with high probability.

### 3.4 Aether SWIM Implementation

> **Note:** SWIM is used for ALL node types, not just workers. The `integrations/swim/` module serves both core-to-core and worker group health detection. Core-to-core SWIM uses tighter parameters for faster failure detection among consensus participants. See [Section 17](#17-swim-as-universal-health-detection) for full details on universal SWIM health detection.

**REQ-SWIM-01:** Implement SWIM as a new module: `integrations/swim/`.

**REQ-SWIM-02:** SWIM operates over UDP for ping/ack within groups. Port: cluster port + 1.

**REQ-SWIM-03:** Configuration parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `swim.period` | 1s | Time between probe rounds |
| `swim.probe-timeout` | 500ms | Direct ping timeout |
| `swim.indirect-probes` | 3 | Number of indirect probe targets (K) |
| `swim.suspect-timeout` | 5s | Time in suspect before declaring faulty |
| `swim.max-piggyback` | 10 | Max membership updates per message |

**REQ-SWIM-04:** Message format (binary, compact):

```
+--------+--------+--------+--------+
| Type   | SeqNo  | Source | Target |  <- 16 bytes header
+--------+--------+--------+--------+
| Incarnation     | Payload length  |  <- 8 bytes
+--------+--------+--------+--------+
| Piggyback entries (variable)      |  <- 0-N membership updates
+-----------------------------------+

Type: PING(0x01), ACK(0x02), PING_REQ(0x03)

Piggyback entry:
+--------+--------+--------+--------+
| Event  | NodeId | Incarnation     |  <- 12+ bytes per entry
+--------+--------+-----------------+

Event: JOIN(0x01), ALIVE(0x02), SUSPECT(0x03), FAULTY(0x04), LEAVE(0x05)
```

**REQ-SWIM-05:** SWIM membership list provides:
- `List<SwimMember> members()` - all known members
- `SwimMember.State` - ALIVE, SUSPECT, FAULTY
- `SwimMembershipListener` - callbacks for membership changes

### 3.5 SWIM within Aether's Architecture

```
+---------------------------------------------------+
|                  Worker Node                       |
|                                                    |
|  +----------------+  +------------------------+   |
|  | SwimProtocol   |  | WorkerRuntime          |   |
|  | (UDP, periodic)|  | (slice execution)      |   |
|  |                |  |                         |   |
|  | - ping/ack     |  | - Decision application |   |
|  | - membership   |  | - mutation forwarding  |   |
|  | - piggyback    |  | - endpoint reporting   |   |
|  +-------+--------+  +-----------+------------+   |
|          |                       |                 |
|          v                       v                 |
|  +-------+--------+  +-----------+------------+   |
|  | MembershipList  |  | GovernorConnection     |   |
|  | (local view)   |  | (TCP, to governor)     |   |
|  +----------------+  +------------------------+   |
+---------------------------------------------------+
```

SWIM runs independently of the governor connection. If the governor fails, SWIM continues operating. Workers detect governor failure through SWIM itself (governor is a group member).

---

## 4. Governor Protocol

### 4.1 Governor Role Definition

A governor is a regular worker that takes on additional relay responsibilities. The governor role is:
- **Stateless** -- all governor state can be reconstructed from the next Decision stream
- **Idempotent** -- duplicate relay during election transitions causes no harm
- **Ephemeral** -- governor failure causes re-election, not data loss

### 4.2 Governor Responsibilities

| Responsibility | Description | Frequency |
|---------------|-------------|-----------|
| **Decision relay** | Receive Decisions from core, fan out to group members | Per consensus round |
| **Health aggregation** | Run SWIM within group, report summary to core | Every 5s (configurable) |
| **Mutation proxy** | Forward group members' mutations to any core node | Per mutation |
| **Bootstrap** | Send KV snapshot to new workers joining the group | Per new worker |

### 4.3 Election Protocol

**REQ-GOV-01:** Election rule: lowest `NodeId` (lexicographic) among alive members wins.

**REQ-GOV-02:** Election is computed locally by each worker. No election messages needed -- every worker independently evaluates the SWIM membership list and determines who the governor should be.

**REQ-GOV-03:** Sticky incumbency: if the current governor is alive, it remains governor even if a new member with a lower NodeId joins. Re-election only occurs on governor failure.

**REQ-GOV-04:** Election is evaluated after every SWIM membership change:

```
Governor Election State Machine:

                    +-----------+
                    | NO_GROUP  |  <- initial state on startup
                    +-----+-----+
                          | SWIM join complete
                          v
                    +-----+-----+
          +-------->| FOLLOWER  |<--------+
          |         +-----+-----+         |
          |               |               |
          |    governor    |    new member with
          |    declared    |    lower NodeId AND
          |    faulty      |    current governor
          |               |    is faulty
          |               v               |
          |         +-----+-----+         |
          +-------- | GOVERNOR  |---------+
                    +-----------+
                          |
                    governor    self declared
                    steps down  faulty (unlikely)
                          |
                          v
                    +-----+-----+
                    |  LEAVING  |
                    +-----------+
```

**REQ-GOV-05:** Split-brain tolerance: if two workers both believe they are governor (brief window during re-election), both relay Decisions. Workers de-duplicate based on Decision sequence number. Mutations forwarded by either governor are idempotent (same KV command applied once).

### 4.4 Governor-Core Connection

**REQ-GOV-06:** The governor maintains TCP connections to ALL core nodes using the existing `TcpTopologyManager` and `NettyClusterNetwork` infrastructure.

**REQ-GOV-07:** The governor receives Decisions via the same `Decision` message route used by `PassiveNode`. It applies Decisions locally AND relays to group members.

**REQ-GOV-08:** The governor forwards mutations to ANY core node (Rabia is leaderless -- any node can propose). No leader tracking required.

### 4.5 Governor Health Report

**REQ-GOV-09:** Every `governor.health-report-interval` (default 5s), the governor sends a `WorkerGroupHealthReport` to core:

```
WorkerGroupHealthReport:
  groupId:        GroupId          // consistent hash group identifier
  governorId:     NodeId           // self
  memberCount:    int              // alive members in group
  aliveMembers:   List<NodeId>     // all alive member IDs
  suspectMembers: List<NodeId>     // currently suspect members
  sliceSummary:   Map<Artifact, int>  // artifact -> active instance count
  cpuAverage:     double           // average CPU across group
  memoryAverage:  double           // average memory across group
  timestamp:      long
```

Core nodes store this report in the Endpoint Registry (NOT in consensus KV-Store).

### 4.6 Governor Bootstrap Protocol

When a new worker joins a group, it needs the current KV-Store state:

```
New Worker                    Governor
    |                            |
    |--- JOIN_GROUP ------------>|
    |                            |
    |<-- KV_SNAPSHOT ------------|  (full KV-Store serialized)
    |                            |
    |<-- DECISION(seq=N) --------|  (ongoing Decisions from seq N)
    |                            |
    |    [apply snapshot]        |
    |    [apply Decisions]       |
    |    [activate slices]       |
    |                            |
    |--- BOOTSTRAP_COMPLETE ---->|
    |                            |
```

**REQ-GOV-10:** The governor takes a snapshot of its local KV-Store at sequence number N. It sends the snapshot to the new worker, then relays all subsequent Decisions. The worker applies the snapshot, then applies Decisions from sequence N+1 onward.

**REQ-GOV-11:** During bootstrap, the worker does NOT run slices. It transitions to active only after applying the snapshot and catching up to the current Decision sequence.

---

## 5. Group Formation and Splitting

### 5.1 Consistent Hashing for Group Assignment

**REQ-GRP-01:** Workers are assigned to groups using consistent hashing of their `NodeId`. The same consistent hash function used by the DHT (MurmurHash3) is reused.

**REQ-GRP-02:** Group assignment is deterministic -- every node independently computes the same grouping from the membership list.

### 5.2 Zone-Aware Grouping

**REQ-GRP-03:** NodeId format for workers includes a zone prefix: `{zone}-{unique}`. Example: `us-east-1a-w0042`.

**REQ-GRP-04:** The group hash includes the zone prefix, so workers in the same zone naturally cluster into the same group. This provides:
- Reduced cross-zone network traffic (SWIM ping/ack stays intra-zone)
- Lower latency for Decision relay (governor to workers within same zone)
- Zone-aware failure isolation

### 5.3 Group Splitting

**REQ-GRP-05:** Group splitting threshold: **100 members** (configurable via `worker.group.max-size`).

**REQ-GRP-06:** Splitting algorithm:

```
function computeGroups(members: List<NodeId>, maxSize: int) -> Map<GroupId, List<NodeId>>:
    // Start with 1 virtual group
    numGroups = 1

    // Double groups until all fit within maxSize
    while ceil(members.size / numGroups) > maxSize:
        numGroups *= 2

    // Assign each member to a group via hash
    for member in members:
        groupIndex = hash(member.id) % numGroups
        groups[GroupId(groupIndex)].add(member)

    return groups
```

**REQ-GRP-07:** Group splitting is emergent, not coordinated. Each worker independently computes group assignments. When a group exceeds the threshold, the consistent hash naturally distributes members across new groups.

### 5.4 Flat-to-Layered Transition

At small scale (< `max-size` workers), the architecture is effectively flat:

| Workers | Groups | Governors | Behavior |
|---------|--------|-----------|----------|
| 1-100 | 1 | 1 | Flat. Governor relays directly. |
| 101-200 | 2 | 2 | Two groups. Two governors. |
| 201-400 | 4 | 4 | Four groups. Zone-aligned. |
| 1000+ | 10+ | 10+ | Fully layered. |

No configuration change needed. The transition is continuous and automatic.

### 5.5 Group Stability

**REQ-GRP-08:** To prevent excessive re-grouping when nodes join/leave near the threshold boundary, apply hysteresis: split at `max-size` but merge only when a group drops below `max-size * 0.5`.

**REQ-GRP-09:** When a group splits, the existing governor retains governance of the group its NodeId hashes into. The other group triggers a new election.

---

## 6. KV-Store Split

### 6.1 Problem

The current KV-Store puts ALL state through Rabia consensus. At 10,000 workers with 200 slices and 10 methods:

| Entry Type | Count | Size |
|------------|-------|------|
| `EndpointKey/Value` | 60,000,000 | ~1.2 GB |
| `SliceNodeKey/Value` | 2,000,000 | ~80 MB |
| `HttpNodeRouteValue` (per-node per-route) | 10K x 500 = 5M | ~400 MB |
| **Total** | | **~1.5 GB** |

This is untenable. Consensus throughput, snapshot size, and memory usage all break.

### 6.2 Split Principle

**Desired state** stays in consensus. **Observed/runtime state** moves out.

### 6.3 What Stays in Consensus KV-Store

These entries are operator-driven or O(S) (slices) at most. Total stays under 1 MB regardless of worker count.

| Key Type | Value Type | Cardinality | Rationale |
|----------|-----------|-------------|-----------|
| `AppBlueprintKey` | `AppBlueprintValue` | O(B) ~50 | Desired deployment spec |
| `SliceTargetKey` | `SliceTargetValue` | O(S) ~200 | Scaling targets |
| `VersionRoutingKey` | `VersionRoutingValue` | Active updates | Traffic weights |
| `RollingUpdateKey` | `RollingUpdateValue` | Active updates | Update state machine |
| `PreviousVersionKey` | `PreviousVersionValue` | O(S) | Rollback support |
| `NodeLifecycleKey` | `NodeLifecycleValue` | O(C) core only | Core membership |
| `LogLevelKey` | `LogLevelValue` | Operator-driven | Config |
| `AlertThresholdKey` | `AlertThresholdValue` | Operator-driven | Config |
| `ObservabilityDepthKey` | `ObservabilityDepthValue` | Operator-driven | Config |
| `ConfigKey` | `ConfigValue` | Operator-driven | Config |
| `ScheduledTaskKey` | `ScheduledTaskValue` | O(SC) | Scheduled tasks |
| `TopicSubscriptionKey` | `TopicSubscriptionValue` | O(T x S) | Pub/sub |

### 6.4 What Moves Out

| Current Key Type | Problem | New Home |
|-----------------|---------|----------|
| `EndpointKey/Value` | O(N x S x M x I) | Worker Endpoint Registry |
| `SliceNodeKey/Value` | O(N x S) | Worker Endpoint Registry |
| `HttpNodeRouteKey/Value` | N x R entries grows to millions | Derived from endpoints |
| `NodeLifecycleKey` (passive) | O(N) passive nodes | SWIM membership |

### 6.5 Worker Endpoint Registry (New Component)

**REQ-KV-01:** Create `WorkerEndpointRegistry` -- a non-consensus, in-memory registry on core nodes.

**REQ-KV-02:** The registry is NOT replicated via Rabia. It is populated by governor health reports and maintained per-core-node.

**REQ-KV-03:** Architecture:

```
+------------------------------------------------------------+
|              Core Node (each core has one)                  |
|                                                             |
|  +--------------------+     +---------------------------+  |
|  | Consensus KV-Store |     | Worker Endpoint Registry  |  |
|  | (Rabia replicated) |     | (local, non-consensus)    |  |
|  |                    |     |                            |  |
|  | Blueprints         |     | Governor health reports    |  |
|  | SliceTargets       |     | Worker endpoints           |  |
|  | Config             |     | HTTP route derivations     |  |
|  | Routing rules      |     | Worker lifecycle states    |  |
|  +--------------------+     +---------------------------+  |
|          |                             ^                    |
|   Decisions broadcast                  |                    |
|   to all nodes                  Governor reports            |
|          |                      (TCP, periodic)             |
|          v                             |                    |
|  +--------------------+     +---------+--------+           |
|  | Workers (via       |     | Governors (TCP   |           |
|  |  governor relay)   |     |  connections)    |           |
|  +--------------------+     +------------------+           |
+------------------------------------------------------------+
```

**REQ-KV-04:** The leader's CDM uses the Worker Endpoint Registry for placement decisions. Non-leader core nodes use it for request routing (SliceInvoker can route to workers).

**REQ-KV-05:** Registry data is ephemeral. If a core node restarts, governors re-send their health reports within one `health-report-interval`.

### 6.6 Endpoint Aggregation via Governors

Instead of each worker writing O(S x M x I) endpoint entries to consensus, governors aggregate:

```
Governor aggregation:

1. Worker activates slice, registers endpoints LOCALLY
2. Worker reports local endpoints to governor (periodic or event-driven)
3. Governor aggregates endpoints for entire group
4. Governor sends WorkerGroupHealthReport to core (contains slice summary)
5. Core derives endpoint routing from aggregated reports

Result: Core sees O(G x S) entries where G = governor count (~10-100),
        NOT O(N x S x M x I) where N = worker count (~10,000)
```

### 6.7 HTTP Route Derivation

**REQ-KV-06:** `HttpNodeRouteKey/Value` is no longer stored directly. HTTP routes are derived from:
- Consensus: blueprint defines which slices expose HTTP routes (from `routes.toml`)
- Worker Endpoint Registry: which nodes have those slices active

The `HttpRouteRegistry` on each node constructs routes by combining these two sources.

### 6.8 Migration Path

**REQ-KV-07:** During Phase 1, the existing KV-Store entries (`EndpointKey`, `SliceNodeKey`, `HttpNodeRouteKey`) continue to function for CORE nodes only. Workers use the new registry path. This allows incremental migration.

**REQ-KV-08:** In Phase 2, core nodes also switch to the Worker Endpoint Registry for endpoint/route data, and the old KV-Store entries are deprecated.

---

## 7. CDM Scheduling Enhancement

### 7.1 Current CDM Architecture

The existing `ClusterDeploymentManager` (in `aether/aether-deployment/`) operates as a leader-only state machine:

- `Dormant` state when not leader
- `Active` state with blueprint tracking, slice state maps, round-robin allocation
- Allocates slices to `activeNodes` list
- Writes `SliceNodeKey/Value` to KV-Store to command nodes to load/unload slices

### 7.2 Pool-Aware CDM

**REQ-CDM-01:** The `Active` state gains awareness of worker pools via the Worker Endpoint Registry.

**REQ-CDM-02:** CDM allocation targets expand from `List<NodeId> activeNodes` to:

```java
record AllocationPool(
    List<NodeId> coreNodes,       // consensus participants
    List<NodeId> mainWorkers,     // always-on worker pool
    List<NodeId> spotWorkers      // elastic, preemptible workers
) {
    /// All schedulable nodes in priority order.
    List<NodeId> allNodes() {
        var all = new ArrayList<>(coreNodes);
        all.addAll(mainWorkers);
        all.addAll(spotWorkers);
        return List.copyOf(all);
    }
}
```

**REQ-CDM-03:** Placement strategy per slice, configurable in blueprint:

```toml
[slices.my-service]
artifact = "com.example:my-service:1.0.0"
instances = 10
placement = "workers-preferred"   # default
# Options:
#   "core-only"         - only on core nodes (current behavior)
#   "workers-preferred" - prefer workers, fall back to core
#   "workers-only"      - only on workers, never core
#   "spot-eligible"     - can run on spot instances
#   "all"               - any node
```

**REQ-CDM-04:** CDM issues placement directives to workers via Decisions. For worker slices, CDM writes a `WorkerSliceDirective` to the consensus KV-Store:

```java
/// New key type for worker slice placement directives.
record WorkerSliceDirectiveKey(Artifact artifact) implements AetherKey { ... }

/// Directive for workers to activate/deactivate slices.
/// Workers watch for directives targeting their pool/group.
record WorkerSliceDirectiveValue(
    Artifact artifact,
    int targetInstances,          // total desired across pool
    PlacementPolicy placement,    // which pool(s)
    long updatedAt
) implements AetherValue { ... }
```

**REQ-CDM-05:** Workers independently decide which instances to host. The governor coordinates instance assignment within its group using consistent hashing of `artifact + workerNodeId` to determine which workers in the group host which instances.

### 7.3 CDM Reconciliation for Workers

**REQ-CDM-06:** CDM reconciliation runs on:
- Leader change (full reconciliation)
- Governor health report received (check if slice counts match targets)
- Worker group membership change (governor reports member join/leave)

**REQ-CDM-07:** CDM does NOT track individual worker-to-slice assignments. It tracks aggregate counts per group. If Group A reports 8 instances of `my-service` but target is 10, CDM increases the directive. The governor handles which specific workers in Group A host the 2 additional instances.

---

## 8. Mutation Forwarding

### 8.1 Mutation Path

Workers cannot write to the consensus KV-Store directly (they are not Rabia participants). All mutations flow through core nodes.

```
Worker                Governor              Core (any)
  |                      |                     |
  |--[KVMutation]------->|                     |
  |                      |--[KVMutation]------>|
  |                      |                     |
  |                      |    (Rabia consensus |
  |                      |     round happens)  |
  |                      |                     |
  |                      |<--[Decision]--------|
  |<---[Decision relay]--|                     |
  |                      |                     |
  | (mutation visible    |                     |
  |  in local KV-Store)  |                     |
```

### 8.2 Direct Fallback

**REQ-MUT-01:** Workers know core node addresses from static configuration (same as `AetherPassiveLB` today).

**REQ-MUT-02:** If the governor is unavailable (re-election in progress), workers send mutations directly to any core node. This is the same path `AetherPassiveLB` uses today.

**REQ-MUT-03:** Mutation messages include a `correlationId` so the worker can match the eventual Decision to its submitted mutation.

### 8.3 Mutation Message Format

```java
/// Message from worker to governor (or directly to core) requesting a KV mutation.
record WorkerMutation(
    NodeId sourceWorker,
    String correlationId,      // UUID for tracking
    KVCommand<AetherKey> command   // Put/Remove
) { ... }

/// Response from core to governor (relayed to worker) confirming mutation was committed.
/// This is implicit: the worker sees its mutation reflected in the next Decision
/// containing its command. Explicit ACK is optional for latency-sensitive paths.
```

### 8.4 Rabia's Advantage for Mutation Forwarding

Rabia is leaderless -- any core node can accept proposals. The governor does not need to track which core node is the leader. It sends mutations to any connected core node. This simplifies the forwarding logic compared to leader-based consensus (Raft) where mutations must route to the leader.

### 8.5 Mutation Latency

| Path | Hops | Expected Latency |
|------|------|-----------------|
| Worker -> Governor -> Core -> Consensus | 3 | 2-5ms (same DC) |
| Worker -> Core (fallback) -> Consensus | 2 | 1-3ms (same DC) |
| Worker -> Governor -> Core (cross-region) | 3 | 50-200ms |

---

## 9. Decision Stream Relay

### 9.1 Core-to-Governor Delivery

**REQ-DEC-01:** Core nodes broadcast Decisions to all connected governors via the existing `Decision` message type in `NettyClusterNetwork`.

**REQ-DEC-02:** Each Decision carries a monotonically increasing sequence number (`Decision.sequenceNumber`). Governors track the last received sequence number.

### 9.2 Governor-to-Worker Fan-Out

**REQ-DEC-03:** The governor relays each Decision to all workers in its group via TCP connections.

**REQ-DEC-04:** Fan-out is sequential on the governor's event loop. For a group of 100 workers, the governor writes the Decision to 100 TCP connections. With Netty's non-blocking write, this completes in microseconds.

### 9.3 Sequence Tracking and Gap Detection

**REQ-DEC-05:** Workers track `lastAppliedSequence`. If a Decision arrives with `seq > lastApplied + 1`, a gap exists.

**REQ-DEC-06:** Gap recovery protocol:

```
Worker                  Governor
  |                        |
  | [gap detected:         |
  |  have seq 42,          |
  |  received seq 45]      |
  |                        |
  |--DECISION_GAP_REQ----->|
  |  (from=43, to=44)      |
  |                        |
  |<--DECISION_GAP_RESP----|
  |  [Decision 43]         |
  |  [Decision 44]         |
  |                        |
  | [apply 43, 44, 45      |
  |  in order]             |
```

**REQ-DEC-07:** The governor maintains a bounded Decision buffer (`governor.decision-buffer-size`, default 1000) for gap recovery. If the requested gap exceeds the buffer, the worker must re-bootstrap (full KV snapshot from governor).

### 9.4 Decision Filtering

**REQ-DEC-08:** [OPTIMIZATION] Governors MAY filter Decisions before relay:
- If a Decision contains only core-relevant entries (e.g., `NodeLifecycleKey` for core nodes), the governor can skip relay.
- This reduces worker-side processing for irrelevant Decisions.

Phase 1 does NOT implement filtering -- all Decisions are relayed. Filtering is a Phase 2 optimization.

---

## 10. Worker Slice Lifecycle

### 10.1 Slice Activation on Workers

Workers activate slices based on `WorkerSliceDirectiveValue` from the consensus KV-Store (received via Decision stream):

```
Worker Slice Activation State Machine:

                +----------+
                |  IDLE    |  <- no directive for this slice
                +----+-----+
                     | WorkerSliceDirective received
                     | AND worker is assigned this instance
                     v
                +----+-----+
                | LOADING  |  <- downloading artifact, classloading
                +----+-----+
                     |
          +----------+----------+
          | success              | failure
          v                      v
    +-----+------+        +-----+-----+
    | ACTIVATING |        |  FAILED   |
    +-----+------+        +-----------+
          | start() complete          ^ retry (limited)
          v                           |
    +-----+------+                    |
    |   ACTIVE   |--------------------+
    +-----+------+   start() timeout / error
          |
          | directive removed OR
          | worker leaving group
          v
    +-----+------+
    | DRAINING   |  <- stop accepting new requests
    +-----+------+
          | in-flight complete
          v
    +-----+------+
    | STOPPED    |
    +------------+
```

### 10.2 Instance Assignment within Group

**REQ-SLICE-01:** The governor assigns slice instances to workers within its group using consistent hashing:

```
function assignInstances(artifact: Artifact,
                         targetCount: int,
                         members: List<NodeId>) -> Map<NodeId, int>:

    // Hash each member to get a score for this artifact
    scores = members.map(m -> (m, hash(artifact.asString() + m.id())))
    scores.sortBy(score)

    // Assign round-robin from sorted list
    assignment = {}
    for i in 0..targetCount:
        nodeId = scores[i % scores.size].nodeId
        assignment[nodeId] = assignment.getOrDefault(nodeId, 0) + 1

    return assignment
```

**REQ-SLICE-02:** Workers compute the assignment independently (same deterministic algorithm). A worker activates instances if and only if the assignment maps instances to its NodeId.

**REQ-SLICE-03:** When group membership changes (worker joins/leaves), instance assignments recompute. Slices migrate from the departed worker to remaining workers automatically.

### 10.3 Artifact Resolution on Workers

**REQ-SLICE-04:** Workers resolve artifacts via the DHT-backed `ArtifactStore`. Workers are DHT clients -- they read artifacts from core nodes that own the relevant DHT partitions.

**REQ-SLICE-05:** Artifact download path:

```
Worker --[DHT GET(artifact-chunk-key)]--> Core Node (partition owner)
                                              |
                                         DHT responds with chunk
                                              |
Worker <--[chunk data]------------------------+
```

Workers reassemble chunks locally and load the JAR.

### 10.4 Endpoint Registration

**REQ-SLICE-06:** When a worker activates a slice instance, it does NOT write `EndpointKey/Value` to consensus. Instead, it registers locally and reports via the governor's health report.

**REQ-SLICE-07:** The worker maintains a local `EndpointRegistry` identical in interface to the core's `EndpointRegistry`. The `SliceInvoker` on workers uses this local registry for intra-group routing.

### 10.5 Request Routing to Workers

**REQ-SLICE-08:** External HTTP requests arrive at core nodes (or passive LB). The core node's `SliceInvoker` consults both:
1. The local `EndpointRegistry` (for core-hosted slices)
2. The `WorkerEndpointRegistry` (for worker-hosted slices)

**REQ-SLICE-09:** `SliceInvoker` routes to workers via the same binary protocol used for inter-core invocations (`HttpForwarder`). Workers maintain TCP connections to core nodes through the governor's network.

---

## 11. Spot Pool

### 11.1 Spot Workers

Spot instances provide 60-90% cost savings but can be terminated with 30-120 seconds notice (cloud provider dependent).

**REQ-SPOT-01:** Spot workers are regular workers with additional metadata:

```java
enum WorkerPoolType {
    MAIN,   // always-on, stable
    SPOT    // elastic, preemptible
}
```

**REQ-SPOT-02:** Workers declare their pool type at join time via configuration:

```toml
[worker]
pool-type = "spot"  # or "main"
```

### 11.2 Spot-Eligible Slices

**REQ-SPOT-03:** Blueprint declares which slices can run on spot instances:

```toml
[slices.analytics-worker]
artifact = "com.example:analytics:1.0.0"
instances = 20
placement = "spot-eligible"

[slices.payment-service]
artifact = "com.example:payments:1.0.0"
instances = 3
placement = "workers-preferred"  # NOT spot-eligible
```

### 11.3 Preemption Handling

**REQ-SPOT-04:** When a spot worker receives a preemption notice:

```
Cloud Provider               Spot Worker              Governor
     |                           |                       |
     |--termination notice------>|                       |
     | (30-120s warning)         |                       |
     |                           |                       |
     |                    [enter DRAINING]               |
     |                           |                       |
     |                    [stop accepting                |
     |                     new requests]                 |
     |                           |                       |
     |                    [complete in-flight            |
     |                     requests]                     |
     |                           |                       |
     |                           |--LEAVE_GROUP--------->|
     |                           |                       |
     |                           |            [re-assign instances
     |                           |             to remaining workers]
     |                           |                       |
     |                    [shutdown]                      |
     |                           |                       |
```

**REQ-SPOT-05:** The governor re-assigns the preempted worker's instances to remaining group members. If the group lacks capacity, CDM is notified (via health report showing under-provisioning) and may request additional spot workers from the cloud provider.

### 11.4 Spot Draining Budget

**REQ-SPOT-06:** Spot workers respect the same `disruptionBudget.minAvailable` constraint as core nodes during draining. If stopping a spot worker would violate the minimum, the worker delays shutdown until replacement instances are active elsewhere.

**REQ-SPOT-07:** If the cloud termination deadline arrives before graceful drain completes, the worker force-stops. This is acceptable because spot-eligible slices are explicitly designed to tolerate abrupt termination.

---

## 12. Failure Scenarios

### 12.1 Governor Failure

```
Scenario: Governor node crashes or becomes unresponsive.

Detection:
  - SWIM detects governor is unresponsive within 1-2 probe periods (1-2 seconds)
  - Workers receive FAULTY(governor) via piggybacked dissemination

Recovery:
  1. All workers evaluate election: next lowest NodeId becomes governor
  2. New governor connects to core nodes (has addresses from config)
  3. New governor receives Decision stream from core
  4. New governor sends first health report to core

Impact:
  - Decision relay paused for ~2-5 seconds (detection + election + connection)
  - Workers fall back to direct core connections during gap
  - Mutations: workers send directly to core until new governor ready
  - No data loss: Decisions are sequenced, gap recovery fills missed Decisions
  - Running slices continue executing (no slice disruption)

Duration: 2-5 seconds
Data loss: None
Slice disruption: None
```

### 12.2 Worker Failure

```
Scenario: Worker node crashes.

Detection:
  - SWIM detects within 1-2 probe periods
  - Governor receives FAULTY(worker) via SWIM membership change

Recovery:
  1. Governor re-computes instance assignments for group
  2. Remaining workers activate additional instances (from deterministic assignment)
  3. Governor sends updated health report to core
  4. CDM may adjust if group is under-provisioned

Impact:
  - Slice instances on failed worker unavailable for 1-5 seconds
  - Other workers pick up instances within seconds
  - No consensus disruption
  - No re-election unless failed worker was governor

Duration: 1-5 seconds for instance redistribution
Data loss: None (slices are stateless compute; state is in consensus/DHT)
```

### 12.3 Core Node Failure

```
Scenario: One core node crashes (quorum preserved).

Detection:
  - Rabia consensus detects (built-in)
  - Leader re-election if failed node was leader (virtually instantaneous)

Impact on Workers:
  - Governor connections to failed core: reconnect to remaining cores
  - Decision stream: continues from remaining cores
  - Mutations: forwarded to any remaining core
  - Worker slices: completely unaffected

Impact on Core:
  - Standard Rabia recovery: state reconstructed from quorum
  - CDM transfers to new leader if needed

Duration: < 1 second for consensus recovery
Worker disruption: None
```

### 12.4 Core Quorum Loss

```
Scenario: Majority of core nodes fail (< ceil(N/2) remain).

Detection:
  - Rabia consensus stalls (cannot form quorum)
  - Governors cannot receive new Decisions

Impact on Workers:
  - Running slices CONTINUE executing (no disruption to in-flight requests)
  - New mutations fail (consensus unavailable)
  - Decision stream stops (KV-Store becomes stale)
  - Workers operate on cached/stale state
  - SWIM continues (membership tracking independent of consensus)

Recovery:
  - Restore core nodes to re-establish quorum
  - Consensus resumes, Decision stream catches up
  - Workers apply backlog of Decisions

Duration: Until quorum restored (manual intervention likely required)
Worker disruption: Partial (reads work, writes fail)
```

### 12.5 Network Partition: Core-Worker Split

```
Scenario: Network partition separates core cluster from all workers.

Detection:
  - Governors lose TCP connections to all core nodes
  - Governor switches to PARTITION mode

Impact:
  - Workers continue running existing slices
  - SWIM within worker groups continues normally
  - No new Decisions arrive (stale state)
  - Mutations cannot be forwarded (no core connectivity)
  - Worker-to-worker invocations within same group still work
  - Cross-group invocations fail (route through core)

Recovery:
  - On partition heal, governors reconnect to core
  - Decision stream resumes, gap recovery fills missed Decisions
  - Buffered mutations retry

Duration: Until partition heals
```

### 12.6 Network Partition: Group Split

```
Scenario: Network partition splits a worker group in half.

Detection:
  - SWIM detects half the group as SUSPECT then FAULTY
  - Both halves may elect their own governor (split brain)

Impact:
  - Each half operates independently with its own governor
  - Both governors connect to core and relay Decisions
  - Duplicate health reports (core sees two reports for "same" group)
  - Instance assignments diverge (each half sees different membership)

Recovery:
  - On partition heal, SWIM merges membership
  - One governor steps down (deterministic election resolves)
  - Instance assignments recompute
  - Brief period of over-provisioning (both halves had full instance count)

Duration: Until partition heals
Safety: Over-provisioning is safe (idempotent slices). Core deduplicates endpoints.
```

### 12.7 Cascading Governor Failure

```
Scenario: Governor fails, and the worker that should become governor also fails.

Detection:
  - SWIM detects both as FAULTY
  - Remaining workers evaluate election with updated membership

Recovery:
  - Third-lowest NodeId becomes governor
  - Same recovery path as single governor failure
  - Workers fall back to direct core connections during extended election

Duration: 3-8 seconds (two SWIM detection cycles)
```

---

## 13. Phased Implementation Plan

### 13.1 Phase 1: Foundation (0.20.x)

**Goal:** Workers connect to core, run slices, with flat topology. One group, one governor. Exercises the full protocol at small scale.

#### Deliverables

| # | Component | Description | Module |
|---|-----------|-------------|--------|
| P1.1 | SWIM module | SWIM protocol implementation (UDP, membership, piggybacking) | `integrations/swim/` |
| P1.2 | Worker node runtime | Worker process: connects to core, receives Decisions, runs slices | `aether/worker/` |
| P1.3 | Governor election | Election protocol (lowest NodeId, sticky incumbent) | `aether/worker/` |
| P1.4 | Decision relay | Governor receives from core, fans out to group | `aether/worker/` |
| P1.5 | Mutation forwarding | Worker -> governor -> core path, direct fallback | `aether/worker/` |
| P1.6 | Worker Endpoint Registry | Non-consensus endpoint tracking on core | `aether/aether-invoke/` |
| P1.7 | CDM pool awareness | `AllocationPool` with core + worker lists, placement policies | `aether/aether-deployment/` |
| P1.8 | Worker bootstrap | KV snapshot from governor, sequence catch-up | `aether/worker/` |
| P1.9 | SliceInvoker routing | Route to workers via WorkerEndpointRegistry | `aether/aether-invoke/` |
| P1.10 | Worker configuration | `worker.toml` for pool type, core addresses, zone | `aether/worker/` |
| P1.11 | Management API | Worker list, worker health, worker endpoints | `aether/node/` |
| P1.12 | CLI commands | `aether workers list`, `aether workers health` | `aether/cli/` |
| P1.13 | SWIM for core nodes | SWIM health detection replacing TCP disconnect for core-to-core | `integrations/swim/` |
| P1.14 | Automatic topology growth | CDM auto-assigns core vs worker role based on topology config | `aether/aether-deployment/` |

#### Phase 1 Architecture

```
Core (5-9 nodes, unchanged)
  |
  +-- Governor (1 worker, elected)
        |
        +-- Workers (flat, all in one group)
              - Receive Decisions via governor
              - Send mutations via governor
              - SWIM for health within group
```

#### Phase 1 Non-Goals
- No auto-splitting (single group)
- No spot pool
- No zone awareness
- No Decision filtering
- Workers do NOT own DHT partitions

#### Phase 1 Verification

| Test | Description |
|------|-------------|
| Worker joins cluster | Worker connects, receives KV snapshot, activates slices |
| Governor election | Lowest NodeId becomes governor |
| Governor failure | New governor elected, workers reconnect, no slice disruption |
| Worker failure | SWIM detects, slices redistributed to remaining workers |
| Mutation forwarding | Worker mutation reaches consensus, Decision relayed back |
| Mixed routing | HTTP request at core routes to worker-hosted slice |
| Worker scaling | Add/remove workers, slices rebalance |
| Core auto-growth | Start 3-node cluster, add nodes, core grows to `core-max` then workers join |
| SWIM core detection | SWIM detects core node failure faster than TCP, triggers proper recovery |

### 13.2 Phase 2: Auto-Layering (0.21.x)

**Goal:** Groups split automatically when exceeding threshold. Multiple governors. Zone-aware grouping.

#### Deliverables

| # | Component | Description |
|---|-----------|-------------|
| P2.1 | Group splitting | Consistent hash-based group assignment with hysteresis |
| P2.2 | Zone-aware grouping | Zone prefix in NodeId, zone-aligned hashing |
| P2.3 | Multi-governor | Multiple groups with independent governors |
| P2.4 | ~~Spot pool~~ | ~~Spot worker support, preemption handling~~ — **Deferred to Phase 3** |
| P2.5 | ~~Decision filtering~~ | ~~Governor filters irrelevant Decisions before relay~~ — **Rejected:** high-cardinality keys already migrated to DHT; remaining KV keys are low-volume metadata. Filtering would introduce partial KV-Store state on workers, requiring resync on governor transitions — complexity outweighs negligible bandwidth savings |
| P2.6 | KV-Store migration | Core nodes switch to WorkerEndpointRegistry for all endpoint data |
| P2.7 | Cross-group routing | Worker-to-worker routing across groups via core |
| P2.8 | Dashboard integration | Worker pool visualization in management dashboard |

#### Phase 2 Architecture

```
Core (5-9 nodes)
  |
  +-- Governor A (zone-us-east-1a, 100 workers)
  |     +-- Workers (SWIM within group)
  |
  +-- Governor B (zone-us-east-1b, 80 workers)
  |     +-- Workers (SWIM within group)
  |
  +-- Governor C (zone-eu-west-1a, 50 workers)
        +-- Workers (SWIM within group)
```

### 13.3 Phase 3: Multi-Region (Future)

**Goal:** Governors as regional presence points. Cross-region topology.

#### Deliverables

| # | Component | Description |
|---|-----------|-------------|
| P3.1 | Cross-region governor-to-core | Governors connect to core over WAN |
| P3.2 | Regional SWIM pools | SWIM scoped to region, governor bridges regions |
| P3.3 | Regional read replicas | Workers serve reads from local KV replica, cross-region staleness |
| P3.4 | DHT worker participation | Workers optionally join DHT ring as storage participants |
| P3.5 | Multi-cluster bridge | Governor relays between independent Aether clusters |

---

## 14. Prior Art Comparison

### 14.1 Comparison Matrix

| Feature | Aether (proposed) | Kubernetes | Consul | Hazelcast | Akka |
|---------|-------------------|------------|--------|-----------|------|
| **Control plane** | Rabia (5-9) | etcd/Raft (3-5) | Raft (3-5) | None (peer) | Gossip + singleton |
| **Worker membership** | SWIM gossip | Heartbeat/lease | SWIM gossip | Gossip | Phi accrual |
| **Failure detection** | ~2s (SWIM) | ~50s (lease) | ~2s (SWIM) | Configurable | Adaptive |
| **Worker-to-control** | Governor relay | API server watch | Client agent RPC | Direct | Gossip |
| **Hierarchical** | Yes (governor groups) | No (flat) | No (flat gossip) | No (flat) | No (flat) |
| **Auto-layering** | Yes (emergent) | No | No | No | No |
| **Zone-aware groups** | Yes (hash prefix) | No (flat pool) | WAN gossip (servers) | No | No |
| **Consensus isolation** | Workers excluded | Workers excluded | Clients excluded | Lite members excluded | All participate |

### 14.2 Key Differentiators

**vs Kubernetes:** Aether uses SWIM (seconds) instead of lease heartbeats (minutes) for worker failure detection. Aether introduces hierarchical governor groups for 10K+ scale; K8s has a flat worker pool with API server as bottleneck.

**vs Consul:** Similar two-tier design (SWIM for membership, consensus for state). Aether adds governor-based relay for Decision fan-out. Consul's client agents forward RPCs; Aether governors aggregate and relay.

**vs Hazelcast:** Closest analog. Aether workers = Hazelcast lite members. Key difference: Aether adds hierarchical grouping (governors) to overcome Hazelcast's flat gossip limit (~1000 members).

**vs Akka:** Akka uses CRDT-based state replication and a coordinator singleton. Aether uses consensus for state and elected governors for relay. Aether's SWIM-based detection is more predictable than Akka's phi accrual detector.

### 14.3 Novel Aspects

1. **Automatic flat-to-layered transition** -- no configuration change needed. The system self-organizes as it grows.
2. **Leaderless consensus advantage** -- governors forward mutations to ANY core node. No leader tracking required. Simplifies forwarding compared to Raft-based systems.
3. **Governor as pure relay** -- no state, no scheduling, no data ownership. Simplest possible middle layer. Failure recovery is trivial (re-elect, reconnect).

---

## 15. Open Questions

### Resolved by This Spec

| Question | Resolution |
|----------|-----------|
| Gossip implementation | Java SWIM module (`integrations/swim/`) |
| Worker identity | Zone-prefixed NodeId, e.g., `us-east-1a-w0042` |
| Slice assignment granularity | Per-group directive from CDM, governor assigns within group |
| DHT participation | Workers are DHT clients only (Phase 1-2), optional ownership in Phase 3 |
| Worker-to-worker communication | Within group: direct. Cross-group: via core. |
| Decision stream catch-up | KV snapshot from governor + subsequent Decisions |
| Consensus core scaling | Out of scope for this feature (Rabia supports dynamic membership separately) |
| Node role assignment | Automatic: CDM assigns core (up to core-max) or worker role on join |
| Failure detection mechanism | SWIM for all node types, TCP for data transport only |
| Core scaling policy | Auto-grow to core-max, explicit-only shrink |

### Open (Requires Further Design)

| # | Question | Impact | Notes |
|---|----------|--------|-------|
| OQ-1 | **Worker artifact repository:** Should workers cache downloaded artifacts locally, or always fetch from DHT? | Phase 1 | Local caching is obvious optimization but adds disk management complexity. |
| OQ-2 | **Governor-to-governor communication:** Should governors communicate directly for cross-group routing, or always route through core? | Phase 2 | **Resolved (0.19.3):** GovernorMesh provides direct TCP connections between governors for DHT relay. Cross-community routing bypasses core entirely. |
| OQ-3 | **SWIM protocol tuning for large groups:** At 100+ members per group, should SWIM period increase to reduce network overhead? | Phase 2 | SWIM is O(1) per member, but absolute overhead still matters at scale. |
| OQ-4 | **Worker KV-Store scope:** Should workers receive ALL Decisions or only Decisions relevant to their assigned slices? | Phase 2 | **Rejected (0.19.3):** Workers keep full KV-Store replicas. Filtering would break governor transition simplicity (new governor would need KV-Store resync with core). High-cardinality keys already migrated to DHT, so remaining metadata volume is low. The complexity and edge cases of filtered state far outweigh the modest memory savings. |
| OQ-5 | **Observability on workers:** How do worker metrics (CPU, latency, error rates) flow to the dashboard? Via governor aggregation or direct push? | Phase 1 | **Resolved (0.19.3):** Event-based governor aggregation. Governors collect follower metrics via WorkerMetricsPing/Pong, evaluate locally with CommunityScalingEvaluator, and send CommunityScalingRequest to core only when scaling is needed. Zero baseline bandwidth. On-demand CommunityMetricsSnapshot for diagnostics/dashboard. |
| OQ-6 | **Worker management API:** Should workers expose a management API directly, or proxy all management through core? | Phase 1 | Direct API is simpler for debugging. Proxy is more secure. |
| OQ-7 | **Scheduled tasks on workers:** Can scheduled tasks run on worker nodes, or only on core? | Phase 2 | Leader-only tasks must run on core. All-node tasks could run on workers for better distribution. |
| OQ-8 | **Pub/sub on workers:** Can workers be pub/sub subscribers? Topic subscriptions are currently per-node in consensus. | Phase 2 | Would require moving `TopicSubscriptionKey` to the non-consensus registry or allowing workers to subscribe through governors. |

---

## 16. Automatic Topology Growth

### 16.1 Design Principle

Nodes do not need to know their role at boot. A new node connects to seed nodes, receives the current topology, and CDM assigns its role (core or worker) based on the cluster's needs. This means:

- **Single binary for all node types** -- role is runtime state, not deployment configuration. The same binary starts as core or worker depending on the cluster's current topology.
- **Slices and HTTP clients are topology-unaware** -- routing is transparent. A slice running on a worker invokes other slices the same way as on a core node. The `SliceInvoker` handles routing differences internally.
- **Growth is transparent** -- adding nodes to a cluster requires no configuration changes on existing nodes. New nodes connect to seed nodes and CDM places them automatically.

### 16.2 Declarative Topology Configuration

Topology boundaries are declared in cluster configuration:

```toml
[cluster.topology]
core-max = 9                    # stop adding core nodes after this
core-min = 3                    # minimum for quorum safety

[[cluster.topology.core-pinning]]
zone = "us-east-1a"
count = 3

[[cluster.topology.core-pinning]]
zone = "us-east-1b"
count = 2

[[cluster.topology.core-pinning]]
zone = "eu-west-1a"
count = 2

[cluster.topology.workers]
group-max-size = 100            # SWIM group split threshold
```

Core pinning expresses a preference for distributing core nodes across availability zones. The `count` per zone is a target, not a hard constraint (see REQ-TOPO-05).

### 16.3 Growth Lifecycle

| Cluster Size | Core | Workers | Governors | Behavior |
|---|---|---|---|---|
| 3 | 3 | 0 | 0 | Pure core, flat, current Aether |
| 5 | 5 | 0 | 0 | Larger core, still flat |
| 9 | 9 | 0 | 0 | Max core reached |
| 10 | 9 | 1 | 1 (self-governor) | First worker, trivial group |
| 50 | 9 | 41 | 1 | Single worker group |
| 150 | 9 | 141 | 2 | Auto-split into 2 groups |
| 10,000 | 9 | 9,991 | ~100 | Fully layered |

The transition from 3 core nodes to 10,000+ is continuous and automatic. No configuration change, no operator intervention, no restart.

### 16.4 Core Membership Growth Protocol

When a new node joins and core is below `core-max`:

1. **Announce:** Node connects to seed nodes, announces candidacy.
2. **Evaluate:** CDM (leader) evaluates: is core below `core-max`? Does the candidate satisfy pinning constraints (zone matching)?
3. **Propose:** If yes: CDM proposes a Rabia membership change (add node as voter).
4. **Commit:** Rabia consensus round commits the membership change.
5. **Participate:** New node begins participating in consensus.
6. **Overflow:** If core is full (`currentCoreCount >= core-max`): node joins as worker, governor election handles the rest.

This is a controlled, sequenced operation. Core growth happens at most 6 times in a cluster's lifetime (from 3 to 9). It is not a hot path.

### 16.5 Core Shrinking -- Explicit Only

Automatic core shrinking (9 to 7 to 5) is NOT supported. Removing a node from Rabia consensus is risky under load -- it changes quorum requirements and can cause brief unavailability if timed poorly.

If core was over-provisioned, leave it. Core nodes can also run slices, so they are not wasted capacity. The overhead of extra consensus participants at 9 nodes is negligible.

Core shrinking is an explicit operational action via Management API, never automatic.

### 16.6 Requirements

- **REQ-TOPO-01:** Nodes connect to seed nodes and receive role assignment from CDM. No pre-configured role.
- **REQ-TOPO-02:** CDM evaluates core candidacy on each new node join: `currentCoreCount < coreMax AND pinningConstraintsSatisfied(node)`.
- **REQ-TOPO-03:** Core promotion is a Rabia membership change -- requires consensus round to commit.
- **REQ-TOPO-04:** Worker assignment is immediate -- no consensus required, CDM notifies the node directly.
- **REQ-TOPO-05:** Pinning constraints are evaluated as preferences, not hard requirements. If no candidate in a pinned zone is available, the system fills core from any available zone.
- **REQ-TOPO-06:** Core shrinking requires explicit operator action via Management API (`POST /management/topology/shrink-core`).

---

## 17. SWIM as Universal Health Detection

### 17.1 Problem with TCP-Based Detection

The current implementation relies on TCP/IP disconnect detection for core-to-core failure detection. This is unreliable in modern deployment environments:

- **Containerized environments:** Network namespaces, overlay networks, and silent connection drops obscure real connectivity state.
- **Keepalive mismatches:** OS-level TCP keepalive settings and container runtime settings often conflict, leading to stale connections.
- **Variable detection latency:** TCP disconnect detection ranges from 15 seconds to 2+ minutes depending on OS, network configuration, and whether the failure is a crash vs. network partition.
- **False negatives:** Half-open connections where TCP considers the connection alive but data is not flowing. The remote process may have crashed while the local TCP stack retains the connection.

### 17.2 Unified SWIM Health Layer

SWIM replaces TCP disconnect as the PRIMARY failure detector for ALL node types:

- **Core to Core:** SWIM probes alongside Rabia messages.
- **Core to Governor:** SWIM probes alongside Decision stream.
- **Worker to Worker:** SWIM probes within group (already specified in Section 3).

TCP connections remain for DATA transport (Rabia messages, Decisions, mutations). SWIM is the HEALTH layer only. This separation of concerns means that a lost TCP connection triggers reconnection, not a topology change.

### 17.3 Architecture Change

```
Before (current):
  TCP connect/disconnect → TopologyChangeNotification.NodeDown/NodeUp

After (proposed):
  SWIM probe failure → TopologyChangeNotification.NodeDown
  SWIM probe success → TopologyChangeNotification.NodeUp
  TCP connection      → data transport only (no health signaling)
```

The change is in the SOURCE of health signals, not in how those signals are consumed. All downstream logic (Rabia recovery, leader election, CDM reconciliation) receives the same `TopologyChangeNotification` events.

### 17.4 Core-to-Core SWIM

The same SWIM protocol used for worker groups applies to core nodes, with tighter parameters for faster detection among consensus participants:

| Parameter | Core Value | Worker Value | Rationale |
|-----------|-----------|-------------|-----------|
| Probe period | 500ms | 1s | Faster detection for consensus participants |
| Probe timeout | 300ms | 500ms | Core nodes are typically co-located, lower latency |
| Indirect probes (K) | 3 | 3 | Same reliability guarantee |
| Suspect timeout | 3s | 5s | Quicker declaration for core nodes |

The core SWIM group consists of all core nodes (5-9 members). This is a very small group with negligible overhead: 5-9 probes per second total.

### 17.5 What Changes in Existing Code

- **`TcpTopologyManager`:** TCP disconnect no longer triggers `NodeDown`. It triggers a reconnection attempt only. The connection is considered unhealthy only when SWIM declares the remote node FAULTY.
- **`TopologyChangeNotification`:** Fired by SWIM membership changes, not by TCP events. The notification type and payload remain identical.
- **`NettyClusterNetwork`:** Connection loss enters a reconnect loop. No topology notification is emitted. If SWIM also declares the node FAULTY, the topology notification fires from the SWIM layer.
- **Rabia:** `NodeDown` from SWIM triggers the same recovery path as today (stall detection, re-election, state reconstruction).

### 17.6 What Stays the Same

- **Rabia consensus protocol** -- untouched. Rabia does not depend on the source of failure detection.
- **All message routing over TCP** -- unchanged. SWIM is health-only, not a data transport.
- **Leader election logic** -- same triggers, different source (SWIM instead of TCP).
- **Decision stream delivery** -- TCP connections with reconnect on failure.

### 17.7 Benefits

- **Uniform detection:** 1-2 seconds across all environments (bare metal, containers, VMs, multi-cloud). No dependency on OS TCP stack behavior.
- **Fewer false positives:** Indirect probes confirm before declaring failure. A single missed probe does not trigger a topology change.
- **Single protocol:** One health detection mechanism to configure, tune, and debug across the entire cluster.
- **Predictable timing:** SWIM parameters directly control detection latency. No hidden dependency on TCP keepalive or OS kernel settings.

### 17.8 Configuration

```toml
[cluster.swim]
enabled = true                  # default true; false falls back to TCP detection (migration)

[cluster.swim.core]
period = "500ms"                # probe period for core-to-core
probe-timeout = "300ms"         # direct ping timeout
indirect-probes = 3             # K parameter
suspect-timeout = "3s"          # time in SUSPECT before FAULTY

[cluster.swim.workers]
period = "1s"                   # probe period for worker groups
probe-timeout = "500ms"
indirect-probes = 3
suspect-timeout = "5s"
```

### 17.9 Migration Path

- **REQ-SWIM-MIGRATION-01:** `cluster.swim.enabled = true` activates SWIM health detection. When `false`, TCP disconnect detection remains active (backward compatibility during migration).
- **REQ-SWIM-MIGRATION-02:** During migration, both SWIM and TCP can be active simultaneously. SWIM takes priority -- if SWIM declares a node alive, TCP disconnect is treated as a reconnect event, not a failure.

### 17.10 SWIM Transport: UDP with TCP Fallback

The canonical SWIM protocol uses UDP for ping/ack messages. However, some container networking environments restrict or degrade UDP:

- **Kubernetes with certain CNI plugins:** UDP may traverse additional encapsulation layers (VXLAN, Geneve) adding latency and packet loss.
- **Cloud security groups / firewalls:** UDP ports may be blocked by default while TCP is allowed.
- **Service meshes (Istio, Linkerd):** Typically proxy TCP only; UDP bypasses the mesh entirely, losing observability.
- **Corporate networks:** UDP is sometimes blocked at the network level.

**REQ-SWIM-TRANSPORT-01:** The SWIM implementation supports two transport modes:

| Mode | Protocol | Use Case |
|------|----------|----------|
| `udp` (default) | UDP datagrams | Standard deployments, bare metal, most cloud environments |
| `tcp` | TCP connections with SWIM message framing | Restricted environments where UDP is blocked or unreliable |

**REQ-SWIM-TRANSPORT-02:** In TCP mode, SWIM messages (PING, ACK, PING-REQ) are framed as length-prefixed messages over persistent TCP connections. Each SWIM member maintains a TCP connection to its probe targets. Connection pooling keeps overhead manageable.

**REQ-SWIM-TRANSPORT-03:** TCP mode preserves all SWIM semantics -- probe periods, indirect probes, suspect timeouts, piggybacked dissemination. The only difference is the transport layer. Detection timing may be slightly different (TCP has connection-level flow control that UDP lacks), but the protocol behavior is identical.

**REQ-SWIM-TRANSPORT-04:** Transport mode is configured per cluster, not per node. All nodes in a cluster must use the same transport:

```toml
[cluster.swim]
transport = "udp"               # "udp" (default) or "tcp"
```

**REQ-SWIM-TRANSPORT-05:** Auto-detection mode (`transport = "auto"`) attempts UDP first. If UDP probes fail consistently during the first 3 probe periods (initial startup), the node falls back to TCP and logs a warning. This handles environments where UDP availability is unknown at deployment time.

### 17.11 Requirements

- **REQ-HEALTH-01:** SWIM is the primary health detection mechanism for all node types when enabled.
- **REQ-HEALTH-02:** TCP disconnection triggers reconnection, NOT topology change notifications.
- **REQ-HEALTH-03:** Core SWIM parameters are tighter than worker parameters (faster detection for consensus participants).
- **REQ-HEALTH-04:** SWIM configuration is per-tier (core vs worker) to allow independent tuning.
- **REQ-HEALTH-05:** Fallback to TCP detection is supported for migration via configuration flag.
- **REQ-HEALTH-06:** SWIM transport supports UDP (default), TCP (fallback), and auto-detection modes.

---

## References

### Regulatory and Standards
- [SWIM Protocol Paper (Cornell)](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf) -- Original SWIM paper, foundation for the membership protocol design
- [Making Gossip More Robust with Lifeguard (HashiCorp)](https://www.hashicorp.com/en/blog/making-gossip-more-robust-with-lifeguard) -- Lifeguard extensions to SWIM, reduces false positives

### Technical Documentation
- [Consul Architecture](https://developer.hashicorp.com/consul/docs/architecture) -- Two-tier design (Raft + SWIM) closest to proposed architecture
- [Hazelcast Lite Members](https://docs.hazelcast.com/hazelcast/5.6/maintain-cluster/lite-members) -- Closest existing analog to passive workers
- [Kubernetes Cluster Architecture](https://kubernetes.io/docs/concepts/architecture/) -- Control plane / worker separation reference
- [Akka Cluster Sharding](https://doc.akka.io/libraries/akka-core/current/typed/cluster-sharding.html) -- Coordinator singleton and shard allocation
- [Orleans Grain Placement](https://learn.microsoft.com/en-us/dotnet/orleans/grains/grain-placement) -- DHT-based grain directory
- [FoundationDB Architecture](https://apple.github.io/foundationdb/kv-architecture.html) -- Role separation and read/write path design
- [Scaling Raft (CockroachDB)](https://www.cockroachlabs.com/blog/scaling-raft/) -- MultiRaft and consensus scaling challenges

### Internal References
- [`integrations/cluster/.../passive/PassiveNode.java`](../../../integrations/cluster/src/main/java/org/pragmatica/cluster/node/passive/PassiveNode.java) -- Existing PassiveNode abstraction
- [`aether/lb/AetherPassiveLB.java`](../../lb/src/main/java/org/pragmatica/aether/lb/AetherPassiveLB.java) -- Working passive node implementation
- [`integrations/consensus/net/NodeRole.java`](../../../integrations/consensus/src/main/java/org/pragmatica/consensus/net/NodeRole.java) -- ACTIVE/PASSIVE role enum
- [`aether/aether-deployment/.../ClusterDeploymentManager.java`](../../aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java) -- CDM implementation to be extended
- [`aether/aether-invoke/.../EndpointRegistry.java`](../../aether-invoke/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java) -- Current endpoint discovery
- [`aether/aether-invoke/.../SliceInvoker.java`](../../aether-invoke/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java) -- Request routing
- [`aether/slice/.../AetherKey.java`](../../slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java) -- KV-Store key types
- [`aether/slice/.../AetherValue.java`](../../slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java) -- KV-Store value types
- [`aether/docs/internal/passive-worker-pool-research.md`](../internal/passive-worker-pool-research.md) -- Detailed research on 10 distributed systems
- [`aether/docs/internal/kv-store-scalability.md`](../internal/kv-store-scalability.md) -- KV-Store scalability analysis with concrete numbers
