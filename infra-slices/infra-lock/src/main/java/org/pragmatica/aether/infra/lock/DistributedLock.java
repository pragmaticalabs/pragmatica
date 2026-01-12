package org.pragmatica.aether.infra.lock;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

/**
 * Distributed locking service for coordinating access to shared resources.
 */
public interface DistributedLock extends Slice {
    /**
     * Acquire a lock, waiting up to the specified timeout.
     *
     * @param lockId  Lock identifier
     * @param timeout Maximum time to wait for lock acquisition
     * @return Promise with lock handle on success, failure if timeout exceeded
     */
    Promise<LockHandle> acquire(String lockId, TimeSpan timeout);

    /**
     * Try to acquire a lock without waiting.
     *
     * @param lockId Lock identifier
     * @return Promise with optional lock handle (empty if lock is held by another)
     */
    Promise<Option<LockHandle>> tryAcquire(String lockId);

    /**
     * Execute an action while holding a lock, with automatic release.
     *
     * @param lockId  Lock identifier
     * @param timeout Maximum time to wait for lock acquisition
     * @param action  Action to execute while holding the lock
     * @param <T>     Action result type
     * @return Promise with action result
     */
    <T> Promise<T> withLock(String lockId, TimeSpan timeout, Fn0<Promise<T>> action);

    /**
     * Factory method for in-memory implementation.
     *
     * @return DistributedLock instance
     */
    static DistributedLock inMemory() {
        return new InMemoryDistributedLock();
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
