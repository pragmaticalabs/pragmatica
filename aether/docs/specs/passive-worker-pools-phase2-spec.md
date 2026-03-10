# Passive Worker Pools -- Phase 2: DHT-Backed Runtime + Multi-Group Topology

| Field       | Value                                          |
|-------------|------------------------------------------------|
| Version     | 0.20.0                                         |
| Status      | Draft                                          |
| Author      | Sergiy Yevtushenko                             |
| Created     | 2026-03-10                                     |
| Updated     | 2026-03-10                                     |
| Depends on  | Phase 1 (v0.19.3)                              |
| Sub-phases  | P2a -> P2b -> P2c (strict ordering)            |

## Summary

Phase 2 introduces a DHT-backed ReplicatedMap as foundational infrastructure for runtime state (P2a), then builds multi-group topology with zone-aware grouping (P2b) and spot instance pools (P2c) on top. The DHT replaces the consensus KV-store for high-cardinality runtime data (endpoints, slice-node mappings, HTTP routes), eliminating the O(N) consensus bottleneck and enabling the architecture to support 10K worker nodes. Workers run any workload including scheduled tasks and pub/sub -- these capabilities are cross-cutting and not deferred.

## Background: Phase 1 Recap

Phase 1 (v0.19.3) delivered:

- **WorkerNode** -- passive cluster member receiving Decisions via `PassiveNode`, using SWIM for intra-worker failure detection and governor election.
  - `aether/worker/src/main/java/.../worker/WorkerNode.java`
- **GovernorElection** -- deterministic election: lowest ALIVE NodeId wins, sticky incumbent.
  - `aether/worker/src/main/java/.../worker/governor/GovernorElection.java`
- **DecisionRelay** -- governor fans out Decisions to followers; followers receive relayed Decisions.
  - `aether/worker/src/main/java/.../worker/governor/DecisionRelay.java`
- **MutationForwarder** -- workers forward mutations to core via governor.
  - `aether/worker/src/main/java/.../worker/mutation/MutationForwarder.java`
- **WorkerGroupHealthReport** -- governor sends periodic health reports to core with worker endpoint data.
  - `aether/aether-invoke/src/main/java/.../endpoint/WorkerGroupHealthReport.java`
- **WorkerEndpointRegistry** -- non-consensus registry populated by governor health reports, queried by `SliceInvoker`.
  - `aether/aether-invoke/src/main/java/.../endpoint/WorkerEndpointRegistry.java`
- **EndpointRegistry** -- consensus-backed registry for core node endpoints, watches KV-store `ValuePut`/`ValueRemove` notifications.
  - `aether/aether-invoke/src/main/java/.../endpoint/EndpointRegistry.java`
- **SliceInvoker** -- dual-registry lookup via `selectCoreOrWorkerEndpoint()` and `selectCoreOrWorkerEndpointOption()`.
  - `aether/aether-invoke/src/main/java/.../invoke/SliceInvoker.java`

### Phase 1 Limitations

1. **Single flat worker group** -- no multi-group, no zone awareness.
2. **Governor bottleneck** -- all worker mutations and health reports funnel through a single governor per group.
3. **Dual-registry pattern** -- `EndpointRegistry` (consensus-backed, core only) and `WorkerEndpointRegistry` (non-consensus, governor-fed) create separate code paths for core vs. worker endpoint resolution.
4. **Consensus KV-store scalability ceiling** -- at 10K nodes, endpoint entries alone (O(N x S x M x I)) would flood the consensus log. The current `KVStore` (`integrations/cluster/src/main/java/.../kvstore/KVStore.java`) replicates every put/remove through Rabia consensus -- O(60M) entries at scale is not viable.
5. **No spot instance support** -- no preemption handling or eviction-aware scheduling.

## Design Decisions

### Unchanged from Phase 1 spec

| # | Decision | Rationale |
|---|----------|-----------|
| DD-01 | Cloud Integration SPI available | Plug in cloud-specific APIs (ASG, spot signals) without core coupling |
| DD-02 | Scale target: 10K worker nodes | Drives all capacity math and architectural choices |
| DD-03 | Sub-phase ordering: strict P2a -> P2b -> P2c | Each phase builds on the prior; no parallel implementation |
| DD-04 | Cross-group routing for slice invocations: always through core | Core nodes are the only consensus participants; cross-group slice invocation calls route via core to maintain topology isolation. Does NOT apply to infrastructure traffic (see DD-16). |
| DD-05 | Workers run ANY load (scheduled tasks, pub/sub) | Not deferred to a future phase |

### NEW: Storage architecture

| # | Decision | Rationale |
|---|----------|-----------|
| DD-06 | DHT-backed ReplicatedMap replaces consensus KV-store for heavy runtime data | Consensus replication cost is O(N) per entry; DHT replication cost is O(RF) = O(3). At 60M entries, consensus is infeasible. |
| DD-07 | All non-spot nodes participate in DHT with full protocol support | Spot nodes excluded from replica selection to avoid replica rearrangement churn on preemption. Non-spot workers are full DHT participants. |
| DD-08 | Replication strategy: RF=3 with home-replica rule | 1 home replica (deterministic community-local placement) + 2 ring replicas (standard consistent hashing). Total write amplification = 3x. Home replica guarantees local reads for a community's own data. |
| DD-09 | Core nodes are regular DHT participants, NOT super-replicas | No special role. Same local LRU cache + DHT fallback as everyone else. Avoids O(N) storage on core. |
| DD-10 | Local LRU cache with TTL on every node | `ReplicatedMap` API: `.withLocalCache(Duration)` returns `CachedReplicatedMap<K, V>`. Local cache first, DHT fallback on miss, auto-populate. Unified for core and worker. |
| DD-11 | Startup optimization: replication cooldown | Boot with RF=1 (writer is sole replica). Serve traffic immediately. Background replication gradually builds to RF=3. Decouples availability from durability. |
| DD-12 | Three data types move from consensus to DHT | `EndpointKey/Value` (O(N x S x M x I)), `SliceNodeKey/Value` (O(N x S)), `HttpNodeRouteKey/Value` (O(N x R)). Everything else stays in consensus (<1MB). |
| DD-13 | Workers write their own endpoints directly to DHT | No governor batching. Each worker is a DHT participant and writes directly. Simpler than `WorkerGroupHealthReport`-based approach. |
| DD-14 | Separate named maps | `dht.createMap("endpoints", policy)`, `dht.createMap("slice-nodes", policy)`, `dht.createMap("http-routes", policy)`. Not a single map with key discrimination. |
| DD-15 | Generic `ReplicatedMap<K, V>` API | Usable for any future use case, not just endpoints. Infrastructure primitive. |

### NEW: Network and identity

| # | Decision | Rationale |
|---|----------|-----------|
| DD-16 | Governor-to-governor mesh for DHT traffic | Governors establish connections to all other governors for cross-community DHT operations. DD-04 applies to application-level slice invocations only; DHT is infrastructure traffic with its own routing topology. Eliminates core as relay for DHT operations. |
| DD-17 | Core nodes form community `core` | Core is just another community in the home-replica algorithm. `HomeReplicaResolver.extractCommunity()` returns `"core"` for core node keys. Ensures at least one replica of core-originated entries lands on a core peer. Unified model: every node belongs to a community. |

## Architecture

### Component Diagram

