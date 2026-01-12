package org.pragmatica.aether.infra.lock;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.time.Instant;

/**
 * Handle to a held distributed lock.
 * Provides methods to release or extend the lock.
 */
public interface LockHandle extends AutoCloseable {
    /**
     * Get the lock identifier.
     *
     * @return Lock ID
     */
    String lockId();

    /**
     * Get the fencing token for correctness verification.
     * Each lock acquisition gets a monotonically increasing token.
     *
     * @return Fencing token
     */
    String fencingToken();

    /**
     * Get the time when the lock was acquired.
     *
     * @return Acquisition timestamp
     */
    Instant acquiredAt();

    /**
     * Release the lock explicitly.
     *
     * @return Promise completing when lock is released
     */
    Promise<Unit> release();

    /**
     * Extend the lock TTL.
     *
     * @param extension Additional time to hold the lock
     * @return Promise with true if extension succeeded, false if lock was lost
     */
    Promise<Boolean> extend(TimeSpan extension);

    /**
     * AutoCloseable implementation for try-with-resources.
     * Releases the lock synchronously.
     */
    @Override
    default void close() {
        release()
               .await();
    }
}
