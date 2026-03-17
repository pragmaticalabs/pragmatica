# Worker Pools and Two-Layer Topology

This document describes how Aether scales beyond the Rabia consensus limit of 5-9 nodes to support 10,000+ nodes.

## Problem

Rabia consensus uses all-to-all broadcast: O(N^2) messages per round. At 9 nodes that's 72 messages/round. At 100 nodes it would be 9,900 messages/round - unacceptable. Practical Rabia cluster size is 5-9 nodes.

Production workloads need tens to thousands of compute nodes.

## Solution: Two-Layer Topology

```mermaid
graph TB
    subgraph Core["Core Layer (5-9 nodes, Rabia)"]
        C1["Core 1<br/>(Leader)"]
        C2["Core 2"]
        C3["Core 3"]
        C4["Core 4"]
        C5["Core 5"]

        C1 <--> C2
        C2 <--> C3
        C3 <--> C4
        C4 <--> C5
        C5 <--> C1
        C1 <--> C3
        C2 <--> C4
        C3 <--> C5
        C1 <--> C4
        C2 <--> C5
    end

    subgraph WG1["Worker Group Alpha (SWIM)"]
        G1["Governor<br/>(lowest NodeId)"]
        W1["Worker 1"]
        W2["Worker 2"]
        W3["Worker 3"]
    end

    subgraph WG2["Worker Group Beta (SWIM)"]
        G2["Governor<br/>(lowest NodeId)"]
        W4["Worker 4"]
        W5["Worker 5"]
    end

    subgraph WG3["Worker Group Gamma (SWIM)"]
        G3["Governor"]
        W6["Worker 6"]
        W7["Worker 7"]
        W8["Worker 8"]
    end

    C1 -.->|"Decision stream"| G1
    C3 -.->|"Decision stream"| G2
    C5 -.->|"Decision stream"| G3

    G1 -.->|"Status reports"| C1
    G2 -.->|"Status reports"| C3
    G3 -.->|"Status reports"| C5

    style Core fill:#e1f5fe
    style WG1 fill:#e8f5e9
    style WG2 fill:#e8f5e9
    style WG3 fill:#e8f5e9
```

### Layer Responsibilities

| Layer | Size | Protocol | Owns | Runs |
|-------|------|----------|------|------|
| **Core** | 5-9 nodes | Rabia (all-to-all) | Blueprints, config, routing rules, control plane | CDM, DHT, artifact repository, infrastructure slices |
| **Worker Groups** | 10-50 nodes each | SWIM (gossip) | Local membership, health state | Application slices, governors |

### Complexity

| Operation | Core (N=5-9) | Worker Group (M=10-50) |
|-----------|-------------|----------------------|
| Consensus | O(N^2) per round | N/A (no consensus) |
| Membership | Rabia-based | O(1) per node (SWIM) |
| Health detection | Topology manager | O(1) per node (SWIM) |
| State replication | Full (KV-Store) | Decision stream relay |

## SWIM Protocol

Worker groups use SWIM (Scalable Weakly-consistent Infection-style Membership) for failure detection and membership tracking.

### Failure Detection

```mermaid
sequenceDiagram
    participant A as Node A
    participant T as Target Node
    participant D1 as Delegate 1
    participant D2 as Delegate 2

    Note over A: Probe round for Target

    A->>T: ping (UDP)

    alt Target responds
        T-->>A: ack
        Note over A: Target ALIVE
    else No response within timeout
        par Indirect probe
            A->>D1: ping-req(Target)
            A->>D2: ping-req(Target)
        end

        D1->>T: ping (UDP)
        D2->>T: ping (UDP)

        alt Any delegate gets response
            T-->>D1: ack
            D1-->>A: ack
            Note over A: Target ALIVE
        else No delegate response
            Note over A: Target SUSPECT
            Note over A: After suspect timeout:<br/>Target CONFIRM (dead)
        end
    end
```

### SWIM Properties

| Property | Value |
|----------|-------|
| Detection time | O(1) per node per round |
| False positive rate | Configurable via suspect timeout |
| Message overhead | Constant per node (not proportional to group size) |
| Dissemination | Piggybacked on ping/ack (zero extra messages) |