```
                    +-----------------------+
                    |    Consensus Layer    |
                    | (Rabia, KVStore)      |
                    | blueprints, targets,  |
                    | config, routing rules,|
                    | lifecycle, directives |
                    +-----------+-----------+
                                |
                    +-----------v-----------+
                    |    DHT Layer          |
                    | (ReplicatedMap)       |
                    | endpoints,            |
                    | slice-nodes,          |
                    | http-routes           |
                    +-----------+-----------+
                                |
          +---------------------+---------------------+
          |                     |                     |
+---------v---------+ +---------v---------+ +---------v---------+
| Core Node         | | Worker Node       | | Worker Node       |
| community: core   | | community:        | | (spot)            |
| - DHT participant | |   default:us-e-1a | | - DHT reader only |
| - Consensus voter | | - DHT participant | | - NOT replica     |
| - Local index     | | - Direct DHT      | |   target          |
| - SliceInvoker    | |   writes          | | - Local index     |
+---+---------------+ | - Local index     | +---+---------------+
    |                  +---+---------------+     |
    |                      |                     |
    +----------------------------------------------+
    |          Governor-to-Governor Mesh            |
    |  (DHT cross-community traffic, DD-16)        |
    +----------------------------------------------+
    |                      |                     |
+---v---------------+ +---v---------------+ +---v---------------+
| Governor          | | Governor          | | Governor          |
| Community A       | | Community B       | | Community C       |
| Connects to all   |<-->                 |<-->                 |
| other governors   | | Connects to all   | | Connects to all   |
| for DHT routing   | | other governors   | | other governors   |
+-------------------+ +-------------------+ +-------------------+
```

### DHT Network Topology (DD-16)

DHT traffic uses a dedicated routing topology separate from application-level slice invocation:

| Traffic Type | Path | Hops |
|---|---|---|
| Intra-community DHT | Worker A -> Worker B (direct) | 1 |
| Cross-community DHT | Worker -> Governor -> Target Governor -> Target Worker | 2 max |
| Core <-> Governor | Existing connections | 1 |
| Core <-> Core | Direct (community `core`) | 1 |

**Governor discovery:** Governors discover each other via core. Each governor reports its community membership to core; core distributes the full community-governor mapping. When a new community comes online or a governor changes, core pushes updated topology.

**Connection budget:** At 100 communities, each governor maintains ~100 connections to peer governors. This is trivial compared to the intra-community SWIM connections. No special connection pooling needed.

**Distinction from DD-04:** DD-04 states "cross-group routing through core" for slice invocations. This ensures topology isolation for application traffic. DHT is infrastructure traffic -- governors route it directly to avoid making core a bottleneck for high-volume DHT replication and read operations.

### Data Placement

```
Consensus KV-Store (< 1MB total, all nodes via Rabia):
  - AppBlueprintKey/Value        (O(B) ~ tens)
  - SliceTargetKey/Value         (O(S) ~ hundreds)
  - VersionRoutingKey/Value      (O(S) ~ hundreds)
  - RollingUpdateKey/Value       (O(U) ~ ones)
  - PreviousVersionKey/Value     (O(S) ~ hundreds)
  - LogLevelKey/Value            (O(L) ~ tens)
  - ConfigKey/Value              (O(C) ~ tens)
  - AlertThresholdKey/Value      (O(A) ~ tens)
  - ObservabilityDepthKey/Value  (O(D) ~ tens)
  - NodeLifecycleKey/Value       (O(N) ~ hundreds)
  - WorkerSliceDirectiveKey/Value (O(S) ~ hundreds)
  - ActivationDirectiveKey/Value (O(N) ~ hundreds)
  - TopicSubscriptionKey/Value   (O(T) ~ tens)
  - ScheduledTaskKey/Value       (O(K) ~ tens)

DHT ReplicatedMap (60M+ entries, distributed across N nodes):
  - "endpoints" map:   EndpointKey -> EndpointValue    O(N x S x M x I)
  - "slice-nodes" map: SliceNodeKey -> SliceNodeValue   O(N x S)
  - "http-routes" map: HttpNodeRouteKey -> HttpNodeRouteValue  O(N x R)
```

## P2a: DHT-Backed ReplicatedMap

### P2a.1 Generic ReplicatedMap API

A new generic `ReplicatedMap<K, V>` abstraction layered on top of the existing `integrations/dht` module. The DHT module provides raw `byte[]`-level operations; `ReplicatedMap` adds typed keys/values, serialization, named maps, local caching, and the home-replica rule.

#### New Module

```
aether/aether-dht/
  src/main/java/org/pragmatica/aether/dht/
    ReplicatedMap.java
    CachedReplicatedMap.java
    ReplicatedMapFactory.java
    ReplicationPolicy.java
    HomeReplicaResolver.java
    ReplicatedMapConfig.java
    MapSubscription.java
    ReplicatedMapError.java
```

#### Java API Signatures

