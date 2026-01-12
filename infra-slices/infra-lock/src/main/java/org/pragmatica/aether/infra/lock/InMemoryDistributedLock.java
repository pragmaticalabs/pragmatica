package org.pragmatica.aether.infra.lock;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * In-memory implementation of DistributedLock for testing and single-node scenarios.
 */
final class InMemoryDistributedLock implements DistributedLock {
    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final AtomicLong tokenCounter = new AtomicLong(0);

    @Override
    public Promise<LockHandle> acquire(String lockId, TimeSpan timeout) {
        return tryAcquireWithRetry(lockId, timeout, System.nanoTime(), timeout.nanos());
    }

    private Promise<LockHandle> tryAcquireWithRetry(String lockId,
                                                    TimeSpan timeout,
                                                    long startTime,
                                                    long timeoutNanos) {
        return tryAcquireInternal(lockId)
                                 .map(Promise::success)
                                 .or(() -> retryAfterDelay(lockId, timeout, startTime, timeoutNanos));
    }

    private Promise<LockHandle> retryAfterDelay(String lockId, TimeSpan timeout, long startTime, long timeoutNanos) {
        if (System.nanoTime() - startTime >= timeoutNanos) {
            return new LockError.AcquisitionTimeout(lockId, timeout).promise();
        }
        return Promise.<LockHandle>promise(timeSpan(10)
                                                   .millis(),
                                           promise -> tryAcquireWithRetry(lockId, timeout, startTime, timeoutNanos)
                                                                         .onResult(promise::resolve));
    }

    @Override
    public Promise<Option<LockHandle>> tryAcquire(String lockId) {
        return Promise.success(tryAcquireInternal(lockId));
    }

    @Override
    public <T> Promise<T> withLock(String lockId, TimeSpan timeout, Fn0<Promise<T>> action) {
        return acquire(lockId, timeout)
                      .flatMap(handle -> action.apply()
                                               .onResultRun(handle::release));
    }

    private Option<LockHandle> tryAcquireInternal(String lockId) {
        var ownerId = UUID.randomUUID()
                          .toString();
        var acquiredRef = new AtomicBoolean(false);
        var entry = locks.computeIfAbsent(lockId,
                                          key -> {
                                              acquiredRef.set(true);
                                              var token = String.valueOf(tokenCounter.incrementAndGet());
                                              return new LockEntry(ownerId, token, Instant.now());
                                          });
        if (acquiredRef.get() && entry.ownerId()
                                      .equals(ownerId)) {
            return some(createHandle(lockId, entry.token(), ownerId, entry.acquiredAt()));
        }
        return none();
    }

    private LockHandle createHandle(String lockId, String token, String ownerId, Instant acquiredAt) {
        return new InMemoryLockHandle(lockId, token, ownerId, acquiredAt, this);
    }

    Promise<Unit> releaseLock(String lockId, String ownerId) {
        var entry = locks.get(lockId);
        if (entry != null && entry.ownerId()
                                  .equals(ownerId)) {
            locks.remove(lockId, entry);
            return Promise.success(unit());
        }
        return new LockError.LockNotHeld(lockId).promise();
    }

    Promise<Boolean> extendLock(String lockId, String ownerId, TimeSpan extension) {
        var entry = locks.get(lockId);
        if (entry != null && entry.ownerId()
                                  .equals(ownerId)) {
            return Promise.success(true);
        }
        return Promise.success(false);
    }

    private record LockEntry(String ownerId, String token, Instant acquiredAt) {}

    private record InMemoryLockHandle(String lockId,
                                      String fencingToken,
                                      String ownerId,
                                      Instant acquiredAt,
                                      InMemoryDistributedLock lockService) implements LockHandle {
        @Override
        public Promise<Unit> release() {
            return lockService.releaseLock(lockId, ownerId);
        }

        @Override
        public Promise<Boolean> extend(TimeSpan extension) {
            return lockService.extendLock(lockId, ownerId, extension);
        }
    }
}
