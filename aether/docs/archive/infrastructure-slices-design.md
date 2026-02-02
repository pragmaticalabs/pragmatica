# Infrastructure Slices Design

Status: **IN PROGRESS** (10/21 complete - see [infra-slices-progress.md](infra-slices-progress.md))

## Overview

Aether provides a comprehensive set of infrastructure slices that handle cross-cutting concerns, communication, data management, and coordination. These slices are built on Aether's distributed foundation (consensus, DHT, cluster messaging) and follow JBCT patterns.

**Design Principles:**
- Built on Aether primitives (no external dependencies initially)
- Lightweight, in-memory first (persistence optional/later)
- JBCT-compliant APIs (Result, Promise, Option, Cause)
- Self-contained deployment (slices package their own dependencies)

---

## HTTP Slices

### HTTP Server

Self-registering HTTP server that decouples routing from blueprint configuration.

**Purpose:**
- Slices register their own routes at construction time
- No central routing configuration needed
- Standard route builder API

**API:**
```java
public interface HttpServer extends Slice {
    // Route registration (at construction time)
    Promise<Unit> register(Route... routes);
    Promise<Unit> unregister(String path);

    // Lifecycle
    Promise<Unit> start(int port);
    Promise<Unit> stop();
}

// Route builder (standardized pattern)
public interface Route {
    static Route get(String path);
    static Route post(String path);
    static Route put(String path);
    static Route delete(String path);

    Route handler(Fn1<Promise<Response>, Request> handler);
    Route consumes(ContentType type);
    Route produces(ContentType type);
}
```

**Path Parameter Convention:**
Standardize on single pattern for simplicity:
```
/api/orders/{orderId}           - path parameter
/api/orders?status=pending      - query parameter
```

**Usage in Slice:**
```java
static MySlice mySlice(HttpServer httpServer, ...) {
    httpServer.register(
        Route.post("/api/orders").handler(instance::placeOrder),
        Route.get("/api/orders/{orderId}").handler(instance::getOrder)
    );
    return instance;
}
```

### HTTP Client

Outbound HTTP calls to external services (non-slice endpoints).

**API:**
```java
public interface HttpClient extends Slice {
    Promise<Response> get(String url, Headers headers);
    Promise<Response> post(String url, Body body, Headers headers);
    Promise<Response> put(String url, Body body, Headers headers);
    Promise<Response> delete(String url, Headers headers);

    // Configured client with base URL, timeouts, retry
    HttpClient withBaseUrl(String baseUrl);
    HttpClient withTimeout(Duration timeout);
    HttpClient withRetry(RetryConfig config);
}
```

---

## Aspect Factory Slices

Provide runtime-configurable cross-cutting behavior following JBCT patterns.

### Logging Aspect Factory

**API:**
```java
public interface LoggingAspectFactory extends Slice {
    <T> Aspect<T> create(LogConfig config);
    <T> Aspect<T> create(String name);  // Default config

    // Dynamic control
    void setLevel(LogLevel level);
    void enable(boolean enabled);
}

public record LogConfig(
    String name,
    LogLevel level,
    boolean logArgs,
    boolean logResult,
    boolean logDuration
) {}
```

### Metrics Aspect Factory

**API:**
```java
public interface MetricsAspectFactory extends Slice {
    <T> Aspect<T> create(MetricsConfig config);
    <T> Aspect<T> create(String name);  // Default config

    // Access collected metrics
    Map<String, MetricSnapshot> snapshot();
}

public record MetricsConfig(
    String name,
    boolean recordCount,
    boolean recordDuration,
    boolean recordHistogram,
    List<Double> percentiles  // e.g., [0.5, 0.95, 0.99]
) {}
```

### Retry Aspect Factory

**API:**
```java
public interface RetryAspectFactory extends Slice {
    <T> Aspect<T> create(RetryConfig config);
}

public record RetryConfig(
    int maxAttempts,
    Duration initialDelay,
    double backoffMultiplier,
    Duration maxDelay,
    Set<Class<? extends Cause>> retryableErrors
) {
    public static RetryConfig defaultConfig() {
        return new RetryConfig(3, Duration.ofMillis(100), 2.0,
            Duration.ofSeconds(10), Set.of());
    }
}
```

### Circuit Breaker Aspect Factory

