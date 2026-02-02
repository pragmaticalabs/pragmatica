---
RFC: 0004
Title: Slice Packaging
Status: Draft
Author: Sergiy Yevtushenko
Created: 2026-01-16
Updated: 2026-01-16
Affects: [jbct-cli, aether]
---

## Summary

Defines the packaging format for slice artifacts, including JAR structure, MANIFEST.MF entries, dependency file format, and fat JAR bundling rules.

## Motivation

Each slice is packaged into a single JAR artifact (fat JAR) containing the `@Slice` interface, implementation, and all bundled dependencies. Consumers depend on the `@Slice` interface directly - no separate API artifact is needed. The runtime must understand the JAR structure to correctly load slices with proper classloader isolation. This RFC establishes the contract between the packaging process (jbct-cli) and the runtime loader (aether).

## Design

### Boundaries

- **jbct-cli**: Packages slices via `jbct:package-slices` Maven goal
- **aether**: Loads slice JARs, reads manifests and dependency files, configures classloaders

### 1. Artifact Naming

From a source module containing `@Slice` interface `OrderService`:

| Artifact | Naming Pattern | Example |
|----------|----------------|---------|
| Slice JAR | `{module}-{slice-suffix}-{version}.jar` | `commerce-order-service-1.0.0.jar` |
| Slice POM | `{module}-{slice-suffix}-{version}.pom` | `commerce-order-service-1.0.0.pom` |

The `slice-suffix` is derived from the interface name using kebab-case conversion:
- `OrderService` → `order-service`
- `UserManagement` → `user-management`

### 2. Slice JAR Contents (Fat JAR)

The slice JAR is a fat JAR containing everything needed to run the slice, including the public `@Slice` interface:

```
commerce-order-service-1.0.0.jar
├── org/example/order/
│   ├── OrderService.class              # @Slice interface (public API)
│   ├── OrderServiceImpl.class          # Implementation
│   ├── OrderServiceFactory.class       # Generated factory
│   ├── OrderServiceFactory$InventoryServiceProxy.class
│   ├── OrderServiceFactory$PaymentServiceProxy.class
│   ├── PlaceOrderRequest.class         # Request types
│   ├── PlaceOrderRequest$Item.class    # Nested request types
│   ├── OrderResult.class               # Response types
│   └── internal/                       # Subpackage classes
│       └── OrderValidator.class
├── org/example/shared/                 # Sibling shared package
│   └── CommonUtils.class
├── com/fasterxml/jackson/...           # Bundled external libs
├── META-INF/
│   ├── MANIFEST.MF                     # With Slice-* entries
│   ├── slice/
│   │   └── OrderService.manifest       # Slice manifest
│   ├── dependencies/
│   │   └── org.example.order.OrderServiceFactory  # Dependency file
│   └── services/                       # Merged service files
│       └── ...
└── ...

**Inclusion rules:**
- `@Slice` interface (consumers depend on this directly)
- Implementation class
- Generated factory and proxy classes
- All request/response types referenced by slice methods
- Nested classes of request/response types
- Bundled external dependencies
```

### 3. MANIFEST.MF Entries

The slice JAR's MANIFEST.MF includes slice-specific entries:

```
Manifest-Version: 1.0
Slice-Artifact: org.example:commerce-order-service:1.0.0
Slice-Class: org.example.order.OrderServiceFactory
```

| Entry | Description | Example |
|-------|-------------|---------|
| `Slice-Artifact` | Full Maven coordinates | `org.example:commerce-order-service:1.0.0` |
| `Slice-Class` | Fully qualified factory class name | `org.example.order.OrderServiceFactory` |

**Aether usage:**
```java
var manifest = jar.getManifest();
var artifact = manifest.getMainAttributes().getValue("Slice-Artifact");
var factoryClass = manifest.getMainAttributes().getValue("Slice-Class");
```

### 4. Dependency File Format

Location: `META-INF/dependencies/{FactoryClassName}`

```
[shared]
org.pragmatica-lite:core:^0.9.0

[infra]
org.pragmatica-lite.aether:infra-cache:^0.7.0

[slices]
org.example:inventory-service:^1.0.0
org.example:payment-service:^2.0.0
```

#### Sections

| Section | Purpose | ClassLoader Treatment |
|---------|---------|----------------------|
| `[shared]` | Libraries with shared instances | Loaded in shared classloader |
| `[infra]` | Infrastructure with shared instances via InfraStore | Loaded in infra classloader |
| `[slices]` | Slice dependencies (for resolution ordering) | Resolved recursively |

#### Version Format

Uses semver ranges:
- `^1.0.0` - Compatible with 1.x.x (>=1.0.0 <2.0.0)
- `~1.0.0` - Patch-level compatible (>=1.0.0 <1.1.0)
- `1.0.0` - Exact version

### 5. Fat JAR Bundling Rules

External dependencies (compile/runtime scope) are bundled into the impl JAR:

**Bundled:**
- Compile-scope dependencies (Jackson, Guava, etc.)
- Runtime-scope dependencies
- Application shared code (sibling `shared` package)
- Slice subpackages (internal utilities)

