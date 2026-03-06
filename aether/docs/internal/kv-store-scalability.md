# KV-Store Storage Scalability Assessment

## Overview

Assessment of data stored in the Rabia consensus KV-store, how it scales with cluster size, and architectural implications for the planned 2-layer cluster topology.

## Current KV-Store Contents

15 key types, 15 value types, all replicated to every node via Rabia consensus. Binary codec serialization (compact, code-generated).

### Per-Entry Size Estimates

| Value Type | Binary Size | Cardinality |
|---|---|---|
| `EndpointValue` | ~20B | N x S x M x I |
| `SliceNodeValue` | ~40B | N x S |
| `TopicSubscriptionValue` | ~20B | T x S x M |
| `NodeLifecycleValue` | ~12B | N |
| `LogLevelValue` | ~40B | operator-driven |
| `ConfigValue` | ~60B | operator-driven |
| `ObservabilityDepthValue` | ~50B | operator-driven |
| `AlertThresholdValue` | ~30B | operator-driven |
| `SliceTargetValue` | ~80B | S |
| `VersionRoutingValue` | ~80B | active updates |
| `PreviousVersionValue` | ~100B | S |
| `ScheduledTaskValue` | ~80B | SC |
| `RollingUpdateValue` | ~200B | active updates |
| `HttpRouteValue` | ~30B + 20B x N | R |
| `AppBlueprintValue` | variable | B |

Variables: N=nodes, S=slices, M=methods/slice, I=instances/method, B=blueprints, R=routes, T=topics, SC=scheduled tasks.

### Blueprint Size

A `ResolvedSlice` contains: `Artifact` (3 strings ~60B) + 2 ints + bool + `Set<Artifact>` dependencies. Per slice: ~100-200 bytes.

An `ExpandedBlueprint` is: `BlueprintId` (~40B) + `List<ResolvedSlice>`. No method signatures, no JAR content, no classpath — just artifact coordinates + dependency graph.

- 20-slice blueprint: ~3 KB
- 100-slice blueprint: ~15 KB

### Scenario Modeling (Current Single-Layer)

| Scenario | Nodes | Slices | Methods | Instances | Blueprints | Routes | KV Entries | Total Size |
|---|---|---|---|---|---|---|---|---|
| Dev (Forge) | 1 | 5 | 5 | 1 | 1 | 10 | ~60 | ~5 KB |
| Small prod | 3 | 20 | 5 | 3 | 3 | 50 | ~1,200 | ~50 KB |
| Medium prod | 10 | 50 | 8 | 5 | 10 | 200 | ~22,000 | ~600 KB |
| Large prod | 30 | 200 | 10 | 5 | 30 | 500 | ~320,000 | ~8 MB |

### Growth Drivers (ranked)

1. **Endpoints** — `N x S x M x I` is the fastest-growing dimension
2. **SliceNode state** — `N x S`
3. **HTTP routes** — `R` entries, each with `Set<NodeId>`
4. **Blueprints** — constant per cluster, not per node
5. **Everything else** — O(S) or operator-driven, negligible

### Consensus Replication Properties

- Full replication: every Put/Get/Remove goes through Rabia
- Double send: full batch in both Propose and Decision messages (liveness guarantee)
- Snapshot: full `HashMap<K,V>` serialized to `byte[]` for recovery
- No delta encoding: full values always
- No batch size limit: unbounded command accumulation per phase

### Verdict for Single-Layer

For intended scale (3-30 nodes, <200 slices): **no issues**. Total KV store stays under 10 MB. Snapshots are fast. Consensus messages are small.

---

## 2-Layer Cluster Topology

### Planned Architecture

```
Core layer:    5-11 nodes (consensus participants)
Passive layer: 0-10,000+ nodes (load handlers, no consensus)
```

### Impact on KV-Store

With 10,000 passive nodes running slices:

| Entry Type | Formula | At 10K nodes, 200 slices, 10 methods, 3 instances |
|---|---|---|
| EndpointKey | N x S x M x I | 60,000,000 entries (~1.2 GB) |
| SliceNodeKey | N x S | 2,000,000 entries (~80 MB) |
| HttpRouteValue | R x Set<NodeId> | 500 routes x 10K NodeIds (~200 MB) |

