# Infrastructure Services

## Overview

Infrastructure Services are special slices that provide cluster-wide platform capabilities. Unlike application slices, they are deployed automatically from an infrastructure repository when required by application blueprints.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Application Blueprint                                           │
│  requires: [artifact-repo, http-routing]                        │
│  slices: [order-service, inventory-service, ...]                │
└─────────────────────────────────────────────────────────────────┘
                              ↓
                    (auto-resolved from)
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Infrastructure Service Repository                               │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │artifact-repo│ │http-routing │ │  caching    │ ...           │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Aether Core                                                     │
│  consensus (Rabia) | slice lifecycle | metrics | decision tree  │
└─────────────────────────────────────────────────────────────────┘
```

## Key Principles

### 1. Infrastructure Services ARE Slices

No special runtime treatment. Infrastructure services follow the same:
- Slice interface
- Lifecycle (load → activate → active)
- Inter-slice communication
- Monitoring and metrics

The only difference is deployment order and source (infra repo vs application artifact).

### 2. Automatic Resolution

When Aether loads an application blueprint with `requires: [artifact-repo]`:
1. Check if `artifact-repo` service is already running
2. If not, load from infrastructure repository
3. If not in repository, fail blueprint deployment
4. Start infrastructure service before application slices

### 3. Unified Discovery

Application slices find infrastructure services using the same mechanisms as inter-slice calls:
- Well-known service names (e.g., `artifact-repo`, `caching`)
- Standard SliceInvoker API
- Same retry and timeout handling

## Planned Infrastructure Services

### Priority 1: Artifact Repository

**Purpose**: Maven-compatible repository for deploying and retrieving slice artifacts.

**Value**:
- Eliminates need for external Nexus/Artifactory/Archiva
- Self-contained deployment story
- Enables air-gapped deployments
- First-run experience: `aether deploy my-app.jar` just works

**Interface**:
```java
public interface ArtifactRepository {
    // Deploy artifact
    Promise<Unit> deploy(Artifact artifact, byte[] content);

    // Resolve artifact
    Promise<byte[]> resolve(Artifact artifact);

    // List versions
    Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId);

    // Check existence
    Promise<Boolean> exists(Artifact artifact);
}
```

**Protocol**: Subset of Maven repository protocol (enough for deploy + resolve).

**Storage**: Distributed hash map with persistence (see Foundation section).

### Priority 2: HTTP Routing Service

**Purpose**: External HTTP request routing to slices.

**Value**:
- Removes routing configuration from blueprint
- Slices self-register routes on startup
- Enables dynamic routing changes
- Cleaner separation of concerns

**Current** (routing in blueprint):
```
POST:/api/orders => place-order:placeOrder(body)
GET:/api/orders/{orderId} => get-order-status:getOrderStatus(orderId)
```

**Future** (slices self-register):
```java
// In PlaceOrderSlice.start()
httpRouting.register(
    Route.post("/api/orders")
         .handler(this::placeOrder)
         .binding(Body.class)
);
```

**Blueprint becomes**:
```toml
[blueprint]
name = "order-system"
requires = ["http-routing"]

[slices]
"org.example:place-order:1.0.0" = 3
"org.example:get-order-status:1.0.0" = 2
```

### Priority 3: Caching Service

**Purpose**: Distributed cache with configurable consistency.

**Value**:
- Eliminates Redis/Memcached dependency
- Integrated with Aether patterns
- Per-cache configuration

**Interface** (Apache Ignite-inspired):
```java
public interface CacheService {
    // Get or create named cache
    <K, V> Promise<Cache<K, V>> cache(String name, CacheConfig config);
}

public interface Cache<K, V> {
    Promise<Option<V>> get(K key);
    Promise<Unit> put(K key, V value);
    Promise<Unit> putWithTtl(K key, V value, Duration ttl);
    Promise<Boolean> remove(K key);
    Promise<Unit> clear();
}

