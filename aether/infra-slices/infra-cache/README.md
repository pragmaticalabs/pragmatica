# Aether Infrastructure: Cache

Key-value cache service with TTL support for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-cache</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides a Redis-like caching API with in-memory implementation. Uses `InfraStore` for singleton-per-node semantics.

### Key Features

- Key-value operations with optional TTL
- Batch operations (getMulti, setMulti, deleteMulti)
- Atomic counters (increment, decrement)
- Pattern-based key operations
- Cache statistics

### Quick Start

```java
var cache = CacheService.cacheService();

// Basic operations
cache.set("user:123", "{\"name\":\"Alice\"}").await();
cache.get("user:123").await(); // Option.some("{\"name\":\"Alice\"}")

// With TTL
cache.set("session:abc", "token", Duration.ofMinutes(30)).await();

// Counters
cache.increment("visits:page1").await(); // 1
cache.incrementBy("visits:page1", 5).await(); // 6

// Pattern operations
cache.keys("user:*").await(); // Set of matching keys
cache.deletePattern("session:*").await(); // Delete expired sessions
```

### API Summary

| Category | Methods |
|----------|---------|
| Basic | `set`, `get`, `delete`, `exists` |
| TTL | `expire`, `ttl` |
| Batch | `getMulti`, `setMulti`, `deleteMulti` |
| Counter | `increment`, `incrementBy`, `decrement` |
| Pattern | `keys`, `deletePattern` |
| Admin | `stats`, `clear` |

### Configuration

```java
var config = CacheConfig.cacheConfig()
    .maxEntries(10_000)
    .defaultTtl(Duration.ofHours(1));

var cache = CacheService.cacheService(config);
```

## License

Apache License 2.0
