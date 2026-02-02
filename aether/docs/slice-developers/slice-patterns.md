# Slice Developer Guide

## Introduction

This guide explains how to develop Slices for the Aether runtime. A Slice is a deployable unit of business logic that
can be loaded, activated, scaled, and managed by the Aether cluster.

## Slice Interface

The `Slice` interface is the contract every deployable component must implement:

```java
public interface Slice {
    default Promise<Unit> start();
    default Promise<Unit> stop();
    List<SliceMethod<?, ?>> methods();
    default List<SliceRoute> routes();
}
```

### Lifecycle Methods

#### start()

**Purpose**: Initialize resources needed by the slice.

**When called**: Exactly once after the slice is loaded and before any method invocations.

**Default implementation**: Returns successfully resolved Promise.

**Custom implementation example**:

```java
@Override
public Promise<Unit> start() {
    return Promise.lift(
        Causes::fromThrowable,
        () -> {
            // Initialize database connection pool
            this.dataSource = createDataSource();

            // Initialize caches
            this.cache = createCache();

            return Unit.unit();
        }
    );
}
```

**Timeout**: Configurable via `SliceActionConfig.startStopTimeout` (default: 5 seconds).

**Failure handling**:

- If `start()` fails (Promise resolves to failure), the slice moves to FAILED state
- The runtime does NOT call `stop()` - your `start()` method must cleanup partial initialization
- No retry - slice must be unloaded and loaded again

#### stop()

**Purpose**: Cleanup resources allocated by the slice.

**When called**: Exactly once during graceful shutdown, after all pending method invocations complete.

**Default implementation**: Returns successfully resolved Promise.

**Custom implementation example**:

```java
@Override
public Promise<Unit> stop() {
    return Promise.lift(
        Causes::fromThrowable,
        () -> {
            // Close database connections
            if (this.dataSource != null) {
                this.dataSource.close();
            }

            // Clear caches
            if (this.cache != null) {
                this.cache.invalidateAll();
            }

            return Unit.unit();
        }
    );
}
```

**Timeout**: Configurable via `SliceActionConfig.startStopTimeout` (default: 5 seconds).

**Failure handling**:

- `stop()` failures are logged but don't prevent slice unloading
- Slice is removed from cluster regardless of stop() outcome

**Important**: Use `stop()` for cleanup, not the constructor or finalizers.

### Method Registry

#### methods()

**Purpose**: Declare all callable methods exposed by this slice.

**Returns**: List of `SliceMethod<?, ?>` descriptors.

**Example**:

```java
@Override
public List<SliceMethod<?, ?>> methods() {
    return List.of(
        new SliceMethod<>(
            MethodName.methodName("processString").unsafe(),
            this::processString,
            TypeToken.of(ProcessedString.class),
            TypeToken.of(InputString.class)
        ),
        new SliceMethod<>(
            MethodName.methodName("validateEmail").unsafe(),
            this::validateEmail,
            TypeToken.of(ValidationResult.class),
            TypeToken.of(EmailAddress.class)
        )
    );
}

private Promise<ProcessedString> processString(InputString input) {
    // Implementation
}

private Promise<ValidationResult> validateEmail(EmailAddress email) {
    // Implementation
}
```

### HTTP Routes

#### routes()

**Purpose**: Declare HTTP routes this slice handles. Routes are automatically registered when the slice activates and
unregistered when it deactivates.

**Returns**: List of `SliceRoute` descriptors.

**Default implementation**: Returns empty list (no HTTP routes).

**Example**:

```java
@Override
public List<SliceRoute> routes() {
    return List.of(
        SliceRoute.get("/api/users/{id}", "getUser")
            .withPathVar("id")
            .build(),
        SliceRoute.post("/api/users", "createUser")
            .withBody()
            .build(),
        SliceRoute.put("/api/users/{id}", "updateUser")
            .withPathVar("id")
            .withBody()
            .build(),
        SliceRoute.delete("/api/users/{id}", "deleteUser")
            .withPathVar("id")
            .build()
    );
}
```

#### SliceRoute Builder API

Create routes using the static factory methods:

```java
SliceRoute.get(pathPattern, methodName)    // HTTP GET
SliceRoute.post(pathPattern, methodName)   // HTTP POST
SliceRoute.put(pathPattern, methodName)    // HTTP PUT
SliceRoute.delete(pathPattern, methodName) // HTTP DELETE
SliceRoute.patch(pathPattern, methodName)  // HTTP PATCH
```

Add bindings to specify how request data maps to method parameters:

```java
.withBody()                    // Request body → parameter
.withBody("customParamName")   // Request body → named parameter
.withPathVar("id")             // Path variable → parameter
.withQueryVar("page")          // Query parameter → parameter
.withHeader("userId", "X-User-Id")  // Header → parameter
```

