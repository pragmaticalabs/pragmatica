# Aspects Pattern

**Purpose**: Apply cross-cutting concerns (retry, timeout, logging, metrics) without mixing them into business logic.

## Definition

Aspects wrap core operations with additional behavior while keeping business logic unchanged.

```java
return withRetry(retryPolicy,
    withTimeout(timeout,
        withMetrics(metrics, coreOperation)
    )
);
```

## Common Aspects

### Retry

```java
public <T> Promise<T> withRetry(
    RetryPolicy policy,
    Promise<T> operation
) {
    return operation.recover(cause -> {
        if (policy.shouldRetry(cause)) {
            return Promise.promise(policy.delay())
                .flatMap(u -> withRetry(policy.next(), operation));
        }
        return cause.promise();
    });
}

// Usage
return withRetry(
    RetryPolicy.exponential(3, Duration.ofSeconds(1)),
    callExternalApi.apply(request)
);
```

### Timeout

```java
public <T> Promise<T> withTimeout(
    TimeSpan timeout,
    Promise<T> operation
) {
    Promise<T> timeoutPromise = Promise.promise(timeout)
        .flatMap(u -> TimeoutError.INSTANCE.promise());

    return Promise.any(operation, timeoutPromise);
}

// Usage
return withTimeout(
    TimeSpan.seconds(5),
    loadUser.apply(userId)
);
```

### Logging

```java
public <T> Promise<T> withLogging(
    String operationName,
    Promise<T> operation
) {
    log.info("Starting: {}", operationName);
    long start = System.currentTimeMillis();

    return operation
        .onSuccess(result -> {
            long duration = System.currentTimeMillis() - start;
            log.info("Completed: {} in {}ms", operationName, duration);
        })
        .onFailure(cause -> {
            long duration = System.currentTimeMillis() - start;
            log.error("Failed: {} after {}ms - {}", operationName, duration, cause.message());
        });
}

// Usage
return withLogging(
    "LoadUser",
    loadUser.apply(userId)
);
```

### Metrics

```java
public <T> Promise<T> withMetrics(
    String metricName,
    Promise<T> operation
) {
    Timer.Sample sample = Timer.start(meterRegistry);

    return operation
        .onResult(result -> {
            sample.stop(Timer.builder(metricName)
                .tag("status", result.isSuccess() ? "success" : "failure")
                .register(meterRegistry));
        });
}

// Usage
return withMetrics(
    "user.load",
    loadUser.apply(userId)
);
```

### Circuit Breaker

```java
public <T> Promise<T> withCircuitBreaker(
    CircuitBreaker breaker,
    Promise<T> operation
) {
    if (breaker.isOpen()) {
        return CircuitBreakerError.Open.INSTANCE.promise();
    }

    return operation
        .onSuccess(r -> breaker.recordSuccess())
        .onFailure(c -> {
            breaker.recordFailure();
            if (breaker.shouldOpen()) {
                breaker.open();
            }
        });
}
```

## Combining Aspects

### Composition Order Matters

```java
// Retry outer → Timeout inner
return withRetry(retryPolicy,
    withTimeout(timeout,
        coreOperation
    )
);
// Each retry attempt has its own timeout

// Timeout outer → Retry inner
return withTimeout(timeout,
    withRetry(retryPolicy,
        coreOperation
    )
);
// Total timeout includes all retry attempts
```

### Full Stack

```java
public Promise<User> loadUserWithAspects(UserId id) {
    return withMetrics("user.load",
        withLogging("LoadUser",
            withRetry(RetryPolicy.fixed(3, Duration.ofSeconds(1)),
                withTimeout(TimeSpan.seconds(5),
                    withCircuitBreaker(userCircuitBreaker,
                        loadUser.apply(id)
                    )
                )
            )
        )
    );
}
```

## Creating Reusable Aspect Combinators

### Generic Wrapper

```java
@FunctionalInterface
public interface PromiseAspect<T> {
    Promise<T> apply(Promise<T> operation);

    // Compose aspects
    default PromiseAspect<T> andThen(PromiseAspect<T> after) {
        return operation -> after.apply(this.apply(operation));
    }
}

// Usage
PromiseAspect<User> userAspects =
    retry(retryPolicy)
        .andThen(timeout(TimeSpan.seconds(5)))
        .andThen(logging("LoadUser"))
        .andThen(metrics("user.load"));

return userAspects.apply(loadUser.apply(userId));
```

### Aspect Factory

```java
public class Aspects {
    public static <T> PromiseAspect<T> retry(RetryPolicy policy) {
        return operation -> withRetry(policy, operation);
    }

    public static <T> PromiseAspect<T> timeout(TimeSpan duration) {
        return operation -> withTimeout(duration, operation);
    }

    public static <T> PromiseAspect<T> logging(String name) {
        return operation -> withLogging(name, operation);
    }

    public static <T> PromiseAspect<T> metrics(String metric) {
        return operation -> withMetrics(metric, operation);
    }
}
```

## Configuration-Driven Aspects

### Aspect Configuration

```java
public record AspectConfig(
    boolean retryEnabled,
    RetryPolicy retryPolicy,
    boolean timeoutEnabled,
    TimeSpan timeout,
    boolean metricsEnabled,
    String metricName
) {
    public <T> Promise<T> apply(Promise<T> operation) {
        Promise<T> result = operation;

        if (timeoutEnabled) {
            result = withTimeout(timeout, result);
        }
        if (retryEnabled) {
            result = withRetry(retryPolicy, result);
        }
        if (metricsEnabled) {
            result = withMetrics(metricName, result);
        }

        return result;
    }
}
```

