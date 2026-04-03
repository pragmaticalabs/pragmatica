package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Cause;


/// Errors that can occur during slice invocation.
public sealed interface SliceInvokerError extends Cause {
    record AllInstancesFailedError(Artifact artifact, MethodName method, String details) implements SliceInvokerError {
        public static AllInstancesFailedError allInstancesFailedError(Artifact artifact,
                                                                      MethodName method,
                                                                      String details) {
            return new AllInstancesFailedError(artifact, method, details);
        }

        @Override public String message() {
            return "All instances failed for " + artifact + ":" + method + " - " + details;
        }
    }

    record InvocationError(Artifact artifact, MethodName method, Cause cause) implements SliceInvokerError {
        public static InvocationError invocationError(Artifact artifact, MethodName method, Cause cause) {
            return new InvocationError(artifact, method, cause);
        }

        @Override public String message() {
            return "Invocation failed for " + artifact + ":" + method + ": " + cause.message();
        }
    }

    record NoEndpointsError(Artifact artifact, MethodName method) implements SliceInvokerError {
        public static NoEndpointsError noEndpointsError(Artifact artifact, MethodName method) {
            return new NoEndpointsError(artifact, method);
        }

        @Override public String message() {
            return "No endpoints available for " + artifact + ":" + method;
        }
    }

    record MethodHandleError(String artifact, String method, String reason) implements SliceInvokerError {
        public static MethodHandleError methodHandleError(String artifact, String method, String reason) {
            return new MethodHandleError(artifact, method, reason);
        }

        @Override public String message() {
            return "Failed to create method handle for " + artifact + ":" + method + " - " + reason;
        }
    }

    record SerializationError(String details) implements SliceInvokerError {
        public static SerializationError serializationError(String details) {
            return new SerializationError(details);
        }

        @Override public String message() {
            return "Serialization error: " + details;
        }
    }

    record TimeoutError(Artifact artifact, MethodName method, long timeoutMs) implements SliceInvokerError {
        public static TimeoutError timeoutError(Artifact artifact, MethodName method, long timeoutMs) {
            return new TimeoutError(artifact, method, timeoutMs);
        }

        @Override public String message() {
            return "Timeout after " + timeoutMs + "ms waiting for " + artifact + ":" + method;
        }
    }

    record RemoteInvocationError(String errorMessage) implements SliceInvokerError {
        public static RemoteInvocationError remoteInvocationError(String errorMessage) {
            return new RemoteInvocationError(errorMessage);
        }

        @Override public String message() {
            return "Remote invocation failed: " + errorMessage;
        }
    }
}