```java
/// aether/aether-dht/src/main/java/.../dht/ReplicatedMap.java

/// Distributed map backed by DHT with typed keys and values.
/// Thread-safe. All operations return Promise for async execution.
///
/// @param <K> key type (must be serializable)
/// @param <V> value type (must be serializable)
public interface ReplicatedMap<K, V> {

    /// Store a key-value pair. Replicates to RF nodes per ReplicationPolicy.
    Promise<Unit> put(K key, V value);

    /// Retrieve a value by key. Reads from local replica or DHT.
    Promise<Option<V>> get(K key);

    /// Remove a key-value pair.
    Promise<Unit> remove(K key);

    /// Subscribe to changes matching a key predicate.
    /// Listener receives put and remove events.
    MapSubscription<K, V> subscribe(java.util.function.Predicate<K> keyFilter,
                                    MapListener<K, V> listener);

    /// Wrap this map with a local LRU cache.
    /// Reads check local cache first; misses fall through to DHT and auto-populate.
    /// Entries expire after the given TTL.
    CachedReplicatedMap<K, V> withLocalCache(java.time.Duration ttl);

    /// Get the map name (for monitoring/debugging).
    String name();

    /// Get approximate entry count across all replicas.
    Promise<Long> approximateSize();
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/CachedReplicatedMap.java

/// ReplicatedMap wrapper with local LRU cache.
/// All reads go to local cache first; misses go to DHT and auto-populate.
/// Writes go to DHT and update local cache atomically.
public interface CachedReplicatedMap<K, V> extends ReplicatedMap<K, V> {

    /// Get from local cache only (no DHT fallback).
    /// Useful for best-effort reads where staleness is acceptable.
    Option<V> getLocal(K key);

    /// Invalidate a specific key in the local cache.
    /// Does NOT remove from DHT.
    Unit invalidateLocal(K key);

    /// Invalidate all entries in the local cache.
    Unit invalidateAll();

    /// Get cache statistics (hits, misses, evictions).
    CacheStats stats();

    /// Cache statistics record.
    record CacheStats(long hits, long misses, long evictions, long size) {
        public static CacheStats cacheStats(long hits, long misses, long evictions, long size) {
            return new CacheStats(hits, misses, evictions, size);
        }
    }
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/ReplicatedMapFactory.java

/// Factory for creating named ReplicatedMap instances.
/// Each named map has its own key namespace in the DHT.
public interface ReplicatedMapFactory {

    /// Create a named replicated map with the given replication policy.
    ///
    /// @param name   map name (used as key prefix for DHT namespace isolation)
    /// @param policy replication policy (RF, home-replica rule, etc.)
    /// @param keyCodec    codec for serializing/deserializing keys
    /// @param valueCodec  codec for serializing/deserializing values
    /// @return a new ReplicatedMap instance
    <K, V> ReplicatedMap<K, V> createMap(String name,
                                          ReplicationPolicy policy,
                                          MapCodec<K> keyCodec,
                                          MapCodec<V> valueCodec);

    /// Codec interface for map key/value serialization.
    interface MapCodec<T> {
        byte[] encode(T value);
        T decode(byte[] bytes);
    }
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/ReplicationPolicy.java

/// Replication strategy for a ReplicatedMap.
///
/// @param replicationFactor total number of replicas per key (including home)
/// @param homeReplicaEnabled whether home-replica placement is active
/// @param excludeSpotNodes whether spot nodes are excluded from replica selection
public record ReplicationPolicy(int replicationFactor,
                                boolean homeReplicaEnabled,
                                boolean excludeSpotNodes) {

    /// Standard policy for runtime data: RF=3, home-replica, exclude spot.
    public static final ReplicationPolicy RUNTIME_DATA =
        new ReplicationPolicy(3, true, true);

    /// Create a custom policy.
    public static ReplicationPolicy replicationPolicy(int rf,
                                                       boolean homeReplica,
                                                       boolean excludeSpot) {
        return new ReplicationPolicy(rf, homeReplica, excludeSpot);
    }
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/HomeReplicaResolver.java

/// Determines the home replica node for a key based on community/zone encoding.
///
/// The home replica is the deterministic placement within the key's
/// "home community", extracted from the key's NodeId or zone encoding.
/// Example key encoding: zone-us-east-1-worker-7 -> home community is us-east-1.
///
/// Core nodes use community identifier "core" (DD-17). Every node belongs
/// to a community -- unified model with no special-casing.
public interface HomeReplicaResolver {

    /// Resolve the home community identifier from a key.
    /// Returns the community/zone string embedded in the key.
    /// For core node keys, returns "core" (DD-17).
    Option<String> extractCommunity(byte[] key);

    /// Select the home replica node for the given community.
    /// Picks a deterministic non-spot node within the community.
    /// Returns empty if no eligible node exists in the community.
    Option<NodeId> selectHomeNode(String community, java.util.Set<NodeId> eligibleNodes);
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/MapListener.java

/// Listener for ReplicatedMap change events.
public interface MapListener<K, V> {
    @SuppressWarnings("JBCT-RET-01") // Callback — void required by listener pattern
    void onPut(K key, V value);

    @SuppressWarnings("JBCT-RET-01") // Callback — void required by listener pattern
    void onRemove(K key);
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/MapSubscription.java

/// Handle for a ReplicatedMap subscription. Used to unsubscribe.
public interface MapSubscription<K, V> {
    /// Unsubscribe from further events.
    Unit unsubscribe();
}
```

```java
/// aether/aether-dht/src/main/java/.../dht/ReplicatedMapError.java

/// Error causes for ReplicatedMap operations.
public sealed interface ReplicatedMapError extends Cause {
    ReplicatedMapError MAP_NOT_FOUND = new MapNotFound();
    ReplicatedMapError SERIALIZATION_FAILED = new SerializationFailed();
    ReplicatedMapError REPLICATION_TIMEOUT = new ReplicationTimeout();

    record MapNotFound() implements ReplicatedMapError {
        @Override public String message() { return "Named map not found"; }
    }

    record SerializationFailed() implements ReplicatedMapError {
        @Override public String message() { return "Key/value serialization failed"; }
    }

    record ReplicationTimeout() implements ReplicatedMapError {
        @Override public String message() { return "Replication did not reach target RF within timeout"; }
    }
}
```

#### Integration with Existing DHT Module

The `ReplicatedMap` implementation delegates to the existing DHT infrastructure:

| Existing Class | Role in ReplicatedMap |
|---|---|
| `ConsistentHashRing<NodeId>` (`integrations/dht`) | Ring placement for the 2 ring replicas |
| `DHTNode` (`integrations/dht`) | Local storage operations |
| `DistributedDHTClient` (`integrations/dht`) | Remote put/get/remove with quorum |
| `DHTRebalancer` (`integrations/dht`) | Re-replication on node departure |
| `DHTAntiEntropy` (`integrations/dht`) | Periodic consistency repair |
| `DHTTopologyListener` (`integrations/dht`) | Ring membership updates on topology changes |
| `StorageEngine` (`integrations/dht/storage`) | Pluggable storage backend |
| `MemoryStorageEngine` (`integrations/dht/storage`) | Default in-memory backend |
| `DHTConfig` (`integrations/dht`) | Per-map quorum/RF configuration |
| `DHTMessage` (`integrations/dht`) | Inter-node protocol messages |
| `DHTError` (`integrations/dht`) | Error cause types |

New additions to `integrations/dht`:

- **Home-replica extension to `ConsistentHashRing`**: method to select N ring replicas + 1 deterministic home replica, with spot-node exclusion filter.
- **Namespace-prefixed storage**: `DHTNode` operations prefixed with map name to isolate named maps in the same storage engine.
- **Subscription support**: local storage engine fires change events on put/remove; `ReplicatedMap` aggregates local + remote events for subscribers.

### P2a.2 Replication Policy: Home-Replica Rule

For any key `K`:

1. **Home replica**: Extracted from the key's embedded NodeId/zone. For `EndpointKey("endpoints/mygroup:myslice:1.0.0/myMethod:0")`, the NodeId in the corresponding `EndpointValue` identifies the originating node. The home community is derived from the node's zone label (e.g., `us-east-1`). For core nodes, the community is `"core"` (DD-17). The home replica is a deterministic non-spot node in that community, selected by hashing the key within the community's node set.

2. **Ring replicas**: Standard consistent hashing via `ConsistentHashRing.nodesFor(key, 2)`, excluding the home replica node and any spot nodes.

3. **Total replicas**: 1 (home) + 2 (ring) = 3. Write amplification is constant 3x regardless of cluster size.

**Algorithm**:

```
function replicasFor(key, allNodes, spotNodes):
    community = extractCommunity(key)   // returns "core" for core node keys (DD-17)
    communityNodes = allNodes.filter(n -> zone(n) == community && n not in spotNodes)
    homeNode = communityNodes.sorted()[hash(key) % communityNodes.size()]

    ringCandidates = allNodes.filter(n -> n != homeNode && n not in spotNodes)
    ringNodes = consistentHashRing(ringCandidates).nodesFor(key, 2)

    return [homeNode] + ringNodes
```

**Edge cases**:
- Community has zero non-spot nodes: home replica falls back to ring replica #1 (any zone).
- Fewer than 3 non-spot nodes in entire cluster: RF degrades gracefully (same as existing `DHTConfig.effectiveReplicationFactor()`).
- Key has no community encoding (e.g., `SliceNodeKey`): all 3 replicas are ring replicas (home-replica rule disabled for that map).

### P2a.3 DHT Participation Rules

| Node Type | Community | DHT Ring Member | Replica Target | Can Read | Can Write |
|-----------|-----------|-----------------|----------------|----------|-----------|
| Core (non-spot) | `core` (DD-17) | Yes | Yes | Yes | Yes |
| Worker (non-spot) | `groupName:zone` | Yes | Yes | Yes | Yes (own endpoints) |
| Worker (spot) | `groupName:zone` | No | No | Yes (via DHT hop) | Yes (writer-only, RF=1 for own data) |