public record CacheConfig(
    int replicas,           // Number of copies (e.g., 3)
    int writeQuorum,        // ACKs needed for success (e.g., 2)
    Duration defaultTtl,    // Default TTL
    EvictionPolicy eviction // LRU, LFU, etc.
) {}
```

### Future: Messaging & Streaming

**Lightweight Messaging**:
- Point-to-point with delivery guarantees
- Fire-and-forget option
- Request-reply pattern

**Pub-Sub**:
- Topic-based subscription
- Durable subscriptions option

**Streaming** (Kafka-like):
- Persistent event log
- Consumer groups
- Replay from offset

These are deferred until customer demand is clear.

## Foundation: Distributed Hash Map

All infrastructure services share a common foundation: a distributed hash map with optional persistence.

### Design

**NOT the consensus KV-Store**: The existing Rabia-based KV-Store is for cluster metadata - consistent but not optimized for performance.

**New component**: Consistent hashing implementation for data distribution.

```
┌─────────────────────────────────────────────────────────────────┐
│  Infrastructure Services Layer                                   │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │artifact-repo│ │  caching    │ │  messaging  │               │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘               │
│         └────────────────┼────────────────┘                     │
│                          ↓                                       │
│              ┌───────────────────────┐                          │
│              │  Distributed Hash Map │                          │
│              │  (consistent hashing) │                          │
│              └───────────┬───────────┘                          │
│                          ↓                                       │
│              ┌───────────────────────┐                          │
│              │   Storage Engines     │                          │
│              │  ┌────────┐ ┌──────┐  │                          │
│              │  │KV DB   │ │Off-  │  │                          │
│              │  │Engine  │ │Heap  │  │                          │
│              │  └────────┘ └──────┘  │                          │
│              └───────────────────────┘                          │
└─────────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────────┐
│  Aether Core (Rabia consensus for cluster coordination)         │
└─────────────────────────────────────────────────────────────────┘
```

### Consistent Hashing

**Approach**: Similar to Apache Ignite, but simpler.

**Partition assignment**:
- Hash key → partition (e.g., 1024 partitions)
- Partition → primary node + replica nodes
- SMR tracks partition assignments (not data)

**Replication**:
- Configurable replica count (e.g., 3)
- Configurable write quorum (e.g., 2 of 3)
- Async replication with configurable consistency

**Failure handling**:
- Node failure triggers partition reassignment via SMR
- Surviving replicas serve reads
- Background rebalancing restores replica count

### Storage Engines

**1. KV Database Engine**:
- Persistent storage
- Write-ahead log
- Compaction
- For: artifact repository, durable caches

**2. Off-Heap Memory Engine**:
- In-memory with off-heap storage
- Fast access, no GC pressure
- For: session caches, hot data

### Why This Works

Starting with SMR (Rabia) provides:
- Reliable partition map
- Membership tracking
- Leader for coordination tasks

Adding consistent hashing provides:
- Horizontal data scaling
- Configurable consistency/availability tradeoff
- Independent of consensus performance

This is the opposite of Ignite's evolution (DHT first, consensus later) but may be simpler because cluster coordination is already solved.

## Blueprint Dependencies

### Syntax

```toml
[blueprint]
name = "order-system"
requires = ["artifact-repo", "http-routing", "caching"]

[slices]
"org.example:place-order:1.0.0" = 3
"org.example:inventory:1.0.0" = 2
```

### Resolution Flow

1. Parse blueprint
2. Check `requires` list
3. For each required service:
   - If running: continue
   - If not running: load from infra repo
   - If not in repo: fail with clear error
4. Wait for infrastructure services to reach ACTIVE
5. Deploy application slices

### Error Messages

```
Blueprint deployment failed:

Required infrastructure service 'caching' not found.

Available services in infrastructure repository:
  - artifact-repo (v1.0.0)
  - http-routing (v1.0.0)

To add caching service:
  aether infra install caching
```

## Bootstrap Strategy

### Problem

Artifact-repo can't load itself from artifact-repo.

### Solution: Two-Phase Bootstrap

**Phase 1 - Bundled Bootstrap**:
- Aether distribution ships with core infra services embedded
- Located in `lib/infra/` or similar
- Includes: artifact-repo, http-routing (minimal set)

**Phase 2 - Self-Hosted**:
- Once artifact-repo is running, it stores infra services
- Upgrades deployed through artifact-repo itself
- Bundled versions used only for cold start

### Bootstrap Flow

```
1. Aether starts
2. Check if artifact-repo running in cluster
3. If not: load from bundled lib/infra/artifact-repo.jar
4. Artifact-repo starts, registers itself
5. Future loads come from artifact-repo
6. Upgrade: deploy new version → restart → picks up new version
```

### Upgrade Path

```bash
# Deploy new infra service version
aether deploy lib/infra/artifact-repo-2.0.0.jar

# Rolling restart picks up new version
aether cluster rolling-restart
```

## Implementation Roadmap

### Phase 1: Foundation (Month 2)
- [ ] Distributed hash map design
- [ ] Consistent hashing implementation
- [ ] Basic storage engine (in-memory)

### Phase 2: Artifact Repository (Month 2-3)
- [ ] Maven protocol subset
- [ ] Deploy/resolve operations
- [ ] Integration with slice loading
- [ ] CLI: `aether deploy artifact.jar`

### Phase 3: HTTP Routing Service (Month 3)
- [ ] Self-registration API
- [ ] Route management
- [ ] Migration from blueprint routing
- [ ] Backward compatibility

### Phase 4: Caching Service (Month 4+)
- [ ] Cache API
- [ ] Configurable consistency
- [ ] TTL and eviction
- [ ] Persistent storage engine

### Future Phases
- Messaging service
- Pub-sub service
- Streaming service
- Distributed file system

## Trade-offs and Decisions

### Why Infrastructure Services vs Built-in Features?

**Pros of service approach**:
- Dogfoods slice architecture
- Optional - don't pay for what you don't use
- Upgradable independently
- Testable in isolation

**Cons**:
- More moving parts at startup
- Bootstrap complexity

**Decision**: Services approach is cleaner. Bootstrap with bundled, then self-host.

### Why Not Use Existing Solutions?

Could embed Redis, H2, etc. But:
- Adds external dependencies
- Different operational model
- Doesn't leverage Aether's distribution

Building on Aether's foundation provides:
- Unified management
- Consistent APIs
- Single system to operate

### Consistency vs Availability

Each service can configure its own trade-off:
- Artifact repo: Strong consistency (don't serve stale artifacts)
- Caching: Configurable per cache
- Messaging: At-least-once or at-most-once per use case

## Related Documents

- [ai-integration.md](ai-integration.md) - Layered autonomy architecture
- [architecture-overview.md](architecture-overview.md) - Core Aether architecture
- [demos.md](demos.md) - Current blueprint format and slice patterns
