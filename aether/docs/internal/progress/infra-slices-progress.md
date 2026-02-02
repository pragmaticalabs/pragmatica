# Infrastructure Slices Implementation Progress

Status: **IN PROGRESS**

## Completed (16/21)

| # | Component | Package | Key Classes | Tests |
|---|-----------|---------|-------------|-------|
| 1 | Aspect<T> | `slice-api/.../slice` | `Aspect`, `DynamicAspectConfig`, `DefaultDynamicAspectConfig` | N/A (interface) |
| 2 | LoggingAspectFactory | `infra-slices/.../aspect` | `LogConfig`, `LoggingAspectFactory`, `DefaultLoggingAspectFactory` | `LoggingAspectFactoryTest` |
| 3 | MetricsAspectFactory | `infra-slices/.../aspect` | `MetricsConfig`, `MetricSnapshot`, `MetricsAspectFactory`, `DefaultMetricsAspectFactory` | `MetricsAspectFactoryTest` |
| 4 | CacheService | `infra-slices/.../cache` | `CacheService`, `CacheConfig`, `CacheStats`, `CacheServiceError`, `InMemoryCacheService` | `CacheServiceTest` |
| 5 | RetryAspectFactory | `infra-slices/.../aspect` | `RetryConfig`, `RetryAspectFactory`, `DefaultRetryAspectFactory` | `RetryAspectFactoryTest` |
| 6 | CircuitBreakerFactory | `infra-slices/.../circuit` | `CircuitBreakerConfig`, `CircuitBreakerFactory`, `DefaultCircuitBreakerFactory` | `CircuitBreakerFactoryTest` |
| 7 | DistributedLock | `infra-slices/.../lock` | `LockHandle`, `LockError`, `DistributedLock`, `InMemoryDistributedLock` | `DistributedLockTest` |
| 8 | RateLimiter | `infra-slices/.../ratelimit` | `RateLimitStrategy`, `RateLimitConfig`, `RateLimitResult`, `RateLimiter`, `InMemoryRateLimiter` | `RateLimiterTest` |
| 9 | PubSub | `infra-slices/.../pubsub` | `Message`, `Subscription`, `PubSubError`, `PubSub`, `InMemoryPubSub` | `PubSubTest` |
| 10 | FeatureFlags | `infra-slices/.../feature` | `Context`, `FlagConfig`, `FeatureFlagError`, `FeatureFlags`, `InMemoryFeatureFlags` | `FeatureFlagsTest` |
| 11 | HttpClientSlice | `infra-slices/.../http` | `HttpClientConfig`, `HttpClientSlice`, `JdkHttpClientSlice` | `HttpClientSliceTest` |
| 12 | ConfigService | `infra-slices/.../config` | `ConfigScope`, `ConfigError`, `ConfigSubscription`, `ConfigService`, `InMemoryConfigService` | `ConfigServiceTest` |
| 13 | Scheduler | `infra-slices/.../scheduler` | `ScheduledTaskHandle`, `SchedulerError`, `Scheduler`, `DefaultScheduler` | `SchedulerTest` |
| 14 | HttpServerSlice | `infra-slices/.../server` | `HttpServerSliceConfig`, `HttpServerSliceError`, `HttpServerSlice`, `DefaultHttpServerSlice` | `HttpServerSliceTest` |
| 15 | CacheService Extensions | `infra-slices/.../cache` | Batch ops, counters, patterns, stats | `CacheServiceTest` |
| 16 | StreamingService | `infra-slices/.../streaming` | `StreamingConfig`, `StreamingError`, `StreamMessage`, `TopicInfo`, `ConsumerGroupInfo`, `StreamingService`, `InMemoryStreamingService` | `StreamingServiceTest` |
| 17 | SecretsManager | `infra-slices/.../secrets` | `SecretsConfig`, `SecretsError`, `SecretMetadata`, `SecretValue`, `SecretsManager`, `InMemorySecretsManager` | `SecretsManagerTest` |

## Remaining (4)

### Phase 5: Data
- [ ] **DatabaseService** - JDBC adapter with RowMapper, batch operations
- [ ] **BlobStorage** - S3-like object storage on DHT chunks
- [ ] **TransactionAspect** - Local transaction management with isolation levels

### Phase 6: Orchestration
- [ ] **StateMachine** - Durable state machine for long-running workflows
- [ ] **Outbox** - Reliable event publishing with exactly-once delivery

---

## Implementation Patterns

### Package Structure
```
infra-slices/src/main/java/org/pragmatica/aether/infra/
├── aspect/          # Logging, Metrics, Retry aspects
├── cache/           # CacheService
├── circuit/         # CircuitBreakerFactory
├── config/          # ConfigService with hierarchical scopes
├── feature/         # FeatureFlags
├── http/            # HttpClientSlice (wraps pragmatica-lite HttpOperations)
├── lock/            # DistributedLock
├── pubsub/          # PubSub messaging
├── ratelimit/       # RateLimiter
├── scheduler/       # Scheduler with fixed-rate and fixed-delay tasks
└── InfraSliceError.java  # Shared error type
```

