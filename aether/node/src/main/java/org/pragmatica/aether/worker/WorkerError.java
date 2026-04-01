package org.pragmatica.aether.worker;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Error hierarchy for worker node failures.
public sealed interface WorkerError extends Cause {
    /// Fixed-message errors.
    enum General implements WorkerError {
        NO_CORE_NODES("No core nodes configured"),
        NODE_ALREADY_STARTED("Worker node is already started"),
        NODE_NOT_STARTED("Worker node has not been started"),
        BOOTSTRAP_FAILED("Bootstrap failed: no snapshot source available"),
        GOVERNOR_UNAVAILABLE("Governor node is unavailable");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    /// Network-level failure wrapping an underlying exception.
    record NetworkFailure(Throwable cause) implements WorkerError {
        @Override public String message() {
            return "Worker network failure: " + Causes.fromThrowable(cause);
        }
    }

    /// Configuration error.
    record ConfigurationError(String detail) implements WorkerError {
        @Override public String message() {
            return "Worker configuration error: " + detail;
        }
    }

    record unused() implements WorkerError {
        @Override public String message() {
            return "unused";
        }
    }
}