- Non-spot nodes call `ring.addNode(nodeId)` on join and `ring.removeNode(nodeId)` on leave (via `DHTTopologyListener`).
- Spot nodes never join the ring. They write their own endpoints with RF=1 (local only) and read via DHT client with remote hops.
- On spot node preemption, its RF=1 data vanishes. This is acceptable: the endpoints it registered are no longer valid anyway.

### P2a.4 Local Query Index

**Problem:** `SliceInvoker` needs "all endpoints for slice X, method Y" to round-robin among them. DHT is optimized for point lookups, not range queries. The `EndpointKey` format `endpoints/group:artifact:version/method:instance` means finding all endpoints for a slice+method would require scanning all instance keys across all nodes.

**Solution:** Every node maintains a **local query index** rebuilt from DHT subscription events. The DHT is used for **durability and replication**; the local index serves all read patterns at zero network hops.

#### How It Works

1. Each node subscribes to the `endpoints` ReplicatedMap (via `MapSubscription`).
2. On each `onPut` event, the node inserts the endpoint into its local index.
3. On each `onRemove` event, the node removes it from the local index.
4. All read patterns -- find-all, round-robin, exclude, affinity, version-weighted routing -- query the local index directly.

#### Data Structure

The local index is the same structure as the current `EndpointRegistry`:

```java
/// Map from (artifact, method) to the list of available endpoints with round-robin state.
Map<EndpointLookupKey, EndpointSelectionState> localIndex;

/// Where:
record EndpointLookupKey(ArtifactId artifact, String method) {}

/// Holds the endpoint list + round-robin counter + version-tagged subsets
class EndpointSelectionState {
    List<EndpointEntry> endpoints;
    AtomicInteger roundRobinCounter;
    Map<Version, List<EndpointEntry>> byVersion;  // for rolling update routing
}
```

#### EndpointRegistry: Repurposed, Not Replaced

`EndpointRegistry` is **preserved** as the local query index. Its role changes:

| Aspect | Phase 1 (current) | Phase 2 (P2a) |
|---|---|---|
| Data source | Consensus `ValuePut`/`ValueRemove` notifications | DHT `MapSubscription` events |
| Scope | Core node endpoints only | All endpoints (core + worker) |
| Query API | `findEndpoints()`, `selectEndpoint()`, `selectEndpointByAffinity()` | **Unchanged** -- same API |
| Consumers | `SliceInvoker` | **Unchanged** -- `SliceInvoker` still queries `EndpointRegistry` |

The migration is a data source swap, not an API change. `SliceInvoker` continues to call `EndpointRegistry.findEndpoints()` and `selectEndpoint()` -- the only change is how `EndpointRegistry` populates its internal map.

#### Startup and Recovery

On node restart:
1. Subscribe to DHT events for the `endpoints` map.
2. Request a snapshot from the DHT (full scan of local replicas + remote fetch for non-local data).
3. Build the local index from the snapshot.
4. Process incremental events as they arrive.
5. Anti-entropy fills any gaps from events missed during the snapshot window.

#### Rolling Update Routing (Resolved)

Rolling update routing (`selectEndpointWithFailover()` with version-weighted selection) works **unchanged** against the local index. The routing config stays in consensus (`VersionRoutingValue`); the endpoint data comes from the local index instead of consensus. The algorithm selects from `EndpointSelectionState.byVersion` -- same logic, different data source.

#### Affinity Routing (Resolved)

Affinity routing (`selectEndpointByAffinity(slice, method, nodeId)`) works **unchanged** against the local index. The local index holds all endpoints including node identity, so filtering by target node is a local operation -- identical to current `EndpointRegistry.selectEndpointByAffinity()`.

### P2a.5 Endpoint Lifecycle and Cleanup

When a worker crashes without removing its DHT entries, replicated entries persist on RF=3 nodes. Without cleanup, traffic routes to a dead worker causing failed invocations.

#### Governor SWIM-Triggered Cleanup

The governor handles endpoint cleanup on node failure, following the same pattern as CDM + deployments:

1. **SWIM detects DEAD**: Governor's SWIM membership monitor detects a node as DEAD.
2. **Governor removes entries**: Governor removes the dead node's entries from all DHT maps:
   - `endpoints` map: all `EndpointKey` entries owned by the dead node
   - `slice-nodes` map: all `SliceNodeKey` entries for the dead node
   - `http-routes` map: all `HttpNodeRouteKey` entries for the dead node
3. **Propagation**: DHT remove operations propagate to replicas; subscription events propagate to all nodes' local indices.
4. **Idempotent**: If multiple nodes detect the death simultaneously, duplicate removes are harmless (DHT remove is idempotent).

#### New Governor Reconciliation

When a new governor is elected (previous governor died or left):
1. New governor queries SWIM for current alive membership.
2. New governor queries DHT for all entries attributed to nodes in its community.
3. Entries for nodes NOT in SWIM alive set are removed from DHT.
4. This handles the case where the old governor died before completing cleanup.

#### Anti-Entropy Safety Net

Periodic anti-entropy (every 30s via `DHTAntiEntropy`) reconciles DHT entries against SWIM-known-alive nodes:
1. For each endpoint entry in local DHT storage, check if the owning node is ALIVE in any community's SWIM membership.
2. Orphaned entries (owning node not alive anywhere) are removed.
3. This catches edge cases: governor failover gaps, network partitions, split-brain recovery.

### P2a.6 Startup / Replication Cooldown

**Sequence**:

1. **Node boots**: joins DHT ring, creates local `StorageEngine`.
2. **RF=1 phase**: node writes its own endpoints to local storage only. `DHTConfig` is `SINGLE_NODE` (RF=1, WQ=1, RQ=1).
3. **Serving immediately**: other nodes can read this node's data via DHT hop to this node. Local cache misses trigger DHT reads.
4. **Background replication starts** (after configurable delay, default 10s):
   - Iterate local entries in batches.
   - For each entry, compute target replicas under `RUNTIME_DATA` policy (RF=3).
   - Push to replica nodes, rate-limited to `replication.cooldown.rate` entries/sec (default: 10,000/s).
5. **RF=3 reached**: switch `DHTConfig` to `DEFAULT` (RF=3, WQ=2, RQ=2). All subsequent writes replicate to 3 nodes.
6. **Anti-entropy** (`DHTAntiEntropy`) runs every 30s to repair any missed replications.

**Configuration** (in `aether.toml`):

```toml
[dht.replication]
cooldown-delay = "10s"
cooldown-rate = 10000     # entries/sec
target-rf = 3
```

### P2a.7 Migration from Consensus KV-Store

Three key types move from consensus to DHT:

| Key Type | Current Location | New Location |
|----------|-----------------|--------------|
| `EndpointKey` / `EndpointValue` | `KVStore` via `KVCommand.Put` | `endpoints` ReplicatedMap |
| `SliceNodeKey` / `SliceNodeValue` | `KVStore` via `KVCommand.Put` | `slice-nodes` ReplicatedMap |
| `HttpNodeRouteKey` / `HttpNodeRouteValue` | `KVStore` via `KVCommand.Put` | `http-routes` ReplicatedMap |

**What stays in consensus** (all < 1MB total):

