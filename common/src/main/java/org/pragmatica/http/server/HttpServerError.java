package org.pragmatica.http.server;

import org.pragmatica.lang.Cause;

/**
 * Error types for HTTP server operations.
 */
public sealed interface HttpServerError extends Cause {
    /**
     * Failed to bind to the specified port.
     */
    record BindFailed(int port, Throwable cause) implements HttpServerError {
        @Override
        public String message() {
            return "Failed to bind to port " + port + ": " + cause.getMessage();
        }
    }

    /**
     * Failed to start server.
     */
    record StartFailed(String reason) implements HttpServerError {
        @Override
        public String message() {
            return "Failed to start HTTP server: " + reason;
        }
    }
}
