# Slice Architecture

This document explains how Aether slices are built, from source code to deployable artifacts.

## What is a Slice?

A **slice** is a self-contained business capability that:
- Exposes a single-responsibility API via a Java interface
- Receives requests and returns responses asynchronously (`Promise<T>`)
- Declares its dependencies explicitly via a factory method
- Can be deployed, scaled, and updated independently

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory,
                                     PricingEngine pricing) {
        return OrderServiceImpl.orderServiceImpl(inventory, pricing);
    }
}
```

## Build Pipeline Overview

```mermaid
flowchart LR
    A["@Slice Interface"] --> B["Annotation Processor"]
    B --> C["Generated Code"]
    B --> D["Manifests"]
    C --> E["Compile"]
    D --> E
    E --> F["Package"]
    F --> G["Slice JARs"]
    G --> H["Blueprint Generation"]
    H --> I["blueprint.toml"]
```

## Annotation Processing Phase

When `javac` compiles a class annotated with `@Slice`, the `SliceProcessor` intercepts it and generates:

| Generated Artifact | Location | Purpose |
|-------------------|----------|---------|
| API Interface | `{package}.api.{SliceName}` | Public contract for consumers |
| Factory Class | `{package}.{SliceName}Factory` | Creates slice instances with wiring |
| Slice Manifest | `META-INF/slice/{SliceName}.manifest` | Metadata for packaging/deployment |
| API Manifest | `META-INF/slice-api.properties` | Maps artifact to API interface |

### Processing Flow

```mermaid
sequenceDiagram
    participant J as javac
    participant P as SliceProcessor
    participant M as SliceModel
    participant G as Generators

    J->>P: process(@Slice interface)
    P->>M: sliceModel(interface, env)
    M->>M: Extract methods
    M->>M: Extract factory method
    M->>M: Classify dependencies
    M-->>P: SliceModel
    P->>G: generate(model)
    G->>G: ApiInterfaceGenerator
    G->>G: FactoryClassGenerator
    G->>G: ManifestGenerator
    G-->>J: Generated sources + resources
```

### SliceModel Extraction

The `SliceModel` extracts from the `@Slice` interface:

```java
public record SliceModel(
    String packageName,        // e.g., "org.example.order"
    String simpleName,         // e.g., "OrderService"
    String qualifiedName,      // e.g., "org.example.order.OrderService"
    String apiPackage,         // e.g., "org.example.order.api"
    String factoryMethodName,  // e.g., "orderService"
    List<MethodModel> methods,
    List<DependencyModel> dependencies
) {}
```

**Method extraction rules:**
- Must return `Promise<T>` where `T` is the response type
- Must have exactly one parameter (the request type)
- Static methods and default methods are ignored

**Factory method detection:**
- Static method returning the interface type
- Parameters become dependencies
- Name becomes `factoryMethodName`

## Dependency Classification

Factory method parameters are classified into three categories:

```mermaid
flowchart TD
    D["Dependency Interface"] --> RQ{"Has @ResourceQualifier?"}
    RQ -->|Yes| R["Resource Dependency"]
    RQ -->|No| FM{"Has factory method?"}
    FM -->|Yes| P["Plain Interface"]
    FM -->|No| S["Slice Dependency"]
    R --> RP["ctx.resources().provide()"]
    P --> PF["Call factory directly"]
    P --> PT{"Factory has @ResourceQualifier params?"}
    PT -->|Yes| TR["Transitive resource provisioning"]
    S --> SP["Proxy record with MethodHandle"]
```

**Resource dependency:**
- Parameter annotated with a `@ResourceQualifier` meta-annotation
- Provisioned via `ctx.resources().provide(Type.class, "config.section")`
- Example: a `DataSource` parameter annotated with `@DatabaseResource("orders")`

**Slice dependency** (external slice):
- An interface without a static factory method (resolved via Aether runtime)
- Generates a local proxy record with `MethodHandle<R, I>` fields
- Handles resolved via `ctx.invoker().methodHandle(...)`
- Example: `org.example.inventory.InventoryService` is a slice dependency of `org.example.order.OrderService`

**Plain interface dependency:**
- A non-`@Slice` interface that has a static factory method
- Called directly: `PlainInterface.plainInterface()`
- If the plain interface's factory has `@ResourceQualifier` parameters, those resources are provisioned transitively
- Example: `org.example.order.validation.OrderValidator` is a plain interface dependency of `OrderService`

## Generated Code Deep Dive

### API Interface Generation

The API interface is a copy of the `@Slice` interface in the `.api` subpackage, minus the factory method:

**Input:**
```java
package org.example.order;

@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
    static OrderService orderService(InventoryService inv) { ... }
}
```

**Output:**
```java
package org.example.order.api;