- `AppBlueprintKey/Value`, `SliceTargetKey/Value`, `VersionRoutingKey/Value`, `RollingUpdateKey/Value`, `PreviousVersionKey/Value`, `LogLevelKey/Value`, `ConfigKey/Value`, `AlertThresholdKey/Value`, `ObservabilityDepthKey/Value`, `NodeLifecycleKey/Value`, `WorkerSliceDirectiveKey/Value`, `ActivationDirectiveKey/Value`, `TopicSubscriptionKey/Value`, `ScheduledTaskKey/Value`.

**Migration approach -- clean architectural cut, not a runtime toggle**:

1. **Remove consensus code paths** for the three migrated key types:
   - `EndpointRegistry` consensus notification handlers (`onEndpointPut()` / `onEndpointRemove()` triggered by `ValuePut`/`ValueRemove`) -- replaced by DHT `MapSubscription` feeding the same `EndpointRegistry` local index.
   - `KVNotificationRouter` handlers for `EndpointKey`, `SliceNodeKey`, `HttpNodeRouteKey` -- removed.
   - `KVCommand.Put`/`Remove` calls for these key types in `NodeDeploymentManager`, `HttpRoutePublisher`, etc. -- replaced by `ReplicatedMap.put()`/`remove()`.

2. **Replace dual registries with unified local index**:
   - `EndpointRegistry` -- **repurposed** (not replaced). Data source changes from consensus notifications to DHT `MapSubscription` events. Query API unchanged.
   - `WorkerEndpointRegistry` (non-consensus, governor-fed) -- **eliminated**. Workers write directly to DHT; all endpoints appear in `EndpointRegistry` via DHT subscription.
   - `WorkerGroupHealthReport` for endpoint delivery -- **eliminated**. Workers own their endpoint lifecycle via direct DHT writes.

3. **Simplify SliceInvoker**:
   - `selectCoreOrWorkerEndpoint()` (lines 1010-1022 of `SliceInvoker.java`) -- replaced by single `EndpointRegistry` lookup (which now contains all endpoints, core and worker).
   - `selectCoreOrWorkerEndpointOption()` (lines 673-678) -- same simplification.
   - Remove `workerEndpointRegistry` field and all `Option<WorkerEndpointRegistry>` handling.
   - `SliceInvoker` still uses `EndpointRegistry` for all queries -- the interface does not change.

4. **No flag-gating**: this is a version boundary change (0.19.x -> 0.20.0). Mixed-version clusters are not supported during this transition.

### P2a.8 What This Replaces

| Before (Phase 1) | After (P2a) |
|---|---|
| `EndpointRegistry` (consensus-backed, core only) | `EndpointRegistry` **repurposed as local query index**, fed by DHT `MapSubscription` instead of consensus notifications. Same API, broader scope (all nodes). |
| `WorkerEndpointRegistry` (non-consensus, governor-fed) | **Eliminated**; workers write directly to DHT, endpoints appear in `EndpointRegistry` via subscription |
| `WorkerGroupHealthReport` for endpoint delivery | **Eliminated**; workers write own endpoints |
| `selectCoreOrWorkerEndpoint()` dual-registry lookup | Single `EndpointRegistry` lookup (contains all endpoints) |
| `CoreEndpointReporter` (old P2c concept) | Unnecessary; unified storage |
| Flag-gated migration (old P2c concept) | Unnecessary; clean version cut |

## P2b: Multi-Group + Zone-Aware Grouping

> Renumbered from original P2a. Content preserved with updates for DHT infrastructure.

### P2b.1 Group Identity

A **worker group** is identified by `(groupName, zone)` -- a human-readable name combined with an availability zone or region label. Examples:

| groupName | zone | Description |
|-----------|------|-------------|
| `default` | `us-east-1a` | Default worker group in AZ us-east-1a |
| `gpu-pool` | `eu-west-1b` | GPU-capable workers in AZ eu-west-1b |
| `batch` | `us-east-1c` | Batch processing workers in AZ us-east-1c |

Group identity is configured at node startup via `aether.toml`:

```toml
[worker]
group-name = "default"
zone = "us-east-1a"
```

The combined `groupName:zone` string forms the **community identifier** used by the home-replica algorithm (DD-08). Core nodes use the fixed community identifier `"core"` (DD-17).

### P2b.2 Topology Model

```
Cluster
  +-- Core Community: core (DD-17)
  |     +-- Core Node 1 (consensus + DHT participant, home replicas for "core")
  |     +-- Core Node 2
  |     +-- Core Node 3
  +-- Worker Community: default:us-east-1a
  |     +-- Worker Node 1 (DHT participant, home replicas for this community)
  |     +-- Worker Node 2
  |     +-- ...
  +-- Worker Community: default:us-east-1b
  |     +-- Worker Node 50
  |     +-- ...
  +-- Worker Community: gpu-pool:eu-west-1b
        +-- Worker Node 100
        +-- ...
```

Each community elects its own governor independently (same `GovernorElection` algorithm: lowest ALIVE NodeId, sticky incumbent). Core nodes do not elect a governor -- they use consensus for coordination. Governor responsibilities in P2b:

- **Decision relay** within community (unchanged from Phase 1).
- **Mutation forwarding** to core (unchanged from Phase 1).
- **Health reporting** to core (membership only; endpoint delivery is now via DHT per P2a).
- **DHT cross-community routing** (DD-16) -- governor routes DHT traffic to/from peer governors.
- **SWIM-triggered cleanup** -- governor removes dead node entries from DHT (P2a.5).

### P2b.3 Node Registration

On startup, a worker node:

1. Receives activation directive from CDM via consensus (`ActivationDirectiveKey`/`Value`).
2. Joins SWIM group for its community.
3. Joins DHT ring (if non-spot).
4. Subscribes to DHT events and builds local query index (P2a.4).
5. Writes its own endpoints directly to the `endpoints` ReplicatedMap (via DHT).
6. Writes its `SliceNodeKey`/`Value` to the `slice-nodes` ReplicatedMap.
7. Governor reports community membership (node IDs, health) to core -- but NOT endpoint data.

### P2b.4 CDM Awareness

The `ClusterDeploymentManager` gains zone-awareness:

- **Placement policy**: blueprints can specify `placement: "zone:us-east-1a"` or `placement: "group:gpu-pool"` to constrain slice deployment.
- **Worker slice directives** (`WorkerSliceDirectiveKey`/`Value`) include the target community.
- **Instance distribution**: CDM distributes target instance count across communities proportionally to community size.

### P2b.5 Cross-Group Routing

**Slice invocations** across groups route through core (DD-04). The flow:

1. Worker A in community X invokes `SliceInvoker.invoke(artifact, method, request)`.
2. SliceInvoker queries `EndpointRegistry` (local query index, P2a.4).
3. If the target endpoint is on a node in community Y, the request goes to core (via governor of community X).
4. Core forwards to the target node (which may be in community Y).

**DHT traffic** across communities routes through the governor mesh (DD-16), NOT through core:

1. Worker A writes an endpoint to DHT.
2. DHT determines replica nodes include a node in community Y.
3. Replication: Worker A -> Governor of community X -> Governor of community Y -> target replica node.
4. Core is not involved in DHT replication traffic.

Within the same community, both slice invocations and DHT traffic use direct worker-to-worker communication.

### P2b.6 Configuration

