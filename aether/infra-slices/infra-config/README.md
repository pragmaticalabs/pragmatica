# Aether Infrastructure: Config

Hierarchical configuration service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-config</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides hierarchical configuration management with GLOBAL, NODE, and SLICE scopes. More specific scopes override less specific ones.

### Key Features

- Hierarchical scopes (SLICE → NODE → GLOBAL)
- TOML configuration format
- Type-safe value access (String, Int, Boolean, Double, List)
- Configuration change watching

### Scope Hierarchy

```
SLICE   (most specific)  - Per-slice configuration
  ↓
NODE    - Per-node configuration
  ↓
GLOBAL  (least specific) - Cluster-wide configuration
```

### Quick Start

```java
var config = ConfigService.configService();

// Load TOML configuration
config.loadToml(ConfigScope.GLOBAL, """
    [database]
    host = "localhost"
    port = 5432
    pool_size = 10

    [cache]
    enabled = true
    ttl_seconds = 3600
    """);

// Hierarchical lookup (SLICE → NODE → GLOBAL)
var host = config.getString("database", "host").await();
var port = config.getInt("database", "port").await();
var enabled = config.getBoolean("cache", "enabled").await();

// Scope-specific lookup
var nodeHost = config.getString(ConfigScope.NODE, "database", "host").await();

// Set value at specific scope
config.set(ConfigScope.NODE, "database", "pool_size", 20).await();

// Watch for changes
config.watch("database", "host", newValue -> {
    newValue.onPresent(v -> System.out.println("Host changed to: " + v));
    return Unit.unit();
}).await();
```

### API Summary

| Category | Methods |
|----------|---------|
| Read | `getString`, `getInt`, `getBoolean`, `getDouble`, `getStringList` |
| Write | `set`, `loadToml` |
| Watch | `watch` |
| Document | `getDocument` |

### ConfigScope

```java
enum ConfigScope {
    GLOBAL,  // Cluster-wide defaults
    NODE,    // Node-specific overrides
    SLICE    // Slice-specific overrides
}
```

## License

Apache License 2.0
