package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Cause;

/**
 * Errors that can occur during slice invocation.
 */
public sealed interface SliceInvokerError extends Cause {
    /**
     * All available instances of the target slice failed to respond.
     */
    record AllInstancesFailedError(Artifact artifact, MethodName method, String details) implements SliceInvokerError {
        @Override
        public String message() {
            return "All instances failed for " + artifact + ":" + method + " - " + details;
        }
    }

    /**
     * A specific invocation attempt failed.
     */
    record InvocationError(Artifact artifact, MethodName method, Cause cause) implements SliceInvokerError {
        @Override
        public String message() {
            return "Invocation failed for " + artifact + ":" + method + ": " + cause.message();
        }
    }

    /**
     * No endpoints available for the target slice.
     */
    record NoEndpointsError(Artifact artifact, MethodName method) implements SliceInvokerError {
        @Override
        public String message() {
            return "No endpoints available for " + artifact + ":" + method;
        }
    }

    /**
     * Method handle creation failed.
     */
    record MethodHandleError(String artifact, String method, String reason) implements SliceInvokerError {
        @Override
        public String message() {
            return "Failed to create method handle for " + artifact + ":" + method + " - " + reason;
        }
    }

    /**
     * Serialization/deserialization error during invocation.
     */
    record SerializationError(String details) implements SliceInvokerError {
        @Override
        public String message() {
            return "Serialization error: " + details;
        }
    }

    /**
     * Timeout waiting for response.
     */
    record TimeoutError(Artifact artifact, MethodName method, long timeoutMs) implements SliceInvokerError {
        @Override
        public String message() {
            return "Timeout after " + timeoutMs + "ms waiting for " + artifact + ":" + method;
        }
    }

    /**
     * Error received from remote invocation (error message only, context not available).
     */
    record RemoteInvocationError(String errorMessage) implements SliceInvokerError {
        @Override
        public String message() {
            return "Remote invocation failed: " + errorMessage;
        }
    }
}