```toml
[worker]
group-name = "default"
zone = "us-east-1a"

[worker.community]
# SWIM configuration for intra-community failure detection
swim-interval = "1s"
swim-suspect-timeout = "5s"

[blueprint.placement]
# Example placement constraints
"myapp:users" = "zone:us-east-1a"
"myapp:analytics" = "group:gpu-pool"
```

## P2c: Spot Pool

> Renumbered from original P2b. Content preserved with spot-DHT exclusion rule.

### P2c.1 Spot Node Characteristics

Spot/preemptible instances differ from on-demand:

| Property | On-Demand | Spot |
|----------|-----------|------|
| Availability | Guaranteed | Can be reclaimed at any time |
| Cost | Full price | 60-90% discount |
| Startup | Normal | Normal |
| Shutdown | Graceful | 2-minute warning (cloud-specific) |
| DHT ring membership | Full participant | **NOT a ring member** |
| DHT replica target | Yes | **No -- excluded from replica selection** |
| DHT reads | Local or DHT hop | DHT hop only (no local ring replicas) |
| DHT writes | RF=3 | RF=1 (writer-only, local) |

### P2c.2 Spot Node Identification

Configured at startup:

```toml
[worker]
group-name = "default"
zone = "us-east-1a"
spot = true
```

The `spot` flag propagates to:
- `ActivationDirectiveValue` (CDM marks the node as spot).
- DHT ring exclusion (node never calls `ring.addNode()`).
- `ReplicationPolicy.excludeSpotNodes = true` filters spots from `replicasFor()`.

### P2c.3 Preemption Handling

1. **Cloud SPI** delivers preemption signal (2-minute warning on AWS, 30s on GCP).
2. Node enters `DRAINING` state via `NodeLifecycleKey`/`Value` in consensus.
3. Node removes its endpoints from the `endpoints` ReplicatedMap.
4. Node removes its `SliceNodeKey`/`Value` from the `slice-nodes` ReplicatedMap.
5. Governor detects departure via SWIM and updates community membership report.
6. No DHT rebalancing needed -- spot node was never a ring member or replica target.

Step 6 is the key benefit of DD-07: preemption of spot nodes causes zero replica rearrangement in the DHT ring.

### P2c.4 Scheduling on Spot Nodes

- **Stateless workloads preferred**: slices that can tolerate sudden termination.
- **Scheduled tasks**: spot-hosted scheduled tasks should be `leaderOnly = false` to ensure another node picks up if spot is reclaimed.
- **Pub/sub subscribers**: spot-hosted subscribers get unsubscribed on preemption; other subscribers continue.

### P2c.5 Cloud Integration SPI

```java
/// Cloud-specific spot instance integration.
public interface SpotIntegrationProvider {
    /// Register a listener for preemption signals.
    /// The listener receives the estimated time until termination.
    Unit onPreemption(java.util.function.Consumer<java.time.Duration> listener);

    /// Check if the current node is a spot instance.
    boolean isSpot();

    /// Get the current spot price (for cost-aware scheduling).
    Promise<Option<Double>> currentSpotPrice();
}
```

Implementations: `AwsSpotProvider`, `GcpSpotProvider`, `NoOpSpotProvider` (for non-cloud).

## Worker Capabilities (Cross-Cutting)

### Scheduled Tasks on Workers

Workers run scheduled tasks via the same `ScheduledTaskRegistry` and `ScheduledTaskManager` used by core nodes:

- `aether/aether-invoke/src/main/java/.../invoke/ScheduledTaskRegistry.java`
- `aether/aether-invoke/src/main/java/.../invoke/ScheduledTaskManager.java`

Workers watch for `ScheduledTaskKey`/`Value` in consensus. When a task's target artifact is deployed on a worker, the worker's `ScheduledTaskManager` starts the timer. `leaderOnly` tasks run only on the community governor (workers elect a governor per community).

### Pub/Sub on Workers

Workers participate in pub/sub via `TopicSubscriptionRegistry`:

- `aether/aether-invoke/src/main/java/.../endpoint/TopicSubscriptionRegistry.java`

Workers register their topic subscriptions in consensus (`TopicSubscriptionKey`/`Value`). When a message is published, the publisher queries the subscription registry and routes to all subscribers (core or worker) via `ClusterNetwork`.

## Scale Considerations

### Entry Counts at 10K Nodes

Assumptions:
- N = 10,000 nodes
- S = 50 slices
- M = 5 methods per slice
- I = 2 instances per method per node
- R = 10 HTTP routes per node

| Map | Formula | Entries |
|-----|---------|---------|
| `endpoints` | N x S x M x I | 10,000 x 50 x 5 x 2 = **5,000,000** |
| `slice-nodes` | N x S | 10,000 x 50 = **500,000** |
| `http-routes` | N x R | 10,000 x 10 = **100,000** |
| **Total** | | **5,600,000** |

> Note: The 60M figure in DD-12 assumes a higher I (instances) count. With I=2, total is ~5.6M. With I=12 (high-density deployment), total reaches ~60M. Both are within DHT capacity.

### Per-Node DHT Storage

With RF=3 distributed across 10K nodes:

```
Per node = (RF / N) x TotalEntries x AvgEntrySize + HomeReplicas
         = (3 / 10,000) x 5,600,000 x ~200B + CommunityLocalReplicas
         = 1,680 x 200B + ~50KB
         = ~386KB + ~50KB
         = ~436KB
```

With 60M entries:
```
Per node = (3 / 10,000) x 60,000,000 x ~200B + ~500KB
         = 18,000 x 200B + ~500KB
         = ~3.6MB + ~500KB
         = ~4.1MB
```

Both are trivially small for modern servers.

### Per-Node Local Query Index Memory

Every node holds a **complete** local query index of all endpoints (rebuilt from DHT subscription events). This is a full copy, not partitioned.

```
Local index = TotalEndpoints x AvgEntrySize
            = 5,000,000 x ~200B = ~1GB (at I=2)
            = 60,000,000 x ~200B = ~12GB (at I=12)
```

The 12GB figure at I=12 is significant. Mitigations:

1. **Compact representation**: The local index stores `EndpointEntry` (nodeId + address + port + version), not the full DHT key/value. Realistic per-entry size is ~80B, not 200B:
   ```
   5,000,000 x 80B = ~400MB (I=2) -- acceptable
   60,000,000 x 80B = ~4.8GB (I=12) -- significant but feasible for server-class nodes
   ```

2. **Selective subscription**: Nodes can subscribe only to artifacts they need (slices they deploy or invoke). A node deploying 10 of 50 slices reduces index size by 80%.

3. **Index-per-community optimization** [FUTURE]: For very large clusters, the local index could be limited to same-community endpoints + cross-community endpoints for slices the node invokes. Not needed at I=2.

[ASSUMPTION] For initial implementation, every node holds the full local index. Selective subscription is a performance optimization added if memory becomes a concern.

### Write Amplification

- **DHT (P2a)**: 3x constant (RF=3). A single endpoint registration = 3 writes.
- **Consensus (Phase 1)**: O(N) -- every node processes every write through Rabia.
- **Improvement at 10K nodes**: 3 vs. 10,000 = **3,333x reduction** in write amplification.

### Read Latency

| Scenario | Latency |
|----------|---------|
| Local query index hit | 0 (in-process HashMap lookup) |
| Home replica hit (same community, point lookup) | 0 (local storage) |
| DHT hop (cross-community, point lookup) | 1 network round-trip |
| Cache miss + DHT hop + cache populate | 1 network round-trip + local write |

