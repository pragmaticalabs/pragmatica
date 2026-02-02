# Slice Loading Mechanism

This document describes how Aether loads slices and resolves their dependencies at runtime.

## Overview

Slice loading is triggered by KV-Store events and involves:
1. Locating the slice JAR in repositories
2. Reading manifest and dependency files
3. Setting up ClassLoader hierarchy
4. Resolving transitive slice dependencies
5. Instantiating the slice via factory method

## Loading Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           TRIGGER: KV-Store Event                            │
│                                                                              │
│  ClusterDeploymentManager writes: slices/{nodeId}/{artifact} = LOAD         │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 1: NodeDeploymentManager.onValuePut()                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • Filters: only handles keys for THIS node                                 │
│  • Records deployment state                                                  │
│  • Triggers: processStateTransition(LOAD)                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 2: SliceStore.loadSlice(artifact)                                     │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • Check: already loaded? → return existing                                 │
│  • Locate: search repositories for JAR                                      │
│  • Delegate: DependencyResolver.resolve()                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 3: DependencyResolver.resolve()                                       │
│  ─────────────────────────────────────────────────────────────────────────  │
│  3a. Check registry for already-loaded slice                                │
│  3b. Check for circular dependency                                          │
│  3c. repository.locate(artifact) → get JAR URL                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 4: Read JAR Manifest (META-INF/MANIFEST.MF)                           │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • SliceManifest.read(jarUrl)                                               │
│  • Extract: Slice-Artifact, Slice-Class                                     │
│  • Validate: requested artifact == manifest artifact                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 5: Load Dependency File (META-INF/dependencies/{className})           │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • DependencyFile.load(sliceClassName, tempClassLoader)                     │
│  • Parse sections: [api], [shared], [infra], [slices]                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 6: Process API Dependencies                                           │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • SharedDependencyLoader.processApiDependencies()                          │
│  • Load into SharedLibraryClassLoader                                       │
│  • Contains typed interfaces for generated proxies                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 7: Process Shared Dependencies                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • SharedDependencyLoader.processSharedDependencies()                       │
│  For each dependency:                                                       │
│    • Not loaded → load into SharedLibraryClassLoader                        │
│    • Compatible → reuse existing                                            │
│    • Conflict   → add to slice's conflict list                              │
│  Result: SliceClassLoader with [sliceJAR + conflictJARs]                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 8: Process Infra Dependencies                                         │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • SharedDependencyLoader.processInfraDependencies()                        │
│  For each [infra] dependency:                                               │
│    • Not loaded → load JAR into SharedLibraryClassLoader                    │
│    • Already loaded → check version compatibility                           │
│  Note: Instance creation deferred to slice code via InfraStore              │
│  Note: Infra services control their own sharing strategy (singleton, etc.)  │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 9: Resolve Slice Dependencies (Recursive)                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • resolveSliceDependencies(sliceDeps)                                      │
│  For each slice dependency:                                                 │
│    • Check registry for compatible version                                  │
│    • Not found → RECURSE to Step 3                                          │
│    • Circular dependency detected → fail                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 10: Instantiate Slice via Factory Method                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • SliceFactory.createSlice(sliceClass, dependencies, descriptors)          │
│  • Find static factory: ClassName.className(deps...)                        │
│  • Verify parameters match resolved dependencies                            │
│  • Invoke reflectively                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 11: Register in SliceRegistry                                         │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • registry.register(artifact, slice)                                       │
│  • Makes slice available for other slices' dependencies                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 12: Store Entry and Update State                                      │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • SliceStore: entries.put(artifact, LoadedSliceEntry)                      │
│  • NodeDeploymentManager: transitionTo(LOADED)                              │
│  • Write to KV: slices/{nodeId}/{artifact} = LOADED                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                    ═══════════════════════════════════════
                                 LOADED STATE
                    ═══════════════════════════════════════
                                      │
                                      ▼ (auto-activate)
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step 13: Activation (ACTIVATE → ACTIVATING → ACTIVE)                       │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • slice.start()                                                            │
│  • Create SliceBridge for serialization boundary                            │
│  • Register with InvocationHandler                                          │
│  • Publish endpoints to KV-Store                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

## ClassLoader Hierarchy

Aether uses a three-tier ClassLoader hierarchy to balance isolation with sharing:

