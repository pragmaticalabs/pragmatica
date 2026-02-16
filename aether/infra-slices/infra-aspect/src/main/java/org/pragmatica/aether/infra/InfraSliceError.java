package org.pragmatica.aether.infra;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Error hierarchy for infrastructure slice failures.
public sealed interface InfraSliceError extends Cause {
    record ConfigurationFailed(String detail) implements InfraSliceError {
        public static Result<ConfigurationFailed> configurationFailed(String detail) {
            return success(new ConfigurationFailed(detail));
        }

        @Override
        public String message() {
            return "Infrastructure slice configuration failed: " + detail;
        }
    }

    record OperationFailed(String operation, String detail) implements InfraSliceError {
        public static Result<OperationFailed> operationFailed(String operation, String detail) {
            return success(new OperationFailed(operation, detail));
        }

        @Override
        public String message() {
            return "Infrastructure operation '" + operation + "' failed: " + detail;
        }
    }

    record NotAvailable(String sliceName) implements InfraSliceError {
        public static Result<NotAvailable> notAvailable(String sliceName) {
            return success(new NotAvailable(sliceName));
        }

        @Override
        public String message() {
            return "Infrastructure slice not available: " + sliceName;
        }
    }

    record RetryFailed(String detail, Option<Throwable> cause) implements InfraSliceError {
        public static Result<RetryFailed> retryFailed(String detail, Throwable cause) {
            return success(new RetryFailed(detail, option(cause)));
        }

        public static Result<RetryFailed> retryFailed(String detail) {
            return success(new RetryFailed(detail, none()));
        }

        @Override
        public String message() {
            return "Retry failed: " + detail + cause.map(RetryFailed::causeSuffix)
                                                   .or("");
        }

        private static String causeSuffix(Throwable c) {
            return " - " + c.getMessage();
        }
    }

    static RetryFailed retryFailed(String detail, Throwable cause) {
        return RetryFailed.retryFailed(detail, cause)
                          .unwrap();
    }

    static RetryFailed retryFailed(String detail) {
        return RetryFailed.retryFailed(detail)
                          .unwrap();
    }

    record CircuitBreakerTripped(String detail, Option<Throwable> cause) implements InfraSliceError {
        public static Result<CircuitBreakerTripped> circuitBreakerTripped(String detail, Throwable cause) {
            return success(new CircuitBreakerTripped(detail, option(cause)));
        }

        public static Result<CircuitBreakerTripped> circuitBreakerTripped(String detail) {
            return success(new CircuitBreakerTripped(detail, none()));
        }

        @Override
        public String message() {
            return "Circuit breaker tripped: " + detail + cause.map(CircuitBreakerTripped::causeSuffix)
                                                              .or("");
        }

        private static String causeSuffix(Throwable c) {
            return " - " + c.getMessage();
        }
    }

    static CircuitBreakerTripped circuitBreakerTripped(String detail, Throwable cause) {
        return CircuitBreakerTripped.circuitBreakerTripped(detail, cause)
                                    .unwrap();
    }

    static CircuitBreakerTripped circuitBreakerTripped(String detail) {
        return CircuitBreakerTripped.circuitBreakerTripped(detail)
                                    .unwrap();
    }

    record unused() implements InfraSliceError {
        public static Result<unused> unused() {
            return success(new unused());
        }

        @Override
        public String message() {
            return "";
        }
    }
}