For endpoint lookups in `SliceInvoker`:
- **All invocations**: local query index hit (0 hops). The local index holds all endpoints.
- Point lookups via `CachedReplicatedMap` are used for `slice-nodes` and `http-routes` where range queries are not needed.

### DHT Ring Size

With 150 virtual nodes per physical node (current `ConsistentHashRing.VIRTUAL_NODES_PER_PHYSICAL`):

```
Ring entries = 10,000 nodes x 150 vnodes = 1,500,000 entries in TreeMap
Memory = 1,500,000 x ~40B = ~60MB per node
```

This is manageable. If memory becomes a concern at extreme scale, virtual node count can be reduced (50 vnodes still provides good distribution with 10K physical nodes).

### Governor Mesh Connection Budget

At 100 communities:
```
Connections per governor = 99 (one to each peer governor)
Total governor mesh connections = 100 x 99 / 2 = 4,950
```

Each connection is a single TCP connection with DHT message multiplexing. Trivial resource usage.

## Task Breakdown

### P2a Tasks (DHT-Backed ReplicatedMap)

| ID | Task | Depends On | Estimate |
|----|------|------------|----------|
| P2a-01 | Create `aether/aether-dht` module with `ReplicatedMap<K,V>` interface | -- | 2d |
| P2a-02 | Implement `ReplicatedMapFactory` with namespace-prefixed DHT operations | P2a-01 | 2d |
| P2a-03 | Implement `ReplicationPolicy` with home-replica resolver (including DD-17: core community) | P2a-01 | 3d |
| P2a-04 | Extend `ConsistentHashRing` with spot-node exclusion filter | P2a-03 | 1d |
| P2a-05 | Implement `CachedReplicatedMap` with LRU cache + TTL | P2a-02 | 2d |
| P2a-06 | Implement `MapSubscription` (local + remote event aggregation) | P2a-02 | 2d |
| P2a-07 | Implement replication cooldown (RF=1 -> RF=3 background process) | P2a-02, P2a-03 | 3d |
| P2a-08 | Implement local query index: `EndpointRegistry` fed by DHT subscription | P2a-06 | 3d |
| P2a-09 | Create `endpoints` map: wire `EndpointRegistry` to DHT subscription events | P2a-08, P2a-05 | 3d |
| P2a-10 | Create `slice-nodes` map: replace `SliceNodeKey` consensus wiring | P2a-05 | 2d |
| P2a-11 | Create `http-routes` map: replace `HttpNodeRouteKey` consensus wiring | P2a-05 | 2d |
| P2a-12 | Simplify `SliceInvoker`: remove dual-registry pattern, use unified `EndpointRegistry` | P2a-09 | 2d |
| P2a-13 | Eliminate `WorkerEndpointRegistry` and `WorkerGroupHealthReport` endpoint path | P2a-09, P2a-12 | 1d |
| P2a-14 | Implement governor SWIM-triggered cleanup (dead node entry removal from DHT) | P2a-09, P2a-06 | 2d |
| P2a-15 | Implement governor reconciliation on election (DHT vs. SWIM membership) | P2a-14 | 1d |
| P2a-16 | Implement anti-entropy safety net (periodic DHT vs. SWIM reconciliation) | P2a-14 | 1d |
| P2a-17 | Implement governor-to-governor mesh for DHT cross-community traffic (DD-16) | P2a-02 | 3d |
| P2a-18 | Governor discovery via core (community membership distribution) | P2a-17 | 1d |
| P2a-19 | Wire DHT participation into `WorkerNode` (non-spot workers join ring, write endpoints) | P2a-09 | 2d |
| P2a-20 | Wire DHT participation into `AetherNode` (core nodes join ring, community = `core`) | P2a-09, P2a-03 | 1d |
| P2a-21 | Add `dht.replication` configuration to `AetherNodeConfig` | P2a-07 | 1d |
| P2a-22 | Integration tests: ReplicatedMap operations (put/get/remove/subscribe) | P2a-06 | 2d |
| P2a-23 | Integration tests: local query index rebuild from DHT events | P2a-08 | 2d |
| P2a-24 | Integration tests: governor SWIM-triggered cleanup | P2a-14 | 2d |
| P2a-25 | Integration tests: governor-to-governor mesh DHT routing | P2a-17 | 2d |
| P2a-26 | Integration tests: replication cooldown and anti-entropy repair | P2a-07 | 2d |
| P2a-27 | Integration tests: endpoint migration (verify SliceInvoker works with DHT-fed EndpointRegistry) | P2a-12 | 2d |

**P2a Total: ~49 days**

### P2b Tasks (Multi-Group + Zone-Aware Grouping)

| ID | Task | Depends On | Estimate |
|----|------|------------|----------|
| P2b-01 | Add `groupName` and `zone` to worker configuration | P2a-19 | 1d |
| P2b-02 | Multi-community SWIM: separate SWIM groups per community | P2b-01 | 3d |
| P2b-03 | Multi-community governor election (one per community) | P2b-02 | 2d |
| P2b-04 | CDM zone-aware placement policy | P2b-01 | 3d |
| P2b-05 | Worker slice directives with community targeting | P2b-04 | 2d |
| P2b-06 | Cross-group routing through core (slice invocations) | P2b-03 | 2d |
| P2b-07 | Community membership reporting (governor -> core) | P2b-03 | 1d |
| P2b-08 | CLI commands for multi-group topology inspection | P2b-07 | 2d |
| P2b-09 | Integration tests: multi-community deployment and routing | P2b-06 | 3d |

**P2b Total: ~19 days**

### P2c Tasks (Spot Pool)

| ID | Task | Depends On | Estimate |
|----|------|------------|----------|
| P2c-01 | Add `spot` flag to worker configuration and activation directive | P2b-01 | 1d |
| P2c-02 | Spot-node DHT exclusion (skip `ring.addNode()`, RF=1 writes) | P2c-01, P2a-04 | 2d |
| P2c-03 | `SpotIntegrationProvider` SPI with AWS and GCP implementations | P2c-01 | 3d |
| P2c-04 | Preemption handler: drain + endpoint removal | P2c-03 | 2d |
| P2c-05 | CDM spot-aware scheduling (prefer on-demand, overflow to spot) | P2c-01 | 2d |
| P2c-06 | Integration tests: spot node lifecycle (join, write, preempt) | P2c-04 | 2d |
| P2c-07 | Integration tests: verify zero DHT rebalance on spot preemption | P2c-02, P2c-04 | 1d |

**P2c Total: ~13 days**

### Grand Total: ~81 days

## Testing Strategy

### Unit Tests

| Area | Tests |
|------|-------|
| `ReplicatedMap` API | put/get/remove/subscribe with mock DHT |
| `CachedReplicatedMap` | cache hit/miss/eviction/TTL expiry |
| `HomeReplicaResolver` | community extraction, node selection, edge cases, `"core"` community for core nodes |
| `ReplicationPolicy` | spot exclusion, RF degradation, home-replica fallback |
| Replication cooldown | RF=1 -> RF=3 transition, rate limiting |
| Local query index | Build from events, update on put/remove, round-robin correctness, version-weighted selection |
| `SliceInvoker` (simplified) | single `EndpointRegistry` lookup, no dual-registry |
| Governor cleanup | SWIM DEAD triggers DHT entry removal, idempotent removes |

