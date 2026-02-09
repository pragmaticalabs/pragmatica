# Aether Infrastructure API

Core infrastructure service API for sharing instances across slices within an Aether node.

## Overview

Provides `InfraStore`, a per-node key-value store enabling infrastructure services (cache, database, secrets, etc.) to share instances across slices. Uses `VersionedInstance<T>` for semver-compatible version matching. Ensures consistent singleton-per-node semantics regardless of which slice requests the service.

### Sharing Patterns

1. **Singleton** - Single instance shared across all slices via `getOrCreate`
2. **Shared Core + Per-Slice Adapter** - Core singleton via InfraStore, fresh adapter each call
3. **Factory** - Infra service as factory, producing new instances per call

## Usage

```java
// Infrastructure services use InfraStore in their factory methods
public interface CacheService extends Slice {
    static CacheService cacheService() {
        return InfraStore.instance()
            .map(store -> store.getOrCreate(
                "org.pragmatica-lite.aether:infra-cache", "0.7.0",
                CacheService.class, InMemoryCacheService::inMemoryCacheService))
            .or(InMemoryCacheService::inMemoryCacheService);
    }
}
```

### InfraStore API

```java
public interface InfraStore {
    <T> List<VersionedInstance<T>> get(String artifactKey, Class<T> type);
    <T> T getOrCreate(String artifactKey, String version, Class<T> type, Supplier<T> factory);
    static Option<InfraStore> instance();
}
```

## Dependencies

- `pragmatica-lite-core` (for `Option` and functional types)
