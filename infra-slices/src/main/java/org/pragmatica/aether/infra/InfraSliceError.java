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
}