### Standard Component Structure
Each component follows this pattern:
1. **Config record** - Immutable configuration with factory methods returning `Result<Config>`
2. **Error sealed interface** - Domain-specific errors extending `Cause`
3. **Main interface** - Extends `Slice`, has `inMemory()` factory method
4. **InMemory implementation** - For testing and single-node scenarios

### JBCT Patterns Used

**Return Types:**
- `Promise<T>` - All async operations
- `Result<T>` - Fallible factory methods (e.g., `FlagConfig.flagConfig()`)
- `Option<T>` - Optional values (never null)
- `Unit` - Void equivalent

**Factory Naming:**
```java
TypeName.typeName(...)  // lowercase-first factory method
```

**Error Handling:**
```java
public sealed interface LockError extends Cause {
    record AcquisitionTimeout(String lockId, TimeSpan timeout) implements LockError {
        @Override
        public String message() {
            return "Lock acquisition timed out: " + lockId;
        }
    }
}
```

**Null-Free Policy:**
- Use `Option.option(nullableValue)` instead of null checks
- Return `Option<T>` for methods that may not find a value
- Use `.or(defaultValue)` or `.fold()` to extract values

### Key API Discoveries

**pragmatica-lite CircuitBreaker:**
```java
CircuitBreaker.builder()
    .failureThreshold(threshold)
    .resetTimeout(timeout)
    .testAttempts(attempts)
    .withDefaultShouldTrip()
    .withDefaultTimeSource();
```

**Promise with Delay:**
```java
Promise.<T>promise(timeSpan(10).millis(), promise -> {
    // async operation
    someOperation().onResult(promise::resolve);
});
```

**Fn0 Location:**
```java
import org.pragmatica.lang.Functions.Fn0;
```

**Thread Safety:**
- Prefer `synchronized` methods for simple cases
- Use `ConcurrentHashMap` + `CopyOnWriteArrayList` for collections
- Avoid complex atomic operations (race conditions are subtle)

### pragmatica-lite Integrations Used

| Integration | Usage |
|-------------|-------|
| `http-client` | `HttpOperations`, `JdkHttpOperations`, `HttpResult`, `HttpError` for HttpClientSlice |
| `toml` | `TomlParser`, `TomlDocument` for ConfigService TOML parsing |
| `micrometer` | `PromiseMetrics` for MetricsAspectFactory |
| `core` | `Promise<T>`, `Result<T>`, `Option<T>`, `Cause`, `Retry`, `CircuitBreaker` |

---

## Testing Patterns

```java
@Test
void method_scenario_expectation() {
    // Success case
    operation.await()
        .onFailureRun(Assertions::fail)
        .onSuccess(result -> {
            assertThat(result.field()).isEqualTo(expected);
        });

    // Failure case
    operation.await()
        .onSuccessRun(Assertions::fail)
        .onFailure(cause -> {
            assertThat(cause).isInstanceOf(ExpectedError.class);
        });
}
```

---

## Common Fixes Applied

| Issue | Fix |
|-------|-----|
| `Option.get()` doesn't exist | Use `fold()`, `or()`, or `onPresent()` |
| `Promise.delay()` doesn't exist | Use `Promise.promise(TimeSpan, Consumer)` |
| `Promise.success(null)` | Use `Promise.unitPromise()` |
| `fold(c -> null, s -> s)` | Use `.unwrap()` in tests |
| `Verify.Is.INVALID_ARGUMENT` | Use 2-arg `ensure(value, predicate)` |
| Thread safety race conditions | Use `synchronized` methods |

---

## Build & Test Commands

```bash
# Run all infra-slices tests
mvn test -pl infra-slices -q

# Run specific test
mvn test -pl infra-slices -Dtest=FeatureFlagsTest -q

# Full build
mvn clean install -pl infra-slices -q
```

---

## Git History

Branch: `release-0.7.4`

Commits (chronological):
1. `feat(infra): add Aspect interface and DynamicAspectConfig`
2. `feat(infra): add LoggingAspectFactory with JDK proxy`
3. `feat(infra): add MetricsAspectFactory with histogram support`
4. `feat(infra): add CacheService with String operations and TTL`
5. `feat(infra): add RetryAspectFactory using pragmatica-lite Retry`
6. `feat(infra): add CircuitBreakerFactory using pragmatica-lite CircuitBreaker`
7. `feat(infra): add DistributedLock with fencing tokens`
8. `feat(infra): add RateLimiter with fixed/sliding window strategies`
9. `feat(infra): add PubSub with topic-based messaging`
10. `feat(infra): add FeatureFlags with context-based evaluation`
11. `feat(infra): add HttpClientSlice wrapping pragmatica-lite HttpOperations`
12. `feat(infra): add ConfigService with hierarchical GLOBAL/NODE/SLICE scopes`
13. `feat(infra): add Scheduler with fixed-rate and fixed-delay tasks`

---

## Resume Instructions

To continue implementation:

1. Pick next component from "Remaining" list
2. Create package under `infra-slices/.../infra/{component}`
3. Follow standard structure: Config → Error → Interface → InMemoryImpl
4. Write tests following established patterns
5. Run JBCT review: `/jbct-review infra-slices/src/main/java/.../new-package`
6. Fix any issues found
7. Run all tests: `mvn test -pl infra-slices -q`
8. Commit with conventional commit message