Finalize with `.build()`:

```java
SliceRoute.post("/api/orders", "placeOrder")
    .withBody()
    .withHeader("requestId", "X-Request-Id")
    .build()
```

#### Path Patterns

Path patterns support variables using `{name}` syntax:

- `/api/users` - literal path
- `/api/users/{id}` - path with variable
- `/api/users/{userId}/orders/{orderId}` - multiple variables

#### Route Registration Lifecycle

1. **Activation**: When slice becomes ACTIVE, runtime registers all routes
2. **Request handling**: HTTP requests matching routes invoke the corresponding method
3. **Deactivation**: When last instance of slice deactivates, routes are unregistered

**Note**: Multiple instances of the same slice share routes. Routes are only unregistered when the last instance
deactivates.

## Lifecycle Contract

### State Transitions

From the Slice's perspective:

```
[Constructed] → start() called → [Serving] → stop() called → [Terminated]
```

### Guarantees

1. **start() called exactly once** before any method invocation
2. **Methods can be invoked concurrently** after start() succeeds
3. **stop() called exactly once** after start() succeeds and all methods complete
4. **No method calls during start() or stop()**
5. **Timeouts enforced** on both start() and stop() (default 5 seconds)

### Failure Scenarios

**start() fails:**

- Slice moves to FAILED state
- stop() is NOT called
- Your start() must cleanup partial initialization
- Slice must be unloaded and reloaded to retry

**stop() fails:**

- Failure is logged
- Slice is unloaded anyway
- Resources may leak (design stop() to be best-effort)

**Method invocation fails:**

- Failure returned to caller via Promise
- Slice remains ACTIVE
- Other concurrent calls unaffected

### Shutdown Sequence

When slice receives shutdown request:

1. **Stop accepting new requests immediately**
2. **Wait for all in-flight method calls to complete** (with timeout)
3. **Call stop()** (with timeout)
4. **Unload slice** (regardless of stop() outcome)

## Thread Safety Requirements

### All methods must be thread-safe

After `start()` succeeds, multiple methods can be invoked concurrently. Your implementation must handle:

- Concurrent reads
- Concurrent writes
- Concurrent read-write access

**Thread-safe example**:

```java
public record UserService(ConcurrentHashMap<UserId, User> cache) implements Slice {

    private Promise<User> getUser(UserId id) {
        return Promise.lift(
            Causes::fromThrowable,
            () -> cache.computeIfAbsent(id, this::loadUser)
        );
    }

    private User loadUser(UserId id) {
        // Load from database
    }
}
```

### Immutability Preferred

Best practice: Use immutable data structures and functional programming patterns:

```java
public record OrderProcessor(OrderRepository repository) implements Slice {

    private Promise<OrderConfirmation> processOrder(OrderRequest request) {
        return validateOrder(request)
            .flatMap(repository::save)
            .map(this::createConfirmation);
    }

    private Result<ValidatedOrder> validateOrder(OrderRequest request) {
        // Pure function, no shared state
    }
}
```

## Resource Management

### Use start() and stop(), Not Constructors

**Wrong**:

```java
public record MySlice() implements Slice {
    private final DataSource dataSource = createDataSource(); // ❌ BAD

    public MySlice() {
        // ❌ Don't initialize resources in constructor
        initializeCache();
    }
}
```

**Correct**:

```java
public record MySlice(
    AtomicReference<DataSource> dataSource,
    AtomicReference<Cache> cache
) implements Slice {

    @Override
    public Promise<Unit> start() {
        return Promise.lift(
            Causes::fromThrowable,
            () -> {
                dataSource.set(createDataSource());
                cache.set(createCache());
                return Unit.unit();
            }
        );
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(
            Causes::fromThrowable,
            () -> {
                var ds = dataSource.getAndSet(null);
                if (ds != null) ds.close();

                var c = cache.getAndSet(null);
                if (c != null) c.invalidateAll();

                return Unit.unit();
            }
        );
    }
}
```

### Why?

1. **Constructors run during class loading** - before cluster is ready
2. **start() runs when slice is activated** - cluster is ready, configuration available
3. **stop() ensures cleanup** - constructors have no cleanup hook
4. **Timeout protection** - start()/stop() have timeouts, constructors don't

## Serialization

### Automatic Serialization

The runtime automatically serializes/deserializes method parameters and return values using the configured
`SerializerFactoryProvider`.

**You don't need to handle serialization** - just declare your types:

```java
public record ProcessStringMethod() implements SliceMethod<ProcessedString, InputString> {
    @Override
    public Promise<ProcessedString> apply(InputString input) {
        // Work with typed objects - runtime handles serialization
        return Promise.success(new ProcessedString(input.value().toUpperCase()));
    }
}
```

### Type Requirements

