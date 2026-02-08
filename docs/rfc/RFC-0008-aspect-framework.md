---
RFC: 0008
Title: Aspect Framework
Status: Draft
Author: Sergiy Yevtushenko
Created: 2026-01-17
Updated: 2026-01-17
Affects: [jbct-cli, aether]
---

## Summary

Defines the compile-time aspect framework for cross-cutting concerns (caching, logging, metrics, retry) in Aether slices. Aspects are wired at compile time via annotation processing, with infrastructure objects provided by a runtime `AspectFactory`.

## Motivation

Cross-cutting concerns like caching, logging, and metrics need a consistent pattern that:
- Avoids runtime reflection/proxies (performance, GraalVM native)
- Maintains JBCT composition principles (`Fn1` composition)
- Supports typed configuration
- Allows per-method configuration via hierarchical TOML

This RFC establishes the aspect contract between compile-time generation (jbct-cli) and runtime infrastructure (aether).

## Design

### Boundaries

- **jbct-cli (slice-processor)**: Generates aspect wiring code from `@Aspect` annotations
- **aether**: Provides `AspectFactory` implementation via `SliceRuntime`
- **infra-aspect**: Provides aspect infrastructure types (`Cache`, `Aspects` utilities)
- **infra-config**: Provides hierarchical configuration resolution

### 1. Core Interfaces

#### AspectConfig (Marker)

```java
package org.pragmatica.aether.slice;

/**
 * Marker interface for aspect configuration types.
 * All aspect-specific configurations implement this interface.
 */
public interface AspectConfig {}
```

#### AspectFactory

```java
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;

/**
 * Factory for creating aspect infrastructure objects.
 * Creates Cache, RetryPolicy, etc. based on configuration.
 */
public interface AspectFactory {
    /**
     * Create an aspect infrastructure object.
     *
     * @param infrastructureType The type to create (e.g., Cache.class)
     * @param config             Configuration for the infrastructure
     * @return Result containing the created infrastructure or error
     */
    <C extends AspectConfig, R> Result<R> create(Class<R> infrastructureType, C config);
}
```

#### SliceRuntime Extension

```java
public final class SliceRuntime {
    private static volatile AspectFactory aspectFactory;

    public static Result<AspectFactory> getAspectFactory() {
        return Option.option(aspectFactory)
                     .toResult(SliceRuntimeError.AspectFactoryNotConfigured.INSTANCE);
    }

    public static void setAspectFactory(AspectFactory factory) {
        aspectFactory = factory;
    }

    public static void clear() {
        sliceInvoker = null;
        aspectFactory = null;
    }
}
```

### 2. Cache Aspect

#### Cache Interface

```java
package org.pragmatica.aether.infra.aspect;

public interface Cache<K, V> {
    Promise<Option<V>> get(K key);
    Promise<Unit> put(K key, V value);
    Promise<Unit> put(K key, V value, Duration ttl);
    Promise<Unit> remove(K key);
    Promise<Unit> clear();
    String name();
}
```

#### CacheConfig

```java
package org.pragmatica.aether.infra.aspect;

public record CacheConfig<K, V>(String name,
                                TypeToken<K> keyType,
                                TypeToken<V> valueType,
                                Option<Duration> defaultTtl,
                                int maxEntries) implements AspectConfig {

    public static <K, V> Result<CacheConfig<K, V>> cacheConfig(String name,
                                                                TypeToken<K> keyType,
                                                                TypeToken<V> valueType) { ... }

    public static <K, V> Result<CacheConfig<K, V>> cacheConfig(String name,
                                                                TypeToken<K> keyType,
                                                                TypeToken<V> valueType,
                                                                Duration defaultTtl) { ... }
}
```

#### Aspects Composition Utility

```java
package org.pragmatica.aether.infra.aspect;

public final class Aspects {
    /**
     * Wrap a function with caching behavior.
     *
     * @param fn           The function to wrap
     * @param keyExtractor Extracts cache key from input
     * @param cache        The cache to use
     * @return Wrapped function with caching
     */
    public static <T, K, R> Fn1<Promise<R>, T> withCaching(Fn1<Promise<R>, T> fn,
                                                           Fn1<K, T> keyExtractor,
                                                           Cache<K, R> cache) {
        return input -> {
            var key = keyExtractor.apply(input);
            return cache.get(key)
                        .flatMap(opt -> opt.fold(
                            () -> fn.apply(input)
                                    .flatMap(result -> cache.put(key, result)
                                                            .map(_ -> result)),
                            Promise::success));
        };
    }
}
```

### 3. Annotations

#### @Aspect

```java
package org.pragmatica.aether.slice;

@Retention(CLASS)
@Target(METHOD)
public @interface Aspect {
    AspectKind[] value();
}

public enum AspectKind {
    CACHE, LOG, METRICS, RETRY, TIMEOUT
}
```

#### @Key

```java
package org.pragmatica.aether.slice;

@Retention(CLASS)
@Target(FIELD)
public @interface Key {}
```

Usage in request records:

```java
public record GetUserRequest(@Key UserId userId, boolean includeDetails) {}
```

### 4. Configuration Hierarchy

Configuration follows the pattern: `[sliceName.methodName.section]` → `[sliceName.section]` → `[default.section]`

Example `config.toml`:

```toml
# Default cache settings
[default.cache]
ttl = "5m"
max_entries = 1000

# OrderService slice defaults
[orderService.cache]
ttl = "10m"
max_entries = 5000

# OrderService.getOrder method override
[orderService.getOrder.cache]
ttl = "30m"
max_entries = 10000
```

ConfigService API:

```java
// Resolution: methodLevel → sliceLevel → default
Promise<Option<TimeSpan>> getSliceTimeSpan(String sliceName,
                                            String methodName,
                                            String section,
                                            String key);
```

### 5. Code Generation

From annotated slice:

```java
@Slice
public interface OrderService {
    @Aspect(CACHE)
    Promise<Order> getOrder(GetOrderRequest request);

    Promise<Unit> createOrder(CreateOrderRequest request);
}
```

Generated factory code:

```java
public static Promise<OrderService> orderService(InventoryService inventory) {
    return SliceRuntime.getAspectFactory()
                       .async()
                       .flatMap(factory -> createCaches(factory))
                       .map(caches -> {
                           var impl = new OrderServiceImpl(inventory);

                           // Wrap getOrder with caching
                           Fn1<Promise<Order>, GetOrderRequest> getOrderCached =
                               Aspects.withCaching(
                                   impl::getOrder,
                                   GetOrderRequest::userId,  // @Key field
                                   caches.orderCache);

                           return new OrderServiceWrapper(getOrderCached, impl::createOrder);
                       });
}

private static Promise<Caches> createCaches(AspectFactory factory) {
    return factory.create(Cache.class,
                          CacheConfig.cacheConfig("orderService.getOrder",
                                                   new TypeToken<UserId>() {},
                                                   new TypeToken<Order>() {}))
                  .async()
                  .map(cache -> new Caches(cache));
}
```

### 6. Key Extraction

The `@Key` annotation marks fields used for cache key construction:

**Single key:**
```java
record GetUserRequest(@Key UserId userId) {}
// Key extractor: GetUserRequest::userId
```

**Composite key:**
```java
record GetItemRequest(@Key TenantId tenant, @Key ItemId item) {}
// Key extractor generates: request -> new CompositeKey(request.tenant(), request.item())
```

**No @Key:**
```java
record SimpleRequest(String data) {}
// Entire request used as key: Function.identity()
```

### 7. Aspect Lifecycle

```
Startup:
  1. Aether creates AspectFactory implementation
  2. SliceRuntime.setAspectFactory(factory)
  3. Slice factories called with factory available
  4. Caches/infrastructure created per factory.create()

Request:
  1. Request arrives at wrapped method
  2. Key extracted from request
  3. Cache checked → hit returns cached value
  4. Cache miss → original method called
  5. Result cached, returned

Shutdown:
  1. SliceRuntime.clear()
  2. Caches cleaned up by AspectFactory implementation
```

### Contracts Summary

| Component | Responsibility |
|-----------|---------------|
| `AspectConfig` | Marker for type-safe config |
| `AspectFactory` | Creates infrastructure from config |
| `SliceRuntime` | Holds factory singleton |
| `Cache<K,V>` | Typed cache operations |
| `CacheConfig` | Cache parameters + type tokens |
| `Aspects.withCaching()` | Function composition |
| `@Aspect` | Marks methods for wrapping |
| `@Key` | Marks key fields in requests |
| ConfigService | Hierarchical config resolution |
| slice-processor | Generates wiring code |

## Examples

### Minimal Cache Usage

```java
@Slice
public interface UserService {
    @Aspect(CACHE)
    Promise<User> getUser(GetUserRequest request);
}

record GetUserRequest(@Key UserId userId) {}
```

### Multiple Aspects

```java
@Slice
public interface PaymentService {
    @Aspect({CACHE, METRICS, RETRY})
    Promise<Receipt> processPayment(PaymentRequest request);
}
```

Generated wrapping order (inside-out):
```java
var fn = impl::processPayment;
fn = Aspects.withRetry(fn, retryPolicy);
fn = Aspects.withMetrics(fn, metricsConfig);
fn = Aspects.withCaching(fn, keyExtractor, cache);
```

### Custom TTL Per Method

```toml
[userService.getUser.cache]
ttl = "1h"

[userService.getUserPreferences.cache]
ttl = "5m"
```

### Composite Key

```java
record TenantItemRequest(@Key TenantId tenant, @Key ItemId item, boolean verbose) {}
```

Generated:
```java
record TenantItemKey(TenantId tenant, ItemId item) {}

Fn1<TenantItemKey, TenantItemRequest> keyExtractor =
    req -> new TenantItemKey(req.tenant(), req.item());
```

## Edge Cases

### AspectFactory Not Configured

If `SliceRuntime.getAspectFactory()` returns failure:
- Slice factory returns failed Promise
- No partial initialization

### Cache Unavailable

If `factory.create(Cache.class, config)` fails:
- Slice factory returns failed Promise
- Or: fallback to no-op cache (configurable)

### No @Key Fields

If request has no `@Key` annotation:
- Entire request used as cache key
- Warning logged at compile time (large objects as keys)

### Conflicting Aspects

`@Aspect({CACHE, CACHE})` - duplicate ignored with warning

## Future Aspects

| Aspect | Infrastructure | Config |
|--------|---------------|--------|
| `LOG` | Logger | LogConfig |
| `METRICS` | MetricsRegistry | MetricsConfig |
| `RETRY` | RetryPolicy | RetryConfig |
| `TIMEOUT` | Scheduler | TimeoutConfig |
| `CIRCUIT_BREAKER` | CircuitBreaker | CircuitBreakerConfig |

All follow same pattern:
1. Config implements `AspectConfig`
2. Created via `AspectFactory.create()`
3. Composed via `Aspects.with*()`

## Breaking Changes

Changes requiring version bump:

1. `AspectFactory` interface changes
2. `AspectConfig` contract changes
3. Config hierarchy resolution rules
4. Generated code patterns
5. Annotation semantics

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) - Slice interface pattern
- [RFC-0007: Dependency Sections](RFC-0007-dependency-sections.md) - Infrastructure loading
