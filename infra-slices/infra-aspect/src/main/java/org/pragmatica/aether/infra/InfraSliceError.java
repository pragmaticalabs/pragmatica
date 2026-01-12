package org.pragmatica.aether.infra;

import org.pragmatica.lang.Cause;

/**
 * Error hierarchy for infrastructure slice failures.
 */
public sealed interface InfraSliceError extends Cause {
    record ConfigurationError(String detail) implements InfraSliceError {
        @Override
        public String message() {
            return "Infrastructure slice configuration error: " + detail;
        }
    }

    record OperationFailed(String operation, String detail) implements InfraSliceError {
        @Override
        public String message() {
            return "Infrastructure operation '" + operation + "' failed: " + detail;
        }
    }

    record NotAvailable(String sliceName) implements InfraSliceError {
        @Override
        public String message() {
            return "Infrastructure slice not available: " + sliceName;
        }
    }

    record RetryError(String detail, Throwable cause) implements InfraSliceError {
        public static RetryError retryError(String detail, Throwable cause) {
            return new RetryError(detail, cause);
        }

        @Override
        public String message() {
            return "Retry failed: " + detail + (cause != null
                                                ? " - " + cause.getMessage()
                                                : "");
        }
    }

    record CircuitBreakerError(String detail, Throwable cause) implements InfraSliceError {
        public static CircuitBreakerError circuitBreakerError(String detail, Throwable cause) {
            return new CircuitBreakerError(detail, cause);
        }

        @Override
        public String message() {
            return "Circuit breaker error: " + detail + (cause != null
                                                         ? " - " + cause.getMessage()
                                                         : "");
        }
    }
}
