# Infrastructure Services

## Overview

Aether provides built-in infrastructure services as part of the core runtime. These services are automatically available on every node without requiring separate deployment.

## Current Implementation

### Artifact Repository (Built-in)

The artifact repository is integrated directly into AetherNode, providing Maven-compatible artifact storage.

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│  ManagementServer (HTTP API)                                     │
│  └─ /repository/**  (Maven protocol)                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  MavenProtocolHandler                                            │
│  └─ Parse paths, handle GET/PUT                                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  ArtifactStore                                                   │
│  └─ Chunked storage, version listing                             │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Distributed Hash Table (DHT)                                    │
│  └─ Consistent hashing, configurable replication                 │
└─────────────────────────────────────────────────────────────────┘
```

**Value:**
- No external Nexus/Artifactory required
- Self-contained deployment story
- Enables air-gapped deployments
- First-run experience: `aether artifact deploy my-app.jar` just works

**Replication Configuration:**

The DHT supports configurable replication via `DHTConfig`:

| Mode | Replication Factor | Use Case |
|------|-------------------|----------|
| `DEFAULT` | 3 (quorum 2) | Production - balance between redundancy and efficiency |
| `FULL` | 0 (all nodes) | Testing/Forge - simple, all nodes have everything |
| `SINGLE_NODE` | 1 | Development - single node setup |

```java
// In AetherNodeConfig
AetherNodeConfig.aetherNodeConfig(self, port, coreNodes, sliceConfig,
    managementPort, httpRouter, DHTConfig.DEFAULT);  // 3 replicas

// For tests/Forge - full replication
AetherNodeConfig.testConfig(self, port, coreNodes);  // Uses DHTConfig.FULL
```

**CLI Usage:**

```bash
# Deploy artifact
aether artifact deploy target/my-slice.jar -g com.example -a my-slice -v 1.0.0

# List artifacts
aether artifact list

# List versions
aether artifact versions com.example:my-slice
```

**Maven Protocol:**

The repository supports standard Maven GET/PUT operations:

```bash
# Deploy via Maven
mvn deploy -DaltDeploymentRepository=aether::default::http://localhost:8080/repository

# Or curl
curl -X PUT http://localhost:8080/repository/com/example/my-slice/1.0.0/my-slice-1.0.0.jar \
  --data-binary @target/my-slice-1.0.0.jar
```

### Forge Integration

Forge (testing simulator) automatically provides artifact repository on the same port as the dashboard API. This enables testing deployment flows without external infrastructure.

```bash
# Start Forge
./script/aether-forge.sh

# Deploy to Forge's repository
curl -X PUT http://localhost:8888/repository/com/example/test/1.0.0/test-1.0.0.jar \
  --data-binary @test.jar
```

## InfraStore: Instance Sharing

Infrastructure services share instances across slices via `InfraStore`. This enables:
- Singleton services (cache, database connections)
- Resource pooling
- Configuration sharing

### How It Works

```
┌─────────────────────────────────────────────────────────────────┐
│  Slice A                      Slice B                            │
│  ─────────                    ─────────                          │
│  CacheService.cacheService()  CacheService.cacheService()        │
│         │                            │                           │
│         └───────────┬────────────────┘                           │
│                     ▼                                            │
│              ┌─────────────┐                                     │
│              │ InfraStore  │ ← Per-node singleton                │
│              │ getOrCreate │                                     │
│              └──────┬──────┘                                     │
│                     │                                            │
│         ┌───────────┴───────────┐                                │
│         ▼                       ▼                                │
│  First call: create      Subsequent: return existing             │
│  └─ factory.get()        └─ cached instance                      │
└─────────────────────────────────────────────────────────────────┘
```

### Usage in Infra Services

```java
public interface CacheService extends Slice {
    // Factory using InfraStore for instance sharing
    static CacheService cacheService() {
        return InfraStore.instance()
            .map(store -> store.getOrCreate(
                "org.pragmatica-lite.aether:infra-cache",
                "0.7.0",
                CacheService.class,
                InMemoryCacheService::inMemoryCacheService))
            .or(InMemoryCacheService::inMemoryCacheService);  // fallback
    }
}
```

### getOrCreate Behavior

| Aspect | Behavior |
|--------|----------|
| Thread-safety | Atomic creation, only one instance per (key, version) |
| Version matching | Qualifiers stripped (`1.0.0-SNAPSHOT` = `1.0.0`) |
| Return type | `T` directly (not wrapped) |
| Factory errors | Exceptions propagate to caller |

**Best practice**: Factories should handle initialization errors internally:

```java
static CacheService cacheService() {
    return InfraStore.instance()
        .map(store -> store.getOrCreate(KEY, VERSION, CacheService.class,
            () -> {
                try {
                    return RedisCacheService.redisCacheService();
                } catch (Exception e) {
                    return InMemoryCacheService.inMemoryCacheService();
                }
            }))
        .or(InMemoryCacheService::inMemoryCacheService);
}
```

### Declaring Infra Dependencies

Slices declare infra dependencies in their dependency file:

```
[infra]
org.pragmatica-lite.aether:infra-cache:^0.7.0
org.pragmatica-lite.aether:infra-database:^0.7.0
```

This ensures infra JARs are loaded before the slice attempts to use them.

## Planned Infrastructure Services

### HTTP Routing Service (Future)

Self-registration based routing to replace blueprint-defined routes.

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

### Caching Service (Future)

Distributed cache with configurable consistency, built on the same DHT foundation.

```java
public interface CacheService {
    <K, V> Promise<Cache<K, V>> cache(String name, CacheConfig config);
}

public record CacheConfig(
    int replicas,           // Number of copies
    int writeQuorum,        // ACKs needed for success
    Duration defaultTtl,    // Default TTL
    EvictionPolicy eviction // LRU, LFU, etc.
) {}
```

### Aspect Provider Slice (Future)

Infrastructure slice exposing factories for cross-cutting concerns. Provides runtime-injectable aspects following JBCT patterns.

**Aspects:**

| Aspect | Purpose |
|--------|---------|
| Logging | Structured logging with correlation IDs, entry/exit tracing |
| Retry | Configurable retry policies with backoff strategies |
| Circuit Breaker | Fail-fast with configurable thresholds and recovery |

**API Design:**

```java
public interface AspectProvider {
    // Logging aspect factory
    <I, O> Fn1<Promise<O>, I> withLogging(Fn1<Promise<O>, I> fn, LogConfig config);

    // Retry aspect factory
    <I, O> Fn1<Promise<O>, I> withRetry(Fn1<Promise<O>, I> fn, RetryConfig config);

    // Circuit breaker aspect factory
    <I, O> Fn1<Promise<O>, I> withCircuitBreaker(Fn1<Promise<O>, I> fn, CircuitBreakerConfig config);
}

public record RetryConfig(
    int maxAttempts,
    Duration initialDelay,
    double backoffMultiplier,
    Set<Class<? extends Cause>> retryableErrors
) {}

public record CircuitBreakerConfig(
    int failureThreshold,      // Failures before opening
    Duration openDuration,      // Time to stay open
    int halfOpenPermits,        // Requests allowed in half-open
    double successThreshold     // Success rate to close
) {}
```

**Usage in Slice:**

```java
// Inject via SliceBridge
var aspectProvider = bridge.resolve(AspectProvider.class);

// Wrap adapter leaf with retry
var saveUser = aspectProvider.withRetry(
    userRepository::save,
    new RetryConfig(3, Duration.ofMillis(100), 2.0, Set.of(TransientError.class))
);

// Wrap external call with circuit breaker
var callPayment = aspectProvider.withCircuitBreaker(
    paymentGateway::charge,
    new CircuitBreakerConfig(5, Duration.ofSeconds(30), 3, 0.5)
);
```

**Benefits:**
- Consistent cross-cutting behavior across slices
- Runtime-configurable without slice redeployment
- Cluster-wide circuit breaker state (via DHT)
- Metrics integration for observability

## Foundation: Distributed Hash Table

All infrastructure services share a common DHT foundation with consistent hashing.

### Design

**Separate from consensus KV-Store**: The Rabia-based KV-Store handles cluster metadata (blueprints, slice states). The DHT handles data distribution for infrastructure services.

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
│              │  │Memory  │ │Future│  │                          │
│              │  │Engine  │ │ KV DB│  │                          │
│              │  └────────┘ └──────┘  │                          │
│              └───────────────────────┘                          │
└─────────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────────┐
│  Aether Core (Rabia consensus for cluster coordination)         │
└─────────────────────────────────────────────────────────────────┘
```

### Consistent Hashing

**Key features:**
- Configurable replica count (1, 3, or full)
- Quorum-based writes
- Node failure triggers automatic rebalancing

### Storage Engines

**1. Memory Engine** (current):
- In-memory ConcurrentHashMap
- Fast, suitable for testing and small deployments
- Data lost on restart

**2. Persistent Engine** (future):
- Write-ahead log
- Compaction
- For production artifact storage

## Trade-offs and Decisions

### Why Built-in vs Separate Slices?

The artifact repository is built into AetherNode rather than deployed as a slice because:

1. **Bootstrap problem**: Can't load slice JARs from repository if repository isn't running
2. **Simpler operations**: No separate deployment/upgrade path
3. **Always available**: Every node can serve artifacts
4. **Testing parity**: Same code path in production and Forge

### Why Not External Solutions?

Could use Nexus, Artifactory, or S3. But:
- Adds external dependency
- Different operational model
- Doesn't leverage Aether's distribution
- Complicates air-gapped deployments

Building on Aether's DHT provides:
- Unified management
- Consistent APIs
- Single system to operate
- Automatic replication

## Related Documents

- [CLI Reference](../reference/cli.md) - Artifact CLI commands
- [architecture-overview.md](../contributors/architecture.md) - Core Aether architecture
- [Forge Guide](forge-guide.md) - Testing with artifact deployment