### Piggybacked Dissemination

Membership events (join, leave, suspect, confirm) are attached to existing ping/ack messages:

```mermaid
graph LR
    subgraph Ping["ping message"]
        P_Header["ping header"]
        P_Piggyback["Piggybacked events:<br/>- Node X joined<br/>- Node Y suspected"]
    end

    subgraph Ack["ack message"]
        A_Header["ack header"]
        A_Piggyback["Piggybacked events:<br/>- Node Y confirmed dead"]
    end
```

Each event has a counter. Events with higher counters override older ones. Events are piggy-backed until they've been included in enough messages (log N rounds) to ensure propagation.

## Governor Protocol

Each worker group has a **governor** - the single liaison between the group and the core layer.

### Governor Election

**Deterministic**: Governor is the alive node with the lowest `NodeId` in the group. No election messages needed.

```mermaid
graph TB
    subgraph Group["Worker Group"]
        N5["Node 5 (id=5)<br/>GOVERNOR"]
        N12["Node 12"]
        N23["Node 23"]
        N31["Node 31"]
    end

    Note["Governor = min(alive NodeIds)<br/>No election protocol<br/>Sticky: doesn't change unless<br/>current governor fails"]

    style N5 fill:#4caf50,color:#fff
```

**Sticky incumbent**: If the governor is alive, it stays governor even if a node with a lower ID joins. Governor only changes when the current one fails or leaves.

### GovernorState

```java
sealed interface GovernorState {
    record Governor(NodeId self) implements GovernorState {}
    record Follower(NodeId governorId) implements GovernorState {}
}
```

### Governor Responsibilities

```mermaid
graph TB
    subgraph Core["Core Layer"]
        CDM["ClusterDeploymentManager"]
        KV["KV-Store"]
    end

    subgraph Gov["Governor"]
        DS["DecisionRelay<br/>Buffered, gap recovery"]
        SR["GovernorAnnouncement<br/>Published to KV-Store"]
        MF["Mutation Forwarder"]
        SM["SWIM Manager"]
        GC["GovernorCleanup<br/>DHT entry tracking"]
        GR["GovernorReconciliation<br/>On election"]
    end

    subgraph Workers["Worker Nodes"]
        W1["Worker 1"]
        W2["Worker 2"]
    end

    KV -->|"Decision stream<br/>(via PassiveNode)"| DS
    DS -->|"Relay relevant<br/>changes"| W1
    DS -->|"Relay relevant<br/>changes"| W2

    W1 -->|"Mutations"| MF
    W2 -->|"Mutations"| MF
    MF -->|"Forward to core<br/>for consensus"| KV

    SM -->|"SWIM gossip (UDP)"| W1
    SM -->|"SWIM gossip (UDP)"| W2

    SR -->|"Community, members,<br/>TCP address"| KV

    GC -->|"Track node artifacts<br/>and routes"| W1
    GR -->|"Clean DHT on election"| GC

    style GR fill:#fff3e0
```

| Component | Description |
|-----------|-------------|
| **DecisionRelay** | Buffered relay with sequence tracking and gap recovery |
| **GovernorCleanup** | Tracks NodeArtifactKey/NodeRoutesKey per node for departure cleanup |
| **GovernorReconciliation** | One-time cleanup on election: removes DHT entries for dead nodes |
| **GovernorAnnouncement** | Publishes to KV-Store: communityId, members, TCP address |
| **GovernorMesh** | Cross-community connectivity via TCP for DHT routing |
| **WorkerBootstrap** | New workers request snapshot from governor, catch-up via decision stream |

## Group Formation

### Assignment

`GroupAssignment.computeGroups()` is deterministic and zone-aware:

1. Extract zone from NodeId (prefix before last dash, or `"local"` if no dash)
2. Group members by zone
3. If zone group <= `maxGroupSize` -> single group
4. Else -> split round-robin into `ceil(size/maxGroupSize)` subgroups
5. Return sorted map by `WorkerGroupId.communityId()`

```java
record WorkerGroupId(String groupName, String zone) {
    String communityId() { return groupName + ":" + zone; }
}
// Example: NodeId "worker-us-west-1" → zone "worker-us-west"
```

### Splitting

