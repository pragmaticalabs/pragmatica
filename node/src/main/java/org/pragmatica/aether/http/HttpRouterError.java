package org.pragmatica.aether.http;

import org.pragmatica.lang.Cause;

/**
 * Errors that can occur during HTTP request processing.
 */
public sealed interface HttpRouterError extends Cause {

    record RouteNotFound(String path) implements HttpRouterError {
        @Override
        public String message() {
            return "No route found for path: " + path;
        }
    }

    record BindingFailed(String param, String reason) implements HttpRouterError {
        @Override
        public String message() {
            return "Failed to bind parameter '" + param + "': " + reason;
        }
    }

    record SliceNotFound(String sliceId) implements HttpRouterError {
        @Override
        public String message() {
            return "Slice not found: " + sliceId;
        }
    }

    record InvocationFailed(String sliceId, String method, Cause cause) implements HttpRouterError {
        @Override
        public String message() {
            return "Invocation failed for " + sliceId + "." + method + ": " + cause.message();
        }
    }

    record DeserializationFailed(Cause cause) implements HttpRouterError {
        @Override
        public String message() {
            return "Failed to deserialize request body: " + cause.message();
        }
    }

    record SerializationFailed(Cause cause) implements HttpRouterError {
        @Override
        public String message() {
            return "Failed to serialize response: " + cause.message();
        }
    }
}
