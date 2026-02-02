# Aether Infrastructure: Distributed Lock

Distributed locking service for [Aether](https://github.com/siy/aether) distributed runtime.

## Installation

```xml
<dependency>
    <groupId>org.pragmatica-lite.aether</groupId>
    <artifactId>infra-lock</artifactId>
    <version>${aether.version}</version>
</dependency>
```

## Overview

Provides distributed locking for coordinating access to shared resources across slices.

### Key Features

- Blocking acquire with timeout
- Non-blocking try-acquire
- Automatic release via `withLock` pattern
- Lock handle for manual release

### Quick Start

```java
var lock = DistributedLock.inMemory();

// Blocking acquire with timeout
lock.acquire("resource:123", timeSpan(5).seconds())
    .onSuccess(handle -> {
        try {
            // Critical section
        } finally {
            handle.release();
        }
    });

// Non-blocking try-acquire
lock.tryAcquire("resource:123")
    .onSuccess(optHandle -> optHandle
        .onPresent(handle -> {
            // Got the lock
            handle.release();
        })
        .onEmpty(() -> {
            // Lock held by another
        }));

// Automatic release pattern (recommended)
lock.withLock("resource:123", timeSpan(5).seconds(), () -> {
    // Critical section - lock released automatically
    return doSomething();
}).await();
```

### API Summary

| Method | Description |
|--------|-------------|
| `acquire(lockId, timeout)` | Blocking acquire, fails if timeout exceeded |
| `tryAcquire(lockId)` | Non-blocking, returns `Option<LockHandle>` |
| `withLock(lockId, timeout, action)` | Execute action with automatic lock release |

### LockHandle

```java
public interface LockHandle {
    String lockId();
    Instant acquiredAt();
    void release();
}
```

### Error Handling

```java
sealed interface LockError extends Cause {
    record AcquireTimeout(String lockId, TimeSpan timeout) implements LockError;
    record AlreadyReleased(String lockId) implements LockError;
}
```

## License

Apache License 2.0