```
┌─────────────────────────────────────┐
│     System/Bootstrap ClassLoader     │  ← JDK classes (java.*, javax.*)
└─────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│     Node/Application ClassLoader     │  ← Aether runtime, Pragmatica Core
└─────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│    SharedLibraryClassLoader          │  ← Shared dependencies (first wins)
│    ─────────────────────────────────│    + API JARs for typed interfaces
│    Tracks: groupId:artifactId→Version│
└─────────────────────────────────────┘
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
┌─────────────────┐  ┌─────────────────┐
│ SliceClassLoader │  │ SliceClassLoader │  ← Child-first for isolation
│ [SliceA.jar]     │  │ [SliceB.jar]     │  ← + Conflicting deps
│ [conflict1.jar]  │  │                  │
└─────────────────┘  └─────────────────┘
```

### SharedLibraryClassLoader

- Uses parent-first delegation (standard)
- Tracks loaded artifacts with versions
- First slice to load a dependency sets the canonical version
- Subsequent slices check compatibility

### SliceClassLoader

- Uses **child-first** delegation for slice isolation
- Parent-first only for JDK classes (`java.*`, `javax.*`, `jdk.*`, `sun.*`)
- Contains: slice JAR + any conflicting dependency JARs

## Dependency File Format

Located at `META-INF/dependencies/{fully.qualified.ClassName}`:

```
# Comment lines start with #

[api]
# API modules for typed slice dependencies
# Loaded into SharedLibraryClassLoader for cross-slice type visibility
org.example:inventory-api:^1.0.0
org.example:pricing-api:^1.0.0

[shared]
# Libraries shared across all slices
# First loader wins; conflicts go to slice classloader
org.pragmatica-lite:core:^0.8.0
org.example:order-domain:^1.0.0

[infra]
# Infrastructure services (caching, database, etc.)
# JARs loaded into SharedLibraryClassLoader; instances shared via InfraStore
org.pragmatica-lite.aether:infra-cache:^0.7.0
org.pragmatica-lite.aether:infra-database:^0.7.0

[slices]
# Runtime slice dependencies (other slices this slice calls)
# Resolved recursively
org.example:inventory-service:^1.0.0
org.example:pricing-service:^1.0.0
```

### Section Purposes

| Section | Purpose | Loaded Into |
|---------|---------|-------------|
| `[api]` | Typed interfaces for generated proxies | SharedLibraryClassLoader |
| `[shared]` | Libraries used by multiple slices | SharedLibraryClassLoader (or SliceClassLoader on conflict) |
| `[infra]` | Infrastructure services with instance sharing | SharedLibraryClassLoader (instances via InfraStore) |
| `[slices]` | Other slices this slice depends on | Resolved recursively, passed to factory |

### Infrastructure Services

The `[infra]` section supports infrastructure services like caching, database connections, and configuration.
Unlike `[shared]`, infra services share **instances** across slices via `InfraStore`:

```java
// In slice code - obtains shared instance
var cache = CacheService.cacheService();

// Inside CacheService factory - uses InfraStore
static CacheService cacheService() {
    return InfraStore.instance()
        .map(store -> store.getOrCreate(
            "org.pragmatica-lite.aether:infra-cache",
            "0.7.0",
            CacheService.class,
            InMemoryCacheService::inMemoryCacheService))
        .or(InMemoryCacheService::inMemoryCacheService);  // fallback if InfraStore not configured
}
```

#### InfraStore.getOrCreate() Behavior

The `getOrCreate` method provides atomic instance creation with the following guarantees:

| Aspect | Behavior |
|--------|----------|
| **Thread-safety** | Only one instance created per (artifactKey, version) pair, even under concurrent access |
| **Version matching** | Qualifiers are stripped (e.g., `1.0.0-SNAPSHOT` matches `1.0.0`) |
| **Return type** | Returns `T` directly (not wrapped in Result) |
| **Factory exceptions** | If factory throws, exception propagates to caller |

**Important**: Infra service factories should handle their own initialization errors. The `getOrCreate` method does not wrap factory exceptions - a throwing factory will cause the exception to propagate to all concurrent callers waiting for that instance.

```java
// Safe factory pattern - handles initialization errors
static CacheService cacheService() {
    return InfraStore.instance()
        .map(store -> store.getOrCreate(
            KEY, VERSION, CacheService.class,
            () -> {
                // Factory should not throw - return fallback on error
                try {
                    return RedisCacheService.redisCacheService();
                } catch (Exception e) {
                    log.warn("Redis unavailable, using in-memory cache", e);
                    return InMemoryCacheService.inMemoryCacheService();
                }
            }))
        .or(InMemoryCacheService::inMemoryCacheService);
}
```

#### Three Sharing Patterns

**1. Singleton** (default): All slices share same instance
```java
static CacheService cacheService() {
    return InfraStore.instance()
        .map(store -> store.getOrCreate(KEY, VERSION, CacheService.class,
            InMemoryCacheService::inMemoryCacheService))
        .or(InMemoryCacheService::inMemoryCacheService);
}
```