public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);
}
```

**Design decision:** The API interface exists so consumers can depend only on the contract, not the implementation module.

### Factory Class Generation

The factory provides two entry points. The generated factory method signature is:

```java
public static Promise<SliceName> sliceName(Aspect<SliceName> aspect, SliceCreationContext ctx)
```

Full example:

```java
public final class OrderServiceFactory {
    private OrderServiceFactory() {}

    public static Promise<OrderService> orderService(
            Aspect<OrderService> aspect,
            SliceCreationContext ctx) {
        // Local proxy for slice dependency
        record inventoryService(MethodHandle<Integer, String> checkStockHandle)
                implements InventoryService {
            @Override
            public Promise<Integer> checkStock(String productId) {
                return checkStockHandle.invoke(productId);
            }
        }

        return Promise.all(
                ctx.invoker().methodHandle("org.example:inventory:1.0.0", "checkStock",
                                            new TypeToken<String>() {},
                                            new TypeToken<Integer>() {}).async())
            .map((inventory_checkStock) -> {
                // Plain interface dep - call factory directly
                var validator = OrderValidator.orderValidator();
                // Slice dep - instantiate proxy from method handle
                var inventory = new inventoryService(inventory_checkStock);
                return aspect.apply(
                    OrderService.orderService(validator, inventory));
            });
    }

    public static Promise<Slice> orderServiceSlice(
            Aspect<OrderService> aspect,
            SliceCreationContext ctx) {
        record orderServiceSlice(OrderService delegate) implements Slice {
            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of(
                    new SliceMethod<>(
                        MethodName.methodName("placeOrder").unwrap(),
                        delegate::placeOrder,
                        new TypeToken<OrderResult>() {},
                        new TypeToken<PlaceOrderRequest>() {}
                    )
                );
            }
        }

        return orderService(aspect, ctx)
                   .map(orderServiceSlice::new);
    }
}
```

**Key design decisions:**

1. **Local proxy records**: Slice dependency proxies are generated as local records with `MethodHandle<R, I>` fields inside the factory method, not as separate classes. This keeps the implementation encapsulated.

2. **Aspect support**: The `Aspect<T>` parameter allows runtime decoration (logging, metrics, etc.) without modifying slice code.

3. **SliceCreationContext**: The runtime provides a `SliceCreationContext` which bundles the `SliceInvokerFacade` (for cross-slice calls) and `ResourceProviderFacade` (for infrastructure resources). Proxy records obtain method handles via `ctx.invoker().methodHandle(...)`.

4. **TypeToken usage**: Preserves generic type information for serialization/deserialization at runtime.

5. **Async handle resolution**: Method handles are resolved asynchronously via `Promise.all(...)`, ensuring all dependencies are available before the slice is instantiated.

### Proxy Method Generation

Proxy methods always have exactly one parameter (slice contract requirement). Each method delegates to a `MethodHandle<R, I>` field:

```java
@Override
public Promise<Integer> checkStock(String productId) {
    return checkStockHandle.invoke(productId);
}
```

Method handles are obtained during factory initialization via:

```java
ctx.invoker().methodHandle("org.example:inventory:1.0.0", "checkStock",
                            new TypeToken<String>() {},
                            new TypeToken<Integer>() {}).async()
```

The `methodHandle` call includes:
- **Artifact**: Maven coordinates of the target slice
- **Method name**: String identifier for routing
- **Request type token**: `TypeToken<I>` for serialization
- **Response type token**: `TypeToken<R>` for deserialization

## Manifest Generation

### Slice Manifest (`META-INF/slice/{SliceName}.manifest`)

Properties file containing all metadata needed for packaging and deployment:

```properties
# Identification
slice.name=OrderService
slice.artifactSuffix=order-service
slice.package=org.example.order

# Classes for API JAR
api.classes=org.example.order.api.OrderService

# Classes for Impl JAR
impl.classes=org.example.order.OrderService,\
             org.example.order.OrderServiceFactory,\
             org.example.order.OrderServiceFactory$orderServiceSlice,\
             org.example.order.OrderServiceFactory$inventoryService

# Request/Response types (for Impl JAR)
request.classes=org.example.order.PlaceOrderRequest
response.classes=org.example.order.OrderResult

# Artifact coordinates
base.artifact=org.example:commerce
api.artifactId=commerce-order-service-api
impl.artifactId=commerce-order-service

# Dependencies for blueprint generation
dependencies.count=2
dependency.0.interface=org.example.order.validation.OrderValidator
dependency.0.external=false
dependency.0.artifact=
dependency.0.version=
dependency.1.interface=org.example.inventory.InventoryService
dependency.1.external=true
dependency.1.artifact=org.example:inventory
dependency.1.version=1.0.0

