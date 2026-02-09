# Aether Infrastructure: Distributed Lock

Distributed locking service for the Aether distributed runtime.

## Overview

Provides distributed locking for coordinating access to shared resources across slices. Supports blocking acquire with timeout, non-blocking try-acquire, and automatic release via the `withLock` pattern.

Errors are typed via `LockError` (`AcquireTimeout`, `AlreadyReleased`).

## Usage

```java
var lock = DistributedLock.inMemory();

// Blocking acquire with timeout
lock.acquire("resource:123", timeSpan(5).seconds())
    .onSuccess(handle -> {
        try { /* critical section */ }
        finally { handle.release(); }
    });

// Non-blocking try-acquire
lock.tryAcquire("resource:123")
    .onSuccess(optHandle -> optHandle
        .onPresent(handle -> { /* got lock */ handle.release(); })
        .onEmpty(() -> { /* lock held by another */ }));

// Automatic release (recommended)
lock.withLock("resource:123", timeSpan(5).seconds(), () -> {
    return doSomething();
}).await();
```

## Dependencies

- `pragmatica-lite-core`