**2. Shared Core + Adapter**: Core singleton wrapped with per-slice adapter
```java
static DatabaseAdapter databaseAdapter() {
    var core = DatabaseCore.databaseCore(); // singleton via InfraStore
    return new DatabaseAdapter(core);       // fresh adapter each call
}
```

**3. Factory Pattern**: Infra service is a factory, produces new instances
```java
static HttpClient httpClient() {
    var factory = InfraStore.instance()
        .map(store -> store.getOrCreate(KEY, VERSION, HttpClientFactory.class,
            HttpClientFactory::httpClientFactory))
        .or(HttpClientFactory::httpClientFactory);
    return factory.create(); // new client each call
}
```

## JAR Manifest

Required attributes in `META-INF/MANIFEST.MF`:

```
Manifest-Version: 1.0
Slice-Artifact: org.example:my-slice:1.0.0
Slice-Class: org.example.MySlice
```

| Attribute | Description |
|-----------|-------------|
| `Slice-Artifact` | Maven coordinates (groupId:artifactId:version) |
| `Slice-Class` | Fully qualified name of the slice class |

## Version Conflict Resolution

When a shared dependency is requested:

1. **Not loaded**: Load into SharedLibraryClassLoader, record version
2. **Compatible**: Reuse existing (version satisfies pattern)
3. **Conflict**: Load into SliceClassLoader (child-first shadows parent)

Version patterns supported:
- `1.0.0` - Exact version
- `^1.0.0` - Caret (compatible with 1.x.x)
- `~1.0.0` - Tilde (compatible with 1.0.x)
- `>=1.0.0` - Comparison
- `[1.0.0,2.0.0)` - Range

## Slice Instantiation

Slices are instantiated via static factory method:

```java
public interface OrderService extends Slice {
    // Factory method: lowercase-first class name
    static OrderService orderService(InventoryService inventory,
                                     PricingService pricing) {
        return new OrderServiceImpl(inventory, pricing);
    }
}
```

**Factory method convention:**
- Static method
- Name: lowercase-first version of class name
- Returns: instance of the slice class
- Parameters: resolved dependency slices in declaration order

## Key Components

| Component | File | Responsibility |
|-----------|------|----------------|
| `NodeDeploymentManager` | `node/.../NodeDeploymentManager.java` | KV-Store event handling, state transitions |
| `SliceStore` | `slice/.../SliceStore.java` | Slice lifecycle management |
| `DependencyResolver` | `slice/.../DependencyResolver.java` | Orchestrates dependency resolution |
| `SliceManifest` | `slice/.../SliceManifest.java` | Reads JAR manifest |
| `DependencyFile` | `slice/.../DependencyFile.java` | Parses dependency file |
| `SharedDependencyLoader` | `slice/.../SharedDependencyLoader.java` | Loads shared/API/infra dependencies |
| `SliceFactory` | `slice/.../SliceFactory.java` | Instantiates slice via reflection |
| `SliceClassLoader` | `slice/.../SliceClassLoader.java` | Child-first ClassLoader for isolation |
| `SharedLibraryClassLoader` | `slice/.../SharedLibraryClassLoader.java` | Shared dependency ClassLoader |
| `SliceRegistry` | `slice/.../SliceRegistry.java` | Tracks loaded slices |
| `InfraStore` | `infra-api/.../InfraStore.java` | Per-node infra instance sharing |
| `InfraStoreImpl` | `node/.../InfraStoreImpl.java` | Thread-safe infra store implementation |
| `VersionedInstance` | `infra-api/.../VersionedInstance.java` | Version-tagged instances with compatibility |

## Error Handling

Common failure scenarios:

| Error | Cause | Resolution |
|-------|-------|------------|
| `Artifact not found` | JAR not in any repository | Check repository configuration |
| `Manifest not found` | Missing MANIFEST.MF | Ensure slice JAR is properly built |
| `Missing Slice-Artifact` | Incomplete manifest | Add required manifest attributes |
| `Circular dependency` | A→B→A | Restructure slice dependencies |
| `Factory method not found` | No matching static method | Add `className()` factory method |
| `Parameter count mismatch` | Dependencies don't match factory | Align dependency file with factory signature |

## State Transitions

```
LOAD → LOADING → LOADED → ACTIVATE → ACTIVATING → ACTIVE
         ↓                                ↓
      FAILED ←---------------------------+
         ↓
      UNLOAD → UNLOADING → [removed]
         ↑
   DEACTIVATE ← ACTIVE → DEACTIVATING → LOADED
```

See [slice-lifecycle.md](slice-lifecycle.md) for detailed state machine documentation.