All parameter and return types must be:

- Serializable by Fury or Kryo (default providers)
- Registered with the serializer (if using custom types)
- Immutable (strongly recommended)

### Custom Serialization

If you need custom serialization (e.g., Protocol Buffers, JSON):

1. Implement `SerializerFactoryProvider`
2. Configure via `SliceActionConfig.serializerProvider`
3. Runtime calls your provider during slice loading

## Dependencies

### No Dependency Discovery Yet

**Current limitation**: Slices cannot discover or call other slices during `start()`.

**Workaround**: Initialize lazily on first method call:

```java
private Promise<Result> callOtherSlice(Input input) {
    return discoverOtherSlice()  // Lazy discovery
        .flatMap(otherSlice -> otherSlice.call(input));
}
```

**Future**: Dependency injection and discovery mechanism planned.

## Best Practices

### 1. Keep start() and stop() Fast

Target < 1 second for lifecycle methods:

```java
@Override
public Promise<Unit> start() {
    // ✅ GOOD: Quick initialization
    return Promise.success(Unit.unit());
}

@Override
public Promise<Unit> start() {
    // ❌ BAD: Slow network operation
    return fetchRemoteConfiguration()
        .flatMap(this::initializeWithConfig);
}
```

### 2. Use Default Implementations When Possible

Most slices don't need custom start()/stop():

```java
public record SimpleSlice() implements Slice {
    // ✅ Uses default start()/stop() - no resources to manage

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(/* ... */);
    }
}
```

### 3. Handle Failures Gracefully

```java
private Promise<User> getUser(UserId id) {
    return repository.find(id)
        .flatMap(option -> option
            .toResult(USER_NOT_FOUND.apply(id))
            .async()
        );
}

private static final Fn1<Cause, UserId> USER_NOT_FOUND =
    Causes.forOneValue("User not found: {}");
```

### 4. Use Records for Immutability

```java
public record OrderProcessorSlice(
    OrderValidator validator,
    OrderRepository repository,
    NotificationService notifications
) implements Slice {
    // Immutable by design
}
```

### 5. Follow JBCT Patterns

Use the `/jbct` skill for detailed guidance on:

- Four return kinds (T, Option<T>, Result<T>, Promise<T>)
- Parse, don't validate
- No business exceptions
- Single pattern per function

## Example: Complete Slice

```java
public record UserService(
    AtomicReference<UserRepository> repository
) implements Slice {

    @Override
    public Promise<Unit> start() {
        return Promise.lift(
            Causes::fromThrowable,
            () -> {
                repository.set(UserRepository.create());
                return Unit.unit();
            }
        );
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(
            Causes::fromThrowable,
            () -> {
                var repo = repository.getAndSet(null);
                if (repo != null) {
                    repo.close();
                }
                return Unit.unit();
            }
        );
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("getUser").unsafe(),
                this::getUser,
                TypeToken.of(User.class),
                TypeToken.of(UserId.class)
            ),
            new SliceMethod<>(
                MethodName.methodName("createUser").unsafe(),
                this::createUser,
                TypeToken.of(UserId.class),
                TypeToken.of(CreateUserRequest.class)
            )
        );
    }

    private Promise<User> getUser(UserId id) {
        return Promise.lift(
            Causes::fromThrowable,
            () -> repository.get().findById(id)
        ).flatMap(option -> option
            .toResult(USER_NOT_FOUND.apply(id))
            .async()
        );
    }

    private Promise<UserId> createUser(CreateUserRequest request) {
        return validateRequest(request)
            .async()
            .flatMap(validated ->
                Promise.lift(
                    Causes::fromThrowable,
                    () -> repository.get().save(validated.toUser())
                )
            );
    }

    private Result<ValidatedRequest> validateRequest(CreateUserRequest request) {
        // Validation logic
    }

    private static final Fn1<Cause, UserId> USER_NOT_FOUND =
        Causes.forValue("User not found: {0}");
}
```

## Troubleshooting

### start() timeout

**Symptom**: Slice fails to activate with timeout error.

**Solutions**:

- Move slow initialization to lazy loading
- Increase `SliceActionConfig.startStopTimeout`
- Check for blocking operations in start()

### Concurrent modification errors

**Symptom**: Race conditions, inconsistent state.

**Solutions**:

- Use concurrent collections (`ConcurrentHashMap`, `CopyOnWriteArrayList`)
- Make methods stateless
- Use immutable data structures

### Memory leaks after stop()

**Symptom**: Resources not released after slice unload.

**Solutions**:

- Implement stop() properly
- Use try-with-resources in stop()
- Set references to null in stop()

## See Also

- [Slice Lifecycle](../contributors/slice-lifecycle.md) - Runtime perspective
- [Architecture Overview](../contributors/architecture.md) - System design