**NOT Bundled:**
- Aether runtime libraries (`slice-annotations`, `slice-api`, `infra-api`)
- Pragmatica Lite core (`org.pragmatica-lite:core`)
- Provided-scope dependencies (resolved by platform)
- Slice dependencies (loaded separately)
- Infrastructure dependencies (shared via InfraStore)

**Required `provided` Scope:**

All `org.pragmatica-lite` and `org.pragmatica-lite.aether` dependencies **must** use `provided` scope in slice projects. The `jbct:verify-slice` goal validates this requirement and fails the build if violated.

```xml
<!-- CORRECT: provided scope -->
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>core</artifactId>
    <scope>provided</scope>
</dependency>

<!-- WRONG: compile scope (default) - causes build failure -->
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>core</artifactId>
</dependency>
```

**Rationale:**
- Aether runtime provides these libraries with classloader isolation
- Bundling causes version conflicts and classloading issues
- Reduces slice JAR size significantly

**Bundling process:**
```java
// For each external dependency JAR:
// 1. Extract all class files
// 2. Skip META-INF/ except META-INF/services/
// 3. Skip module-info.class
// 4. Merge META-INF/services/ files
```

### 6. Slice Manifest Format

Location: `META-INF/slice/{SliceName}.manifest`

Properties file containing packaging metadata:

```properties
# Identity
slice.name=OrderService
slice.artifactSuffix=order-service
slice.package=org.example.order

# Classes in Slice JAR
slice.interface=org.example.order.OrderService
impl.classes=org.example.order.OrderService,\
             org.example.order.OrderServiceImpl,\
             org.example.order.OrderServiceFactory,\
             org.example.order.OrderServiceFactory$InventoryServiceProxy,\
             org.example.order.OrderServiceFactory$PaymentServiceProxy

# Request/Response types
request.classes=org.example.order.PlaceOrderRequest
response.classes=org.example.order.OrderResult

# Artifact coordinates
base.artifact=org.example:commerce
slice.artifactId=commerce-order-service

# Dependencies (all via invoker proxy)
dependencies.count=2
dependency.0.interface=org.example.inventory.InventoryService
dependency.0.artifact=org.example:inventory-service
dependency.0.version=1.0.0
dependency.1.interface=org.example.payment.PaymentService
dependency.1.artifact=org.example:payment-service
dependency.1.version=1.2.0

# Runtime config
config.file=slices/OrderService.toml

# Metadata
generated.timestamp=2026-01-15T10:30:00Z
processor.version=0.5.0
```

### 7. Shared Code Inclusion

Application shared code is automatically included in the impl JAR:

1. **Sibling shared package**: `org.example.shared` for slice `org.example.order.OrderService`
2. **Slice subpackages**: `org.example.order.internal`, `org.example.order.utils`, etc.

```
org.example/
├── order/              # Slice package
│   ├── OrderService.java
│   └── internal/       # Included in impl JAR
│       └── Helper.java
└── shared/             # Sibling shared - included in impl JAR
    └── Utils.java
```

### Contracts Summary

| Component | jbct-cli Generates | aether Expects |
|-----------|-------------------|----------------|
| Slice JAR | Fat JAR with @Slice interface + impl + deps | Slice deployment unit |
| MANIFEST.MF | `Slice-Artifact`, `Slice-Class` | Slice discovery |
| Dependency file | `META-INF/dependencies/{Factory}` | ClassLoader configuration |
| Slice manifest | `META-INF/slice/{Name}.manifest` | Packaging metadata |

## Examples

### Multi-Slice Module

Source module with two slices:

```
commerce/
├── pom.xml
└── src/main/java/org/example/
    ├── order/
    │   ├── OrderService.java       # @Slice
    │   └── OrderServiceImpl.java
    └── payment/
        ├── PaymentService.java     # @Slice
        └── PaymentServiceImpl.java
```

Produces two JARs (one per slice):
```
target/
├── commerce-order-service-1.0.0.jar
└── commerce-payment-service-1.0.0.jar
```

### Dependency File Example

```
[shared]
org.pragmatica-lite:core:^0.11.2
org.pragmatica-lite:json:^0.11.2

[infra]
org.pragmatica-lite.aether:infra-cache:^0.7.5
org.pragmatica-lite.aether:infra-metrics:^0.7.5

[slices]
org.example:inventory-service:^1.0.0
```

## Edge Cases

### No External Dependencies

If slice has no external dependencies, dependency file sections may be empty or omitted.

### Nested Request/Response Types

Nested types are included in API JAR:
```java
public interface OrderService {
    record PlaceOrderRequest(List<Item> items) {
        public record Item(String sku, int quantity) {}  // Included
    }
}
```

### Class Name Conflicts

If bundled libraries have conflicting class names, last-wins during extraction. Recommendation: Use shading or relocating if conflicts occur.

## Breaking Changes

Changes requiring version bump:

1. MANIFEST.MF entry names or format
2. Dependency file section names or format
3. Slice manifest property names
4. JAR naming convention changes
5. Bundling inclusion/exclusion rules

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) - Factory class naming
- [RFC-0002: Dependency Protocol](RFC-0002-dependency-protocol.md) - Artifact coordinates
- [RFC-0007: Dependency Sections](RFC-0007-dependency-sections.md) - ClassLoader hierarchy