### Integration Tests

| Area | Tests |
|------|-------|
| DHT ReplicatedMap end-to-end | Multi-node put/get/remove with real DHT |
| Anti-entropy repair | Inject inconsistency, verify repair |
| Replication cooldown | Boot cluster, verify RF=1 -> RF=3 |
| Local query index rebuild | Restart node, verify index rebuilds from DHT snapshot + events |
| Local query index consistency | Kill node, verify index removes dead node endpoints via subscription |
| Governor SWIM-triggered cleanup | Kill worker, verify governor removes entries from DHT |
| Governor reconciliation on election | Kill governor, verify new governor cleans up stale entries |
| Anti-entropy vs. SWIM membership | Inject orphan entries, verify periodic cleanup |
| Governor-to-governor mesh | Cross-community DHT put, verify replication without core involvement |
| Governor mesh discovery | Add new community, verify governors discover and connect |
| Endpoint migration | Deploy slice, verify endpoints in DHT (not consensus) |
| Multi-community routing | Cross-community invocation via core |
| Spot lifecycle | Join -> write -> preempt -> verify no rebalance |

### E2E Tests (Testcontainers)

| Scenario | Verification |
|----------|-------------|
| 3-core + 6-worker cluster | Endpoints in DHT, SliceInvoker routes correctly via local index |
| Worker join/leave | DHT rebalancing, endpoint availability, local index updates |
| Worker crash (no graceful shutdown) | Governor cleanup removes stale entries, local indices updated |
| Spot preemption | Zero rebalance, endpoint cleanup |
| Multi-community deployment | Zone-aware placement, cross-group routing |
| Governor failure | New governor reconciles, DHT cleanup completes |

## Migration and Rollback

### Forward Migration (0.19.x -> 0.20.0)

1. **Stop all 0.19.x nodes.** Mixed-version clusters are not supported for this transition.
2. **Deploy 0.20.0.** Core and worker nodes start with DHT enabled.
3. **On startup**, nodes write their endpoints to DHT instead of consensus.
4. **Local query index** builds from DHT subscription events -- replaces both `EndpointRegistry` consensus path and `WorkerEndpointRegistry`.
5. **Consensus KV-store** still holds blueprints, targets, etc. -- no migration needed for those.
6. **Stale endpoint entries** in consensus (from 0.19.x) are harmless: `KVNotificationRouter` no longer has handlers for `EndpointKey`, so they are ignored. They will be cleaned up by the next consensus snapshot.

### Rollback (0.20.0 -> 0.19.x)

1. **Stop all 0.20.0 nodes.**
2. **Deploy 0.19.x.** Nodes resume writing endpoints to consensus.
3. **DHT data is ephemeral** -- in-memory storage vanishes on shutdown. No cleanup needed.
4. **Consensus state is intact** -- blueprints, targets, etc. were never migrated.

### Zero-Downtime Migration (Future Enhancement)

[ASSUMPTION] Zero-downtime migration between 0.19.x and 0.20.0 is NOT required for initial release. If needed later, it can be achieved by:
- Running DHT in parallel with consensus for the three migrated key types.
- Feature flag to switch reads from consensus to DHT.
- Once all nodes are 0.20.0, disable consensus writes for migrated types.

## Open Issues (Resolved)

The following issues were identified during architectural review and have been resolved with design decisions incorporated into this spec:

| Issue | Resolution | Spec Section |
|---|---|---|
| Endpoint selection is a range query, not a point lookup | Local query index fed by DHT subscriptions | P2a.4 |
| Stale endpoint cleanup on worker crash | Governor SWIM-triggered cleanup + anti-entropy safety net | P2a.5 |
| Rolling update routing with DHT storage | Works unchanged against local query index | P2a.4 (Rolling Update Routing) |
| Affinity routing with DHT storage | Works unchanged against local query index | P2a.4 (Affinity Routing) |
| DHT network transport for workers | Governor-to-governor mesh (DD-16) | DD-16, DHT Network Topology |
| Core node home replica community | Core nodes form community `"core"` (DD-17) | DD-17, P2a.2, P2a.3 |

## References

### Internal References

| Reference | Path |
|-----------|------|
| Phase 1 spec | `aether/docs/specs/passive-worker-pools-spec.md` |
| Phase 2 open issues (resolved) | `aether/docs/internal/phase2-open-issues.md` |
| DHT module | `integrations/dht/` |
| DHTConfig | `integrations/dht/src/main/java/org/pragmatica/dht/DHTConfig.java` |
| ConsistentHashRing | `integrations/dht/src/main/java/org/pragmatica/dht/ConsistentHashRing.java` |
| DHTNode | `integrations/dht/src/main/java/org/pragmatica/dht/DHTNode.java` |
| DHTClient | `integrations/dht/src/main/java/org/pragmatica/dht/DHTClient.java` |
| DistributedDHTClient | `integrations/dht/src/main/java/org/pragmatica/dht/DistributedDHTClient.java` |
| DHTRebalancer | `integrations/dht/src/main/java/org/pragmatica/dht/DHTRebalancer.java` |
| DHTAntiEntropy | `integrations/dht/src/main/java/org/pragmatica/dht/DHTAntiEntropy.java` |
| DHTTopologyListener | `integrations/dht/src/main/java/org/pragmatica/dht/DHTTopologyListener.java` |
| StorageEngine | `integrations/dht/src/main/java/org/pragmatica/dht/storage/StorageEngine.java` |
| MemoryStorageEngine | `integrations/dht/src/main/java/org/pragmatica/dht/storage/MemoryStorageEngine.java` |
| DHTMessage | `integrations/dht/src/main/java/org/pragmatica/dht/DHTMessage.java` |
| DHTError | `integrations/dht/src/main/java/org/pragmatica/dht/DHTError.java` |
| KVStore | `integrations/cluster/src/main/java/org/pragmatica/cluster/state/kvstore/KVStore.java` |
| KVCommand | `integrations/cluster/src/main/java/org/pragmatica/cluster/state/kvstore/KVCommand.java` |
| KVNotificationRouter | `integrations/cluster/src/main/java/org/pragmatica/cluster/state/kvstore/KVNotificationRouter.java` |
| AetherKey | `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java` |
| AetherValue | `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` |
| EndpointRegistry | `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java` |
| WorkerEndpointRegistry | `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerEndpointRegistry.java` |
| WorkerGroupHealthReport | `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerGroupHealthReport.java` |
| WorkerEndpointEntry | `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerEndpointEntry.java` |
| SliceInvoker | `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java` |
| Feature catalog | `aether/docs/reference/feature-catalog.md` |
| Development priorities | `aether/docs/internal/progress/development-priorities.md` |

### Technical Documentation

| Reference | Description |
|-----------|-------------|
| [Consistent Hashing](https://en.wikipedia.org/wiki/Consistent_hashing) | Foundation for DHT ring placement |
| [Amazon DynamoDB Paper](https://www.allthingsdistributed.com/2007/10/amazons_dynamo.html) | DHT design inspiration (quorum, virtual nodes, anti-entropy) |
| [AWS Spot Instance Interruptions](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-interruptions.html) | Spot preemption signal handling |
| [GCP Preemptible VMs](https://cloud.google.com/compute/docs/instances/preemptible) | GCP spot equivalent |