When a group exceeds its size limit:

```mermaid
graph LR
    subgraph Before["Group Alpha (45 nodes)"]
        G1["Governor + 44 workers"]
    end

    Before -->|"Split at threshold"| After

    subgraph After["Two groups"]
        subgraph A1["Group Alpha (23 nodes)"]
            GA["Governor A"]
        end
        subgraph A2["Group Alpha-2 (22 nodes)"]
            GB["Governor B"]
        end
    end
```

Split is coordinated by CDM (core leader):
1. CDM decides split point
2. CDM assigns new group IDs via consensus
3. Affected workers receive new group assignment
4. New governors elected deterministically
5. SWIM membership converges

## KV-Store Integration

Worker groups don't run their own consensus. State flows in two directions:

### Core to Workers (Decision Stream)

```mermaid
graph LR
    KV["Core KV-Store"] -->|"Consensus commits"| Gov["Governor"]
    Gov -->|"Filter: only relevant<br/>to this group"| W["Workers"]
```

The governor receives all KV-Store decisions and relays only those relevant to the group (e.g., slice assignments for nodes in this group).

### Workers to Core (Mutation Forwarding)

```mermaid
graph LR
    W["Worker"] -->|"State change<br/>(e.g., ACTIVE)"| Gov["Governor"]
    Gov -->|"Forward mutation"| Core["Core Node"]
    Core -->|"Propose via<br/>consensus"| KV["Core KV-Store"]
```

Workers cannot write to KV-Store directly. All mutations are forwarded through the governor to a core node for consensus.

### Observed State

Worker health and observed state is stored in the KV-Store (not in a separate DHT), using compound keys to minimize consensus overhead:

| Key | Description |
|-----|-------------|
| `NodeArtifactKey(nodeId, artifact)` | Compound: all artifact state for one node |
| `NodeRoutesKey(nodeId)` | Compound: all routes for one node |

Compound keys reduce KV-Store entries by ~10x compared to per-instance keys.

## Failure Scenarios

### Governor Failure

```mermaid
sequenceDiagram
    participant SWIM as SWIM Protocol
    participant Workers as Group Workers
    participant NewGov as New Governor<br/>(next lowest NodeId)
    participant Core as Core Layer

    SWIM->>SWIM: Governor missed pings
    SWIM->>Workers: Governor SUSPECT
    SWIM->>Workers: Governor CONFIRM (dead)

    Workers->>Workers: Deterministic election:<br/>next lowest alive NodeId

    NewGov->>Core: Register as new governor
    Core-->>NewGov: Decision stream resumed

    Note over NewGov: No election messages needed.<br/>All workers independently<br/>compute the same result.
```

### Worker Failure

1. SWIM detects worker failure (suspect -> confirm)
2. Governor reports to core CDM
3. CDM reallocates orphaned slices to other workers in the group (or other groups)

### Core Node Failure

- Core layer tolerates f < N/2 failures (4 failures in a 9-node core)
- Worker groups continue operating with cached state
- Decision stream resumes when core recovers
- Mutations queued at governor until core is available

### Network Partition (Core-Worker Split)

- Workers continue serving cached state
- No new deployments/updates until partition heals
- Mutations queued at governor
- SWIM continues within the worker group

## Phased Implementation

| Phase | Release | Content | Status |
|-------|---------|---------|--------|
| **Phase 1** | 0.20.0 | SWIM protocol, compound keys, governor election, basic worker groups | Complete |
| **Phase 2a** | 0.20.0 | Decision stream relay, mutation forwarding | Complete |
| **Phase 2b** | 0.20.0 | Group assignment, status reporting, CDM scheduling for workers | Complete |
| **Phase 2c** | 0.21.x | Spot pool (preemptible workers with graceful drain) | Planned |
| **Phase 3** | Future | Multi-region (geo-aware groups, cross-region replication) | Planned |

## Related Documents

- [01-consensus.md](01-consensus.md) - Rabia protocol (core layer)
- [04-networking.md](04-networking.md) - SWIM transport details
- [10-security.md](10-security.md) - SWIM encryption (AES-256-GCM)
- [Passive Worker Pools Spec](../specs/passive-worker-pools-spec.md) - Full specification
