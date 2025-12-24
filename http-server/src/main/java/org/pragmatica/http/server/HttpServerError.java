package org.pragmatica.http.server;

import org.pragmatica.lang.Cause;

import java.nio.file.Path;

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
     * TLS configuration failed.
     */
    record TlsConfigFailed(String reason) implements HttpServerError {
        @Override
        public String message() {
            return "TLS configuration failed: " + reason;
        }
    }

    /**
     * Failed to load certificate from file.
     */
    record CertificateLoadFailed(Path path, Throwable cause) implements HttpServerError {
        @Override
        public String message() {
            return "Failed to load certificate from " + path + ": " + cause.getMessage();
        }
    }

    /**
     * Failed to generate self-signed certificate.
     */
    record SelfSignedGenerationFailed(Throwable cause) implements HttpServerError {
        @Override
        public String message() {
            return "Failed to generate self-signed certificate: " + cause.getMessage();
        }
    }
}