## Testing with Aspects

### Test Without Aspects

```java
@Test
void loadUser_succeeds_whenUserExists() {
    // Test core logic without retry/timeout
    var adapter = new UserAdapter(jdbcTemplate);

    adapter.loadUser(userId)
           .await()
           .onFailure(Assertions::fail)
           .onSuccess(user -> assertEquals(expectedEmail, user.email()));
}
```

### Test Aspect Behavior

```java
@Test
void withRetry_retriesOnFailure() {
    var failTwice = new AtomicInteger(0);
    Promise<User> operation = Promise.promise(() -> {
        if (failTwice.incrementAndGet() < 3) {
            return TransientError.INSTANCE.result();
        }
        return Result.success(testUser);
    });

    withRetry(RetryPolicy.fixed(3, Duration.ofMillis(10)), operation)
        .await()
        .onFailure(Assertions::fail)
        .onSuccess(user -> {
            assertEquals(testUser, user);
            assertEquals(3, failTwice.get());
        });
}
```

### Mock Aspects for Integration Tests

```java
@Test
void useCase_works_withAspects() {
    // Use no-op aspects for faster tests
    PromiseAspect<User> noRetry = operation -> operation;

    var useCase = UseCase.create(
        noRetry.apply(loadUser.apply(userId))
    );

    useCase.execute(request).await();
}
```

## Anti-Patterns

### ❌ Mixing Aspects with Business Logic

```java
// DON'T
public Promise<User> loadUser(UserId id) {
    log.info("Loading user {}", id);  // Aspect concern!
    return Promise.lift(() -> {
        metrics.increment("user.load");  // Aspect concern!
        return repository.findById(id);
    });
}

// DO - Separate aspects
public Promise<User> loadUser(UserId id) {
    return Promise.lift(
        DatabaseError::cause,
        () -> repository.findById(id)
    );
}

// Apply aspects at composition time
return withMetrics("user.load",
    withLogging("LoadUser",
        loadUser.apply(userId)
    )
);
```

### ❌ Hardcoded Aspect Configuration

```java
// DON'T - Hardcoded in business logic
public Promise<User> loadUser(UserId id) {
    return withRetry(RetryPolicy.fixed(3, Duration.ofSeconds(1)),
        actualLoad(id)
    );
}

// DO - Configure at assembly
public interface LoadUser {
    Promise<User> apply(UserId id);

    static LoadUser loadUser(UserRepository repo, AspectConfig config) {
        return id -> {
            Promise<User> operation = Promise.lift(
                DatabaseError::cause,
                () -> repo.findById(id)
            );
            return config.apply(operation);
        };
    }
}
```

### ❌ Aspect Logic in Multiple Places

```java
// DON'T - Duplicate retry logic
public Promise<User> loadUser(UserId id) {
    return operation.recover(cause -> {
        if (retries < 3) {
            return loadUser(id);  // Retry logic embedded
        }
        return cause.promise();
    });
}

// DO - Use reusable aspect
return withRetry(retryPolicy, loadUser.apply(id));
```

## When to Use Aspects

- Retry logic for transient failures
- Timeouts for external services
- Logging/metrics for observability
- Circuit breakers for fault tolerance
- Caching for performance
- Rate limiting for API calls

## Logging Philosophy

**Principle**: Logging belongs only at **leaves** and **external boundaries**. Composition code has zero logging.

### Rationale

- Leaves perform real work - they're the only places where operations can fail
- Boundaries are where we interact with external systems - points of uncertainty
- Everything else is composition - deterministic routing of values

### Implementation

Wrap leaf implementations in logging aspects at construction:

```java
public interface UserRepository {
    Promise<User> findById(UserId id);

    static UserRepository create(DataSource ds, Logger log) {
        var impl = new UserRepositoryImpl(ds);
        return withLogging(log, "UserRepository", impl);
    }
}

public static UserRepository withLogging(Logger log, String name, UserRepository impl) {
    return id -> {
        var correlationId = CorrelationContext.current();
        log.debug("[{}] {}.findById input: {}", correlationId, name, id);

        return impl.findById(id)
                   .onSuccess(user -> log.debug("[{}] {}.findById success", correlationId, name))
                   .onFailure(cause -> log.warn("[{}] {}.findById failure: {}", correlationId, name, cause.message()));
    };
}
```

### What Gets Logged

- Input parameters (sanitized)
- Outcome (success or failure cause)
- Correlation ID for request tracing

### Sensitive Data

Aspects must sanitize inputs:

```java
private String sanitize(Object input) {
    return switch (input) {
        case Password ignored -> "[REDACTED]";
        case Email email -> maskEmail(email);
        default -> String.valueOf(input);
    };
}
```

### Anti-Patterns

```java
// DON'T: Logging in composition code
return validateInput(request)
    .onSuccess(v -> log.info("Validated"))  // Noise
    .flatMap(this::fetchUser)
    .onSuccess(u -> log.info("Fetched"))    // Noise
    .flatMap(this::processOrder);

// DO: Wrap leaves at construction
static ProcessOrder processOrder(UserRepository users, Logger log) {
    return new ProcessOrderImpl(withLogging(log, "users", users));
}
```

## Related

- [leaf.md](leaf.md) - Core operations wrapped by aspects
- [sequencer.md](sequencer.md) - Applying aspects to chains
- [fork-join.md](fork-join.md) - Aspects on parallel operations