**Total: ~1.5 GB+** — untenable for consensus.

### What Breaks

1. **Consensus throughput** — 60M entries = 60M consensus rounds during initial deployment
2. **Snapshot size** — 1.5 GB for node recovery
3. **Full replication** — all 5-11 core nodes hold 1.5 GB+ in-memory; Propose+Decision double-send amplifies network cost
4. **HttpRouteValue** — `Set<NodeId>` with 10K entries per route; every node join/leave mutates every route entry through consensus

### Root Cause

The current design puts **per-instance, per-node state** into the consensus KV-store. This works when consensus participants = load handlers. With 2 layers, passive nodes generate O(N x S x M x I) state that must flow through a 5-11 node consensus bottleneck.

---

## Recommended Split for 2-Layer

### Stays in Consensus (desired state + config)

| Type | Entries | Rationale |
|---|---|---|
| AppBlueprintValue | B (~50) | Desired deployment spec |
| SliceTargetValue | S (~200) | Scaling targets |
| VersionRoutingValue | active updates | Traffic weights |
| RollingUpdateValue | active updates | Update state machine |
| PreviousVersionValue | S | Rollback support |
| LogLevelValue | operator-driven | Config |
| AlertThresholdValue | operator-driven | Config |
| ObservabilityDepthValue | operator-driven | Config |
| ConfigValue | operator-driven | Config |
| ScheduledTaskValue | SC | Config |
| NodeLifecycleValue | core nodes only | Core membership |

**Total: < 1 MB regardless of passive node count.**

### Moves Out of Consensus (observed/runtime state)

| Type | Problem | Alternative |
|---|---|---|
| EndpointKey/Value | O(N x S x M x I) | Passive nodes report to core; core maintains routing table outside consensus |
| SliceNodeKey/Value | O(N x S) | Passive nodes report state via heartbeat/gossip |
| HttpRouteKey/Value | Set<NodeId> grows to 10K | Derived from endpoint registry, not stored |
| NodeLifecycleValue (passive) | O(N) passive nodes | Tracked by core layer via connection state |

### Target Architecture

```
+---------------------------------------------+
|           Consensus KV-Store (Rabia)         |
|  Blueprints, targets, config, routing rules  |
|  O(B + S + config) = < 1 MB                 |
+----------------------+-----------------------+
                       | subscribed by
+----------------------v-----------------------+
|        Endpoint Registry (new component)     |
|  In-memory on core nodes, NOT consensus      |
|  Passive nodes push state via lightweight    |
|  protocol (heartbeat + delta updates)        |
|  Core nodes aggregate and route              |
+----------------------+-----------------------+
                       | query
+----------------------v-----------------------+
|           Passive Nodes (10K+)               |
|  Subscribe to blueprint/config changes       |
|  Report: slice state, endpoints, health      |
|  Receive: deployment commands, route tables  |
+---------------------------------------------+
```

The Endpoint Registry would be:
- A non-consensus, leader-managed in-memory structure on core nodes
- Fed by passive node heartbeats (push model, not consensus Put)
- Replicated core-to-core via simple leader-to-follower broadcast (no consensus — derived state)
- Passive nodes never write to consensus directly

### Comparison

| Metric | Current (all-in-consensus) | 2-Layer (split) |
|---|---|---|
| Consensus KV size | ~8 MB at 30 nodes | < 1 MB (constant) |
| Consensus ops/deploy | O(N x S x M x I) | O(B + S) |
| Snapshot recovery | Scales with N | Constant |
| Passive node count impact on consensus | N/A | Zero |

---

## Conclusion

The current all-in-consensus design is appropriate for the single-layer architecture (3-30 nodes). For the 2-layer topology, **EndpointKey, SliceNodeKey, and HttpRouteKey must be extracted out of consensus** into a separate registry. This is a clean separation: consensus holds desired state and configuration, the registry holds observed/runtime state. The consensus KV-store stays under 1 MB regardless of passive node count.
