package org.pragmatica.aether.lb;

import org.pragmatica.lang.Cause;

/// Error types for load balancer operations.
public sealed interface LoadBalancerError extends Cause {
    /// No healthy backend available to handle the request.
    record NoHealthyBackend() implements LoadBalancerError {
        @Override
        public String message() {
            return "No healthy backend available";
        }
    }

    /// Failed to forward request to backend.
    record ForwardFailed(String backend, Throwable cause) implements LoadBalancerError {
        @Override
        public String message() {
            return "Failed to forward request to " + backend + ": " + cause.getMessage();
        }
    }

    /// All retry attempts exhausted.
    record AllRetriesExhausted(int attempts) implements LoadBalancerError {
        @Override
        public String message() {
            return "All " + attempts + " retry attempts exhausted";
        }
    }

    /// Configuration error.
    record ConfigError(String detail) implements LoadBalancerError {
        @Override
        public String message() {
            return "Load balancer configuration error: " + detail;
        }
    }

    static LoadBalancerError noHealthyBackend() {
        return new NoHealthyBackend();
    }

    static LoadBalancerError forwardFailed(String backend, Throwable cause) {
        return new ForwardFailed(backend, cause);
    }

    static LoadBalancerError allRetriesExhausted(int attempts) {
        return new AllRetriesExhausted(attempts);
    }

    static LoadBalancerError configError(String detail) {
        return new ConfigError(detail);
    }
}