# Metadata
generated.timestamp=2024-01-15T10:30:00Z
processor.version=0.4.8
```

### API Manifest (`META-INF/slice-api.properties`)

Maps artifact coordinates to the API interface:

```properties
api.artifact=org.example:commerce:api
slice.artifact=org.example:commerce
api.interface=org.example.order.api.OrderService
impl.interface=org.example.order.OrderService
```

## Multi-Artifact Packaging

A single source module with multiple `@Slice` interfaces produces separate Maven artifacts:

```mermaid
flowchart TD
    M["commerce module"] --> A["OrderService"]
    M --> B["PaymentService"]
    A --> A1["commerce-order-service-api.jar"]
    A --> A2["commerce-order-service.jar"]
    B --> B1["commerce-payment-service-api.jar"]
    B --> B2["commerce-payment-service.jar"]
```

### Maven Plugin Goals

| Goal | Phase | Description |
|------|-------|-------------|
| `jbct:package-slices` | package | Creates separate JARs from manifests |
| `jbct:install-slices` | install | Installs with distinct artifactIds |
| `jbct:deploy-slices` | deploy | Deploys to remote repository |

### JAR Contents

**API JAR** (`commerce-order-service-api-1.0.0.jar`):
```
org/example/order/api/OrderService.class
META-INF/MANIFEST.MF
```

**Impl JAR** (`commerce-order-service-1.0.0.jar`):
```
org/example/order/OrderService.class
org/example/order/OrderServiceImpl.class
org/example/order/OrderServiceFactory.class
org/example/order/OrderServiceFactory$orderServiceSlice.class
org/example/order/OrderServiceFactory$inventoryService.class
org/example/order/PlaceOrderRequest.class
org/example/order/OrderResult.class
META-INF/slice/OrderService.manifest
META-INF/MANIFEST.MF
```

## Blueprint Generation

The `jbct:generate-blueprint` goal creates a deployment descriptor:

```mermaid
flowchart LR
    A["Read manifests"] --> B["Build dependency graph"]
    B --> C["Resolve external deps from JARs"]
    C --> D["Match internal inter-slice deps"]
    D --> E["Topological sort"]
    E --> F["Generate blueprint.toml"]
```

### Dependency Resolution

1. **Local slices**: Read from `target/classes/META-INF/slice/*.manifest`
2. **External dependencies**: Read from dependency JARs in Maven classpath
3. **Internal inter-slice**: Match interface names to other local manifests

### Topological Ordering

Dependencies are listed before dependents:

```toml
# Generated by jbct:generate-blueprint
id = "org.example:commerce:1.0.0"

[[slices]]
artifact = "org.example:inventory-service:1.0.0"
instances = 1
# transitive dependency

[[slices]]
artifact = "org.example:commerce-payment-service:1.0.0"
instances = 1

[[slices]]
artifact = "org.example:commerce-order-service:1.0.0"
instances = 1
```

## Version Resolution

External dependency versions are resolved from `slice-deps.properties`:

```properties
# Generated by jbct:collect-slice-deps
org.example\:inventory\:api=1.0.0
org.example\:pricing\:api=2.1.0
```

This file is created by the `jbct:collect-slice-deps` goal, which scans Maven dependencies with `scope=provided` and `classifier=api`.

## Extension Points

### Custom Aspects

Implement `Aspect<T>` to decorate slice instances:

```java
public class LoggingAspect<T> implements Aspect<T> {
    @Override
    public T apply(T instance) {
        // Return proxy that logs method calls
        return createLoggingProxy(instance);
    }
}
```

### SliceCreationContext

The runtime provides a `SliceCreationContext` to each factory, bundling both the invoker (for cross-slice calls) and the resource provider (for infrastructure resources):

```java
public interface SliceCreationContext {
    SliceInvokerFacade invoker();
    ResourceProviderFacade resources();
}
```

### SliceInvokerFacade

The invoker routes inter-slice calls and provides method handles:

```java
public interface SliceInvokerFacade {
    <R, I> MethodHandle<R, I> methodHandle(String sliceArtifact,
                                            String methodName,
                                            TypeToken<I> requestType,
                                            TypeToken<R> responseType);
}
```

### ResourceProviderFacade

The resource provider supplies infrastructure dependencies (databases, caches, etc.):

```java
public interface ResourceProviderFacade {
    <T> T provide(Class<T> type, String qualifier);
}
```

## Design Trade-offs

| Decision | Trade-off | Rationale |
|----------|-----------|-----------|
| Single-param methods | Less flexible API | Enables uniform request/response serialization |
| Local proxy records | Larger generated code | Keeps proxies encapsulated, no class pollution |
| Separate API/Impl JARs | More artifacts to manage | Clean dependency boundaries |
| Properties-based manifests | Less structured than JSON/YAML | Simple to parse, no dependencies |
| Compile-time wiring | No runtime discovery | Fail-fast, explicit dependencies |
| Resource provisioning via @ResourceQualifier | Requires annotation definitions | Type-safe, compile-time verified, auto-cached |
