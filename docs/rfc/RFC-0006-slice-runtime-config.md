---
RFC: 0006
Title: Slice Runtime Config
Status: Draft
Author: Sergiy Yevtushenko
Created: 2026-01-16
Updated: 2026-01-16
Affects: [jbct-cli, aether]
---

## Summary

Defines the per-slice configuration format (`{SliceName}.toml`) for runtime properties like instance count, timeouts, and memory limits.

## Motivation

Different slices have different scaling and resource requirements. A high-traffic user service needs more instances than a rarely-used admin service. This RFC establishes the configuration format that developers use to specify slice runtime properties, which are then incorporated into the deployment blueprint.

## Design

### Boundaries

- **jbct-cli**: Reads config files, incorporates into blueprint generation
- **aether Forge**: Applies configuration during deployment

### 1. File Location

Per-slice configuration files are located at:

```
src/main/resources/slices/{SliceName}.toml
```

Example: For `OrderService` slice:
```
src/main/resources/slices/OrderService.toml
```

The slice manifest references this path via `config.file` property.

### 2. TOML Structure

```toml
# Slice configuration for OrderService

[blueprint]
# Required: number of slice instances
instances = 3

# Optional: request timeout in milliseconds
timeout_ms = 30000

# Optional: memory allocation per instance in MB
memory_mb = 512

# Optional: load balancing strategy
load_balancing = "round_robin"

# Optional: request field for sticky routing
affinity_key = "customerId"
```

### 3. Blueprint Section Properties

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `instances` | integer | Yes | 1 | Number of slice instances to deploy |
| `timeout_ms` | integer | No | runtime default | Request timeout in milliseconds |
| `memory_mb` | integer | No | runtime default | Memory allocation per instance |
| `load_balancing` | string | No | `round_robin` | Load balancing strategy |
| `affinity_key` | string | No | none | Request field for sticky routing |

### 4. Default Behavior

If no config file exists for a slice:

```java
SliceConfig.defaults()
// instances = 1
// timeout_ms = none (use runtime default)
// memory_mb = none (use runtime default)
// load_balancing = none (use runtime default)
// affinity_key = none
```

The generator logs an info message when using defaults:
```
[INFO] No config file specified for slice: OrderService - using defaults
```

### 5. Instance Scaling

The `instances` property determines horizontal scaling:

```toml
[blueprint]
instances = 5  # Deploy 5 instances of this slice
```

**Guidelines:**
- Stateless slices: Scale based on expected load
- Stateful slices: Consider affinity routing
- Minimum: 1 (single instance)
- Maximum: Limited by cluster capacity

### 6. Timeout Configuration

The `timeout_ms` property sets request deadline:

```toml
[blueprint]
timeout_ms = 30000  # 30 second timeout
```

**Guidelines:**
- External API calls: Higher timeout (30s+)
- Internal operations: Lower timeout (5-10s)
- Default: Runtime-configured (typically 60s)

### 7. Memory Allocation

The `memory_mb` property sets per-instance memory:

```toml
[blueprint]
memory_mb = 1024  # 1GB per instance
```

**Guidelines:**
- In-memory caching: Higher allocation
- Simple CRUD: Lower allocation (256-512MB)
- Default: Runtime-configured

### 8. Load Balancing

The `load_balancing` property selects routing strategy:

```toml
[blueprint]
load_balancing = "least_connections"
```

| Strategy | Use Case |
|----------|----------|
| `round_robin` | Uniform request distribution (default) |
| `least_connections` | Long-running requests, variable latency |
| `random` | Simple, stateless routing |

### 9. Affinity Routing

The `affinity_key` property enables sticky sessions:

```toml
[blueprint]
affinity_key = "userId"
```

Requests with the same `userId` value route to the same instance. Useful for:
- Session state
- Local caching
- Connection pooling

**Extraction:** Aether extracts the field from the request object by name.

### 10. Manifest Reference

The slice manifest links to the config file:

```properties
# In META-INF/slice/OrderService.manifest
config.file=slices/OrderService.toml
```

Generator reads this path relative to `target/classes/` during blueprint generation.

### Contracts Summary

| Component | Developer Configures | Generator Reads | Forge Applies |
|-----------|---------------------|-----------------|---------------|
| instances | `[blueprint].instances` | Blueprint generation | Instance scaling |
| timeout | `[blueprint].timeout_ms` | Blueprint generation | Request deadline |
| memory | `[blueprint].memory_mb` | Blueprint generation | Container limits |
| load balancing | `[blueprint].load_balancing` | Blueprint generation | Routing strategy |
| affinity | `[blueprint].affinity_key` | Blueprint generation | Sticky routing |

## Examples

### Minimal Config

```toml
[blueprint]
instances = 2
```

### High-Traffic Service

```toml
[blueprint]
instances = 10
timeout_ms = 5000
memory_mb = 512
load_balancing = "least_connections"
```

### Session-Stateful Service

```toml
[blueprint]
instances = 4
timeout_ms = 30000
memory_mb = 1024
affinity_key = "sessionId"
```

### External API Gateway

```toml
[blueprint]
instances = 3
timeout_ms = 60000
memory_mb = 256
```

### Generated Blueprint Entry

From config:
```toml
# slices/OrderService.toml
[blueprint]
instances = 5
timeout_ms = 10000
memory_mb = 512
affinity_key = "customerId"
```

Generated:
```toml
# blueprint.toml
[[slices]]
artifact = "org.example:commerce-order-service:1.0.0"
instances = 5
timeout_ms = 10000
memory_mb = 512
affinity_key = "customerId"
```

## Edge Cases

### Missing Config File

If config file doesn't exist, defaults are used. No error is thrown.

### Invalid TOML

If config file has syntax errors, generator logs warning and uses defaults.

### Zero Instances

`instances = 0` is invalid. Minimum is 1.

### Unknown Properties

Unknown properties in `[blueprint]` section are ignored with a warning.

## Breaking Changes

Changes requiring version bump:

1. Config file location convention
2. Property names or types
3. Default value changes
4. New required properties

## References

- [RFC-0005: Blueprint Format](RFC-0005-blueprint-format.md) - How config flows to blueprint
- [RFC-0004: Slice Packaging](RFC-0004-slice-packaging.md) - Manifest config.file reference
