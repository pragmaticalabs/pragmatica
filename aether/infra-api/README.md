# Aether Infrastructure API

Core infrastructure service API for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-api</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

This module provides the foundational API for infrastructure services to share instances across slices within an Aether node. Infrastructure services (cache, database, secrets, etc.) use `InfraStore` to ensure consistent instance sharing regardless of which slice requests the service.

### Key Components

| Component | Description |
|-----------|-------------|
| `InfraStore` | Per-node key-value store for infrastructure service instances |
| `VersionedInstance<T>` | Version-tagged instance with semver compatibility logic |

### InfraStore

Thread-safe store enabling infrastructure services to share instances across slices:

```java
public interface InfraStore {
    // Get all registered versions for an artifact
    <T> List<VersionedInstance<T>> get(String artifactKey, Class<T> type);

    // Atomic get-or-create (prevents race conditions)
    <T> T getOrCreate(String artifactKey, String version, Class<T> type, Supplier<T> factory);

    // Static accessor (set by AetherNode during startup)
    static Option<InfraStore> instance();
}
```

### Usage in Infrastructure Services

Infrastructure services use `InfraStore` in their factory methods to ensure singleton-per-node semantics:

```java
public interface CacheService extends Slice {
    static CacheService cacheService() {
        return InfraStore.instance()
            .map(store -> store.getOrCreate(
                "org.pragmatica-lite.aether:infra-cache",
                "0.7.0",
                CacheService.class,
                InMemoryCacheService::inMemoryCacheService))
            .or(InMemoryCacheService::inMemoryCacheService);
    }
}
```

### VersionedInstance

Provides semver-compatible version matching:

```java
public record VersionedInstance<T>(String version, T instance) {
    // Check compatibility (major must match, minor >= requested)
    boolean isCompatibleWith(String requested);

    // Find first compatible instance from list
    static <T> Option<T> findCompatible(List<VersionedInstance<T>> instances, String version);
}
```

## Three Sharing Patterns

### 1. Singleton (Share State)
```java
// Single instance shared across all slices
return InfraStore.instance()
    .map(store -> store.getOrCreate(KEY, VERSION, Type.class, Factory::create))
    .or(Factory::create);
```

### 2. Shared Core + Per-Slice Adapter
```java
// Core is singleton, adapter wraps it
var core = DatabaseCore.databaseCore(); // singleton via InfraStore
return new DatabaseAdapter(core);       // fresh adapter each call
```

### 3. Factory Pattern
```java
// Infra service IS a factory, produces new instances
var factory = InfraStore.instance()
    .map(store -> store.getOrCreate(KEY, VERSION, HttpClientFactory.class, ...))
    .or(...);
return factory.create(); // new client each call
```

## Zero Runtime Dependencies

This module depends only on `pragmatica-lite-core` for `Option` and functional types.

## License

Apache License 2.0
