package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Cause;

/// Errors that can occur during slice invocation.
public sealed interface SliceInvokerError extends Cause {
    /// All available instances of the target slice failed to respond.
    record AllInstancesFailedError(Artifact artifact, MethodName method, String details) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static AllInstancesFailedError allInstancesFailedError(Artifact artifact,
                                                                      MethodName method,
                                                                      String details) {
            return new AllInstancesFailedError(artifact, method, details);
        }

        @Override
        public String message() {
            return "All instances failed for " + artifact + ":" + method + " - " + details;
        }
    }

    /// A specific invocation attempt failed.
    record InvocationError(Artifact artifact, MethodName method, Cause cause) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static InvocationError invocationError(Artifact artifact, MethodName method, Cause cause) {
            return new InvocationError(artifact, method, cause);
        }

        @Override
        public String message() {
            return "Invocation failed for " + artifact + ":" + method + ": " + cause.message();
        }
    }

    /// No endpoints available for the target slice.
    record NoEndpointsError(Artifact artifact, MethodName method) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static NoEndpointsError noEndpointsError(Artifact artifact, MethodName method) {
            return new NoEndpointsError(artifact, method);
        }

        @Override
        public String message() {
            return "No endpoints available for " + artifact + ":" + method;
        }
    }

    /// Method handle creation failed.
    record MethodHandleError(String artifact, String method, String reason) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static MethodHandleError methodHandleError(String artifact, String method, String reason) {
            return new MethodHandleError(artifact, method, reason);
        }

        @Override
        public String message() {
            return "Failed to create method handle for " + artifact + ":" + method + " - " + reason;
        }
    }

    /// Serialization/deserialization error during invocation.
    record SerializationError(String details) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static SerializationError serializationError(String details) {
            return new SerializationError(details);
        }

        @Override
        public String message() {
            return "Serialization error: " + details;
        }
    }

    /// Timeout waiting for response.
    record TimeoutError(Artifact artifact, MethodName method, long timeoutMs) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static TimeoutError timeoutError(Artifact artifact, MethodName method, long timeoutMs) {
            return new TimeoutError(artifact, method, timeoutMs);
        }

        @Override
        public String message() {
            return "Timeout after " + timeoutMs + "ms waiting for " + artifact + ":" + method;
        }
    }

    /// Error received from remote invocation (error message only, context not available).
    record RemoteInvocationError(String errorMessage) implements SliceInvokerError {
        /// Factory method following JBCT naming convention. */
        public static RemoteInvocationError remoteInvocationError(String errorMessage) {
            return new RemoteInvocationError(errorMessage);
        }

        @Override
        public String message() {
            return "Remote invocation failed: " + errorMessage;
        }
    }
}
