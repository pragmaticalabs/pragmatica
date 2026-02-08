---
RFC: 0001
Title: Core Slice Contract
Status: Draft
Author: Sergiy Yevtushenko
Created: 2026-01-15
Updated: 2026-01-28
Affects: [jbct-cli, aether]
---

## Summary

Defines the core contract between jbct-cli slice-processor (code generator) and aether runtime (slice consumer). Covers factory method conventions, aspect-based decoration, SliceMethod metadata, and manifest format.

## Motivation

The jbct-cli slice-processor generates code that aether runtime loads and executes. Without explicit contracts, changes on either side can silently break the other. This RFC establishes the foundational protocol that both projects must adhere to.

## Design

### Boundaries

- **jbct-cli**: Generates factory classes, SliceMethod records, and manifests from `@Slice` interfaces
- **aether**: Discovers, loads, and invokes slices via reflection and the generated artifacts

### 1. Factory Method Contract

#### Naming Convention

Factory class: `{SliceName}Factory`
Factory method: `{sliceName}(...)` (lowercase-first)

```java
// For interface MySlice
public final class MySliceFactory {
    public static Promise<MySlice> mySlice(Aspect<MySlice> aspect, SliceInvokerFacade invoker) {
        // ...
    }
}
```

#### Method Signature

```java
public static Promise<{SliceName}> {sliceName}(
    Aspect<{SliceName}> aspect,      // Required, first parameter
    SliceInvokerFacade invoker       // Required, second parameter
)
```

**Rules:**
- Return type: `Promise<{SliceName}>` (never raw type, never `Result<Promise<...>>`)
- First parameter: `Aspect<{SliceName}>` for decoration (use `Aspect.identity()` for no-op)
- Second parameter: `SliceInvokerFacade` for all dependency invocations (see [RFC-0002](RFC-0002-dependency-protocol.md))
- All dependencies (slice dependencies) are resolved via `SliceInvokerFacade` proxies - no direct parameters

#### Aether Discovery

Aether uses reflection to find factory methods:

```java
// SliceFactory.java discovery logic
Method factoryMethod = Arrays.stream(factoryClass.getMethods())
    .filter(m -> Modifier.isStatic(m.getModifiers()))
    .filter(m -> m.getName().equals(lowercaseFirst(sliceName)))
    .filter(m -> m.getReturnType().equals(Promise.class))
    .findFirst()
    .orElseThrow();
```

### 2. Infrastructure Dependencies

Infrastructure dependencies (caches, databases, metrics) are accessed via `InfraStore` pattern, NOT passed as factory parameters.

**Classification:** Dependencies with `artifactId` starting with `infra-` (e.g., `infra-cache`, `infra-database`)

**Access pattern:**
```java
public static Promise<MySlice> mySlice(Aspect<MySlice> aspect, SliceInvokerFacade invoker) {
    // Infrastructure accessed via InfraStore (shared singleton instances)
    var cache = InfraStore.instance()
        .get("org.pragmatica-lite.aether:infra-cache", CacheService.class)
        .orElseGet(NoOpCache::new);

    var impl = new MySliceImpl(cache);
    return Promise.success(aspect.apply(impl));
}
```

**Key characteristics:**
- Shared instances across all slices (singleton per artifact)
- Loaded in SharedLibraryClassLoader (see [RFC-0007](RFC-0007-dependency-sections.md))
- NOT proxied via `SliceInvokerFacade`
- Listed in `[infra]` section of dependency file, NOT in slice manifest

**Contrast with slice dependencies:**
- Slice dependencies: Proxied via `SliceInvokerFacade`, isolated per slice
- Infra dependencies: Direct access via `InfraStore`, shared instances

### 3. Aspect Pattern

#### Interface

```java
public interface Aspect<T> {
    T apply(T instance);

    static <T> Aspect<T> identity() {
        return instance -> instance;
    }
}
```

#### Application Timing

Aspect is applied as the final step before returning from factory:

```java
// Generated factory method
public static Promise<MySlice> mySlice(Aspect<MySlice> aspect, SliceInvokerFacade invoker) {
    var impl = new MySliceImpl(/* dependencies */);
    return Promise.success(aspect.apply(impl));  // Aspect applied here
}
```

**Guarantees:**
- Instance is fully constructed (all dependencies injected) before aspect application
- Identity aspect has zero runtime overhead
- Multiple aspects compose via caller: `aspect1.andThen(aspect2)`

### 4. SliceMethod Metadata

#### Slice Interface

Every slice implements:

```java
public interface Slice {
    Promise<Unit> start();
    Promise<Unit> stop();
    List<SliceMethod<?, ?>> methods();
}
```

#### SliceMethod Record

```java
public record SliceMethod<I, O>(
    MethodName name,                    // Validated method name
    Function<I, Promise<O>> handler,    // Method reference
    TypeToken<O> responseType,          // For serialization
    TypeToken<I> requestType            // For deserialization
) {}
```

#### Method Name Rules

Pattern: `^[a-z][a-zA-Z0-9]+$`

- Must start with lowercase letter
- Alphanumeric only (no underscores, hyphens)
- Must be unique within slice

#### Generated methods() Implementation

```java
@Override
public List<SliceMethod<?, ?>> methods() {
    return List.of(
        new SliceMethod<>(
            MethodName.methodName("processData").unwrap(),
            this::processData,
            new TypeToken<ProcessResponse>() {},
            new TypeToken<ProcessRequest>() {}
        )
    );
}
```

#### TypeToken Preservation

TypeTokens capture generic type information at generation time, enabling runtime serialization across classloader boundaries:

```java
// For method: Promise<List<User>> getUsers(GetUsersRequest request)
new SliceMethod<>(
    MethodName.methodName("getUsers").unwrap(),
    this::getUsers,
    new TypeToken<List<User>>() {},  // Preserves List<User>, not raw List
    new TypeToken<GetUsersRequest>() {}
)
```

### 5. Slice Method Signature Rules

All methods in a `@Slice` interface must follow:

```java
Promise<ResponseType> methodName(RequestType request);
```

**Rules:**
- Return type: `Promise<T>` (async, fallible)
- Exactly one parameter (request object)
- No overloads (method name uniqueness)
- No checked exceptions in signature

**Forbidden patterns:**
- `Result<T>` return (use `Promise<T>`, failures flow through Promise)
- `Promise<Result<T>>` return (nested error channels)
- Multiple parameters (wrap in request record)
- Void return (use `Promise<Unit>`)

### 6. Slice Manifest Format

Location: `META-INF/slice/{SliceName}.manifest`

Each `@Slice` interface gets its own manifest file, enabling multiple slices per module.

Example for `OrderService`:
```properties
# Identity
slice.name=OrderService
slice.artifactSuffix=order-service
slice.package=org.example.order

# Classes in Slice JAR
slice.interface=org.example.order.OrderService
impl.classes=org.example.order.OrderService,\
             org.example.order.OrderServiceImpl,\
             org.example.order.OrderServiceFactory

# Request/Response types
request.classes=org.example.order.PlaceOrderRequest
response.classes=org.example.order.OrderResult

# Artifact coordinates
base.artifact=org.example:commerce
slice.artifactId=commerce-order-service

# Dependencies (slice dependencies via invoker proxy)
dependencies.count=2
dependency.0.interface=org.example.inventory.InventoryService
dependency.0.artifact=org.example:inventory-service
dependency.0.version=1.0.0
dependency.1.interface=org.example.payment.PaymentService
dependency.1.artifact=org.example:payment-service
dependency.1.version=1.2.0

# Metadata
generated.timestamp=2026-01-15T12:00:00Z
processor.version=0.5.0
```

**Key Fields:**
- `slice.interface`: Fully qualified name of the `@Slice` interface (public API)
- `slice.artifactId`: Final artifact ID (e.g., `commerce-order-service`)
- `dependencies.count`: Number of slice dependencies (resolved via `SliceInvokerFacade`)
- `dependency.N.*`: Each slice dependency with interface, artifact, version

**Infrastructure dependencies** (caches, databases) are NOT listed here - they are accessed via `InfraStore.instance().get()` at runtime (see [RFC-0007](RFC-0007-dependency-sections.md)).

**Usage by aether:**
- Scans `META-INF/slice/*.manifest` to discover all slices in JAR
- Reads `slice.interface` for slice registration
- Reads `dependency.*` properties for dependency ordering
- Supports multiple slices per JAR (each gets unique manifest file)

### Contracts Summary

| Component | jbct-cli Generates | aether Expects |
|-----------|-------------------|----------------|
| Factory class | `{SliceName}Factory` | Class name pattern match |
| Factory method | `{sliceName}(Aspect, SliceInvokerFacade)` | Reflection by name + signature |
| Return type | `Promise<{SliceName}>` | Unwrapped via `.await()` or composed |
| Aspect param | First parameter, `Aspect<T>` | Passed by runtime, `identity()` default |
| Invoker param | Second parameter, `SliceInvokerFacade` | Slice dependencies resolved via proxies |
| Infra dependencies | Accessed via `InfraStore.instance().get()` | Shared singleton instances |
| SliceMethod | Record with handler + TypeTokens | `methods()` list lookup by name |
| Manifest | `META-INF/slice/{Name}.manifest` | Scans directory, one file per slice |

## Examples

### Complete @Slice Interface

```java
@Slice
public interface UserService {
    Promise<UserResponse> getUser(GetUserRequest request);
    Promise<CreateUserResponse> createUser(CreateUserRequest request);

    static UserService userService(Aspect<UserService> aspect, SliceInvokerFacade invoker) {
        return UserServiceFactory.userService(aspect, invoker);
    }
}
```

### Generated Factory

```java
public final class UserServiceFactory {
    private UserServiceFactory() {}

    public static Promise<UserService> userService(
            Aspect<UserService> aspect,
            SliceInvokerFacade invoker) {
        var impl = new UserServiceImpl();
        return Promise.success(aspect.apply(impl));
    }
}
```

### Generated Slice Bridge

```java
final class UserServiceSliceBridge implements Slice {
    private final UserService delegate;

    UserServiceSliceBridge(UserService delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("getUser").unwrap(),
                delegate::getUser,
                new TypeToken<UserResponse>() {},
                new TypeToken<GetUserRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("createUser").unwrap(),
                delegate::createUser,
                new TypeToken<CreateUserResponse>() {},
                new TypeToken<CreateUserRequest>() {}
            )
        );
    }

    @Override
    public Promise<Unit> start() { return Promise.unitPromise(); }

    @Override
    public Promise<Unit> stop() { return Promise.unitPromise(); }
}
```

## Breaking Changes

Changes requiring version bump in both projects:

1. Factory method signature changes (parameter order, types)
2. SliceMethod record structure changes
3. Manifest property additions/removals
4. MethodName validation pattern changes
5. Aspect interface changes

## References

- [RFC-0002: Dependency Protocol](RFC-0002-dependency-protocol.md) - External dependency handling
- [RFC-0003: HTTP Layer](RFC-0003-http-layer.md) - Optional HTTP route generation