**API:**
```java
public interface CircuitBreakerFactory extends Slice {
    <T> Aspect<T> create(CircuitBreakerConfig config);

    // Monitoring
    CircuitState state(String name);
    void reset(String name);
}

public record CircuitBreakerConfig(
    String name,
    int failureThreshold,      // Failures before opening
    Duration openDuration,      // Time to stay open
    int halfOpenPermits,        // Requests in half-open state
    double successThreshold     // Success rate to close
) {}

public enum CircuitState { CLOSED, OPEN, HALF_OPEN }
```

### Transaction Aspect Factory

For database transaction management (local transactions only - no distributed Saga needed due to Aether's communication guarantee).

**API:**
```java
public interface TransactionAspectFactory extends Slice {
    <T> Aspect<T> create(TransactionConfig config);
}

public record TransactionConfig(
    IsolationLevel isolation,
    boolean readOnly,
    Duration timeout,
    Set<Class<? extends Cause>> rollbackOn
) {}

public enum IsolationLevel {
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE
}
```

---

## Caching Slice

Redis-like caching with multiple data structures, built on DHT.

**Supported Structures (implement what's cheap):**
- String (basic KV)
- Hash (nested maps)
- List (queues, stacks)
- Set (unique collections)
- Sorted Set (if feasible)

**API:**
```java
public interface CacheService extends Slice {
    // String operations
    Promise<Unit> set(String key, String value);
    Promise<Unit> set(String key, String value, Duration ttl);
    Promise<Option<String>> get(String key);
    Promise<Boolean> delete(String key);
    Promise<Boolean> exists(String key);

    // Hash operations
    Promise<Unit> hset(String key, String field, String value);
    Promise<Option<String>> hget(String key, String field);
    Promise<Map<String, String>> hgetall(String key);

    // List operations
    Promise<Long> lpush(String key, String... values);
    Promise<Long> rpush(String key, String... values);
    Promise<Option<String>> lpop(String key);
    Promise<Option<String>> rpop(String key);
    Promise<List<String>> lrange(String key, int start, int stop);

    // Set operations
    Promise<Long> sadd(String key, String... members);
    Promise<Boolean> sismember(String key, String member);
    Promise<Set<String>> smembers(String key);

    // TTL
    Promise<Boolean> expire(String key, Duration ttl);
    Promise<Option<Duration>> ttl(String key);

    // Typed access (serialization)
    <T> TypedCache<T> typed(Class<T> type);
}

public interface TypedCache<T> {
    Promise<Unit> set(String key, T value);
    Promise<Unit> set(String key, T value, Duration ttl);
    Promise<Option<T>> get(String key);
}
```

**Consistency Modes:**
```java
public enum CacheConsistency {
    STRONG,    // Consensus write - slowest, strongest
    QUORUM,    // DHT quorum - balanced
    LOCAL      // Node-local only - fastest, no replication
}

// Usage
cacheService.withConsistency(CacheConsistency.QUORUM)
            .set("key", "value");
```

---

## Messaging Slices

### Pub/Sub

Simple topic-based publish/subscribe.

**API:**
```java
public interface PubSub extends Slice {
    // Publishing
    Promise<Unit> publish(String topic, Message message);

    // Subscribing
    Promise<Subscription> subscribe(String topic, Fn1<Promise<Unit>, Message> handler);
    Promise<Unit> unsubscribe(Subscription subscription);

    // Topic management
    Promise<Unit> createTopic(String topic);
    Promise<Unit> deleteTopic(String topic);
    Promise<Set<String>> listTopics();
}

public record Message(
    String id,
    byte[] payload,
    Map<String, String> headers,
    Instant timestamp
) {}

public interface Subscription {
    String topic();
    String subscriptionId();
    Promise<Unit> pause();
    Promise<Unit> resume();
}
```

### Streaming (Kafka-like)

Partitioned, ordered message streaming with consumer groups.

**API:**
```java
public interface StreamingService extends Slice {
    // Topic management
    Promise<Unit> createTopic(TopicConfig config);
    Promise<Unit> deleteTopic(String topic);

    // Producer
    Promise<RecordMetadata> send(String topic, Record record);
    Promise<RecordMetadata> send(String topic, String key, byte[] value);

    // Consumer
    Promise<Consumer> consumer(ConsumerConfig config);
}

public record TopicConfig(
    String name,
    int partitions,
    RetentionPolicy retention
) {}

public record RetentionPolicy(
    Option<Duration> maxAge,
    Option<Long> maxBytes,
    boolean compaction  // Keep latest per key
) {}

public interface Consumer {
    Promise<List<Record>> poll(Duration timeout);
    Promise<Unit> commit();
    Promise<Unit> seek(String topic, int partition, long offset);
    Promise<Unit> close();
}

public record ConsumerConfig(
    String groupId,
    List<String> topics,
    OffsetReset autoOffsetReset
) {}

public enum OffsetReset { EARLIEST, LATEST }

public record Record(
    String topic,
    int partition,
    long offset,
    String key,
    byte[] value,
    Map<String, String> headers,
    Instant timestamp
) {}
```

**Scaling Decision:**
- Single artifact initially
- Split into TopicManager, Producer, Consumer if scaling needs diverge

---

## Coordination Slices

### Distributed Lock

Built on consensus for strong consistency.

**API:**
```java
public interface DistributedLock extends Slice {
    // Blocking acquire
    Promise<LockHandle> acquire(String lockId, Duration timeout);

    // Try acquire (non-blocking)
    Promise<Option<LockHandle>> tryAcquire(String lockId);

    // With automatic release
    <T> Promise<T> withLock(String lockId, Duration timeout,
                            Fn0<Promise<T>> action);
}

public interface LockHandle extends AutoCloseable {
    String lockId();
    String fencingToken();  // For correctness verification
    Instant acquiredAt();
    Promise<Unit> release();
    Promise<Boolean> extend(Duration extension);
}
```

### Scheduled Tasks

Cron-like execution with leader-only guarantee.

**API:**
```java
public interface Scheduler extends Slice {
    // Schedule recurring task
    Promise<TaskHandle> schedule(String taskId,
                                  CronExpression cron,
                                  Fn0<Promise<Unit>> task);

    // Schedule one-time task
    Promise<TaskHandle> scheduleOnce(String taskId,
                                      Instant at,
                                      Fn0<Promise<Unit>> task);

    // Schedule with fixed rate
    Promise<TaskHandle> scheduleAtFixedRate(String taskId,
                                             Duration initialDelay,
                                             Duration period,
                                             Fn0<Promise<Unit>> task);

    // Management
    Promise<Unit> cancel(String taskId);
    Promise<List<TaskInfo>> listTasks();
}

public interface TaskHandle {
    String taskId();
    Promise<Unit> cancel();
    TaskState state();
}

public record TaskInfo(
    String taskId,
    TaskState state,
    Option<Instant> lastRun,
    Option<Instant> nextRun
) {}
```

### Rate Limiter

Distributed rate limiting using DHT for counter storage.

**API:**
```java
public interface RateLimiter extends Slice {
    // Check and consume
    Promise<RateLimitResult> acquire(String key);
    Promise<RateLimitResult> acquire(String key, int permits);

    // Check without consuming
    Promise<RateLimitResult> check(String key);

    // Configuration
    Promise<Unit> configure(String key, RateLimitConfig config);
}

public record RateLimitConfig(
    int maxRequests,
    Duration window,
    RateLimitStrategy strategy
) {}

public enum RateLimitStrategy {
    FIXED_WINDOW,
    SLIDING_WINDOW,
    TOKEN_BUCKET
}

public record RateLimitResult(
    boolean allowed,
    int remaining,
    Duration retryAfter
) {}
```

---

## Configuration Slices

### Feature Flags

Runtime feature toggles with targeting support.

**API:**
```java
public interface FeatureFlags extends Slice {
    // Simple boolean check
    Promise<Boolean> isEnabled(String flag);
    Promise<Boolean> isEnabled(String flag, Context context);

    // Variant flags (A/B testing)
    Promise<String> getVariant(String flag);
    Promise<String> getVariant(String flag, Context context);

    // Management
    Promise<Unit> setFlag(String flag, FlagConfig config);
    Promise<Unit> deleteFlag(String flag);
    Promise<Map<String, FlagConfig>> listFlags();
}

public record FlagConfig(
    boolean defaultValue,
    Option<Integer> percentage,  // Percentage rollout
    Map<String, Boolean> overrides  // Per-context overrides
) {}

public record Context(Map<String, String> attributes) {
    public static Context of(String key, String value) {
        return new Context(Map.of(key, value));
    }
}
```

### Config Service

Dynamic configuration with hierarchical override.

**API:**
```java
public interface ConfigService extends Slice {
    // Read
    Promise<Option<String>> get(String key);
    Promise<Option<String>> get(String key, ConfigScope scope);
    <T> Promise<Option<T>> get(String key, Class<T> type);

    // Write
    Promise<Unit> set(String key, String value);
    Promise<Unit> set(String key, String value, ConfigScope scope);
    Promise<Unit> delete(String key);

    // Watch for changes
    Promise<Subscription> watch(String keyPattern,
                                 Fn1<Promise<Unit>, ConfigChange> handler);
}

public enum ConfigScope {
    GLOBAL,      // Cluster-wide
    NODE,        // Per-node
    SLICE        // Per-slice instance
}

public record ConfigChange(
    String key,
    Option<String> oldValue,
    Option<String> newValue,
    ConfigScope scope
) {}
```

### Secrets Manager

Secure credential storage with encryption.

**API:**
```java
public interface SecretsManager extends Slice {
    // Read (decrypts automatically)
    Promise<Option<Secret>> get(String secretId);

    // Write (encrypts automatically)
    Promise<Unit> set(String secretId, Secret secret);
    Promise<Unit> delete(String secretId);

    // Rotation
    Promise<Unit> rotate(String secretId, Secret newSecret);

    // List (metadata only, not values)
    Promise<List<SecretMetadata>> list();
}

public record Secret(
    byte[] value,
    Map<String, String> metadata,
    Option<Instant> expiresAt
) {}

public record SecretMetadata(
    String secretId,
    Instant createdAt,
    Instant updatedAt,
    Option<Instant> expiresAt
) {}
```

---

## Data Slices

### Database Access

JDBC adapter for relational databases.

**API:**
```java
public interface DatabaseService extends Slice {
    // Query
    <T> Promise<List<T>> query(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<Option<T>> queryOne(String sql, RowMapper<T> mapper, Object... params);

    // Execute
    Promise<Integer> execute(String sql, Object... params);
    Promise<Long> insert(String sql, Object... params);  // Returns generated key

    // Batch
    Promise<int[]> executeBatch(String sql, List<Object[]> paramBatch);

    // Transaction (use with TransactionAspect)
    <T> Promise<T> inTransaction(Fn1<Promise<T>, Connection> action);
}

@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
}
```

### Blob Storage

S3-like object storage built on DHT chunks.

**API:**
```java
public interface BlobStorage extends Slice {
    // Write
    Promise<BlobMetadata> put(String bucket, String key, InputStream data);
    Promise<BlobMetadata> put(String bucket, String key, byte[] data);

    // Read
    Promise<Option<Blob>> get(String bucket, String key);
    Promise<Option<BlobMetadata>> metadata(String bucket, String key);

    // Delete
    Promise<Unit> delete(String bucket, String key);

    // List
    Promise<List<BlobMetadata>> list(String bucket, String prefix);

    // Bucket management
    Promise<Unit> createBucket(String bucket);
    Promise<Unit> deleteBucket(String bucket);
}

public record Blob(
    BlobMetadata metadata,
    InputStream data
) {}

public record BlobMetadata(
    String bucket,
    String key,
    long size,
    String contentType,
    String md5,
    Instant createdAt,
    Map<String, String> userMetadata
) {}
```

---

## Workflow Slice

### State Machine

Durable state machine for long-running workflows (replaces Saga pattern).

**API:**
```java
public interface StateMachine extends Slice {
    // Define state machine
    Promise<Unit> define(String machineId, StateMachineDefinition definition);

    // Create instance
    Promise<String> create(String machineId, Map<String, Object> context);

    // Trigger transition
    Promise<StateInfo> trigger(String instanceId, String event);
    Promise<StateInfo> trigger(String instanceId, String event, Map<String, Object> data);

    // Query
    Promise<Option<StateInfo>> get(String instanceId);
    Promise<List<StateInfo>> findByState(String machineId, String state);
}

public record StateMachineDefinition(
    String initialState,
    Map<String, StateDefinition> states
) {}

public record StateDefinition(
    Map<String, Transition> transitions,
    Option<Duration> timeout,
    Option<String> timeoutEvent
) {}

public record Transition(
    String targetState,
    Option<String> guard,      // Condition expression
    Option<String> action      // Action to execute
) {}

public record StateInfo(
    String instanceId,
    String machineId,
    String currentState,
    Map<String, Object> context,
    Instant createdAt,
    Instant updatedAt,
    List<StateTransition> history
) {}
```

---

## Reliability Slice

### Outbox Pattern

Reliable event publishing with exactly-once delivery.

**API:**
```java
public interface Outbox extends Slice {
    // Publish (stores in outbox, delivers asynchronously)
    Promise<String> publish(OutboxMessage message);

    // Batch publish (atomic - all or none)
    Promise<List<String>> publishBatch(List<OutboxMessage> messages);

    // Query pending (for monitoring)
    Promise<List<OutboxEntry>> pending();
    Promise<List<OutboxEntry>> failed();

    // Manual retry
    Promise<Unit> retry(String messageId);
}

public record OutboxMessage(
    String destination,    // Topic or queue name
    byte[] payload,
    Map<String, String> headers,
    Option<String> deduplicationId
) {}

public record OutboxEntry(
    String messageId,
    OutboxMessage message,
    OutboxStatus status,
    int attempts,
    Option<Cause> lastError,
    Instant createdAt,
    Option<Instant> deliveredAt
) {}

public enum OutboxStatus {
    PENDING,
    DELIVERED,
    FAILED
}
```

---

## Implementation Priority

### Phase 1: Foundation (Required by other slices)
1. **HTTP Server** - Enables self-registering routes
2. **Logging Aspect** - Needed for debugging everything else
3. **Metrics Aspect** - Observability foundation
4. **Caching** - Basic String operations, TTL

### Phase 2: Communication
5. **HTTP Client** - External service calls
6. **Pub/Sub** - Simple messaging
7. **Streaming** - Ordered, partitioned messaging

### Phase 3: Coordination
8. **Distributed Lock** - Built on existing consensus
9. **Scheduled Tasks** - Leader-only cron
10. **Rate Limiter** - Overload protection

### Phase 4: Resilience
11. **Retry Aspect** - Transient failure handling
12. **Circuit Breaker** - Fail fast
13. **Outbox** - Reliable delivery

### Phase 5: Configuration
14. **Feature Flags** - Runtime toggles
15. **Config Service** - Dynamic config
16. **Secrets Manager** - Secure credentials

### Phase 6: Data
17. **Database Access** - JDBC adapter
18. **Blob Storage** - Object storage
19. **Transaction Aspect** - Local transactions

### Phase 7: Orchestration
20. **State Machine** - Long-running workflows

---

## Cross-Cutting Concerns

### Slice Dependencies

All infrastructure slices can depend on each other. Dependency graph:

```
HTTP Server
    ↑
    ├── Logging Aspect (for request logging)
    ├── Metrics Aspect (for request metrics)
    └── Rate Limiter (for request throttling)

Streaming
    ↑
    ├── Distributed Lock (for consumer group coordination)
    └── Caching (for consumer offset storage)

State Machine
    ↑
    ├── Distributed Lock (for state transitions)
    └── Outbox (for reliable event publishing)
```

### Testing Infrastructure Slices

Each infrastructure slice provides test doubles:

```java
// In-memory implementation for testing
public interface CacheService extends Slice {
    // ... production methods ...

    // Test support
    static CacheService inMemory() {
        return new InMemoryCacheService();
    }
}
```

### Dashboard Integration

All infrastructure slices expose metrics and status via ManagementServer:

```
GET /api/infra/cache/stats
GET /api/infra/pubsub/topics
GET /api/infra/locks/held
GET /api/infra/scheduler/tasks
GET /api/infra/ratelimiter/stats
GET /api/infra/circuitbreaker/states
```

---

## Related Documents

- [Infrastructure Services](infrastructure-services.md) - Built-in services (artifact repo, DHT)
- [Slice Factory Generation](SLICE-FACTORY-GENERATION.md) - How slices are instantiated
- [Slice Developer Guide](guide/slice-developer-guide.md) - How to write slices
