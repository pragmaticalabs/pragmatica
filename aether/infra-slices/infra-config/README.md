# Aether Infrastructure: Config

Hierarchical configuration service for the Aether distributed runtime.

## Overview

Provides hierarchical configuration management with GLOBAL, NODE, and SLICE scopes. More specific scopes override less specific ones (SLICE -> NODE -> GLOBAL). Uses TOML configuration format with type-safe value access and change watching.

## Usage

```java
var config = ConfigService.configService();

// Load TOML configuration
config.loadToml(ConfigScope.GLOBAL, tomlContent);

// Hierarchical lookup (SLICE -> NODE -> GLOBAL)
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

## Dependencies

- `pragmatica-lite-toml`
- `pragmatica-lite-core`
