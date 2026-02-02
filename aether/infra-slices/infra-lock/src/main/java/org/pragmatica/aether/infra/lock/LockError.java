package org.pragmatica.aether.infra.lock;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

/**
 * Error hierarchy for distributed lock operations.
 */
public sealed interface LockError extends Cause {
    record AcquisitionTimeout(String lockId, TimeSpan timeout) implements LockError {
        @Override
        public String message() {
            return "Failed to acquire lock '" + lockId + "' within " + timeout;
        }
    }

    record LockNotHeld(String lockId) implements LockError {
        @Override
        public String message() {
            return "Lock '" + lockId + "' is not held by this handle";
        }
    }

    record LockAlreadyReleased(String lockId) implements LockError {
        @Override
        public String message() {
            return "Lock '" + lockId + "' has already been released";
        }
    }
}
