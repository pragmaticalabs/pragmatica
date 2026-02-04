package org.pragmatica.aether.infra;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error hierarchy for infrastructure slice failures.
 */
public sealed interface InfraSliceError extends Cause {
    record ConfigurationFailed(String detail) implements InfraSliceError {
        @Override
        public String message() {
            return "Infrastructure slice configuration failed: " + detail;
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

    record RetryFailed(String detail, Option<Throwable> cause) implements InfraSliceError {
        public static RetryFailed retryFailed(String detail, Throwable cause) {
            return new RetryFailed(detail, Option.option(cause));
        }

        public static RetryFailed retryFailed(String detail) {
            return new RetryFailed(detail, Option.none());
        }

        @Override
        public String message() {
            return "Retry failed: " + detail + cause.map(c -> " - " + c.getMessage()).or("");
        }
    }

    record CircuitBreakerTripped(String detail, Option<Throwable> cause) implements InfraSliceError {
        public static CircuitBreakerTripped circuitBreakerTripped(String detail, Throwable cause) {
            return new CircuitBreakerTripped(detail, Option.option(cause));
        }

        public static CircuitBreakerTripped circuitBreakerTripped(String detail) {
            return new CircuitBreakerTripped(detail, Option.none());
        }

        @Override
        public String message() {
            return "Circuit breaker tripped: " + detail + cause.map(c -> " - " + c.getMessage()).or("");
        }
    }
}
