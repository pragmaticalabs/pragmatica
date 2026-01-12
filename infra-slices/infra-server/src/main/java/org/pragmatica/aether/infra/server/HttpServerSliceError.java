package org.pragmatica.aether.infra.server;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

/**
 * Error types for HTTP server slice operations.
 */
public sealed interface HttpServerSliceError extends Cause {
    /**
     * Invalid configuration error.
     */
    record InvalidConfiguration(String detail) implements HttpServerSliceError {
        public static InvalidConfiguration invalidConfiguration(String detail) {
            return new InvalidConfiguration(detail);
        }

        @Override
        public String message() {
            return "Invalid HTTP server configuration: " + detail;
        }
    }

    static InvalidConfiguration invalidConfiguration(String detail) {
        return InvalidConfiguration.invalidConfiguration(detail);
    }

    /**
     * Failed to bind to the specified port.
     */
    record BindFailed(int port, Throwable cause) implements HttpServerSliceError {
        public static BindFailed bindFailed(int port, Throwable cause) {
            return new BindFailed(port, cause);
        }

        @Override
        public String message() {
            return "Failed to bind HTTP server to port " + port + ": " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }

    /**
     * Failed to start the server.
     */
    record StartFailed(String reason, Option<Throwable> cause) implements HttpServerSliceError {
        public static StartFailed startFailed(String reason, Throwable cause) {
            return new StartFailed(reason, Option.option(cause));
        }

        public static StartFailed startFailed(String reason) {
            return new StartFailed(reason, Option.empty());
        }

        @Override
        public String message() {
            return "Failed to start HTTP server: " + reason + cause.fold(() -> "", c -> " - " + c.getMessage());
        }

        @Override
        public Option<Cause> source() {
            return cause.map(Causes::fromThrowable);
        }
    }

    /**
     * Route conflict - duplicate route registration.
     */
    record RouteConflict(String method, String path) implements HttpServerSliceError {
        public static RouteConflict routeConflict(String method, String path) {
            return new RouteConflict(method, path);
        }

        @Override
        public String message() {
            return "Route conflict: " + method + " " + path + " already registered";
        }
    }

    /**
     * Server not running error.
     */
    record NotRunning(String operation) implements HttpServerSliceError {
        public static NotRunning notRunning(String operation) {
            return new NotRunning(operation);
        }

        @Override
        public String message() {
            return "HTTP server not running, cannot perform: " + operation;
        }
    }

    /**
     * Request handling failed.
     */
    record RequestFailed(String requestId, Throwable cause) implements HttpServerSliceError {
        public static RequestFailed requestFailed(String requestId, Throwable cause) {
            return new RequestFailed(requestId, cause);
        }

        @Override
        public String message() {
            return "Request " + requestId + " failed: " + cause.getMessage();
        }

        @Override
        public Option<Cause> source() {
            return Option.some(Causes.fromThrowable(cause));
        }
    }
}
