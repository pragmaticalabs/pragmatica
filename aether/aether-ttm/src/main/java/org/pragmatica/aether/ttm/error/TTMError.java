package org.pragmatica.aether.ttm.error;

import org.pragmatica.lang.Cause;


/// Errors that can occur during TTM operations.
public sealed interface TTMError extends Cause {
    record ModelLoadFailed(String path, String reason) implements TTMError {
        @Override public String message() {
            return "Failed to load TTM model from " + path + ": " + reason;
        }
    }

    record InferenceFailed(String reason) implements TTMError {
        @Override public String message() {
            return "TTM inference failed: " + reason;
        }
    }

    record InsufficientData(int available, int required) implements TTMError {
        @Override public String message() {
            return "Insufficient data for TTM prediction: " + available + " minutes available, " + required + " required";
        }
    }

    record Disabled() implements TTMError {
        public static Disabled disabled() {
            return new Disabled();
        }

        @Override public String message() {
            return "TTM is disabled in configuration";
        }
    }

    record UnexpectedOutputType(String actualType) implements TTMError {
        @Override public String message() {
            return "Unexpected output tensor type: " + actualType;
        }
    }

    record NoProvider() implements TTMError {
        public static NoProvider noProvider() {
            return new NoProvider();
        }

        @Override public String message() {
            return "No TTM predictor implementation available. Add aether-ttm-onnx to classpath.";
        }
    }
}
