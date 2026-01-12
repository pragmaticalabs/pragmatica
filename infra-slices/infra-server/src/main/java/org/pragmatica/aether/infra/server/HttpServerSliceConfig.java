package org.pragmatica.aether.infra.server;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;

/**
 * Configuration for HTTP server infrastructure slice.
 *
 * @param name              Server name for logging and identification
 * @param port              Port to bind to (1-65535)
 * @param maxContentLength  Maximum content length for requests (bytes)
 * @param requestTimeout    Request processing timeout
 * @param idleTimeout       Idle connection timeout
 */
public record HttpServerSliceConfig(String name,
                                    int port,
                                    int maxContentLength,
                                    TimeSpan requestTimeout,
                                    TimeSpan idleTimeout) {
    private static final String DEFAULT_NAME = "http-server";
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 65536;
    private static final TimeSpan DEFAULT_REQUEST_TIMEOUT = TimeSpan.timeSpan(30)
                                                                   .seconds();
    private static final TimeSpan DEFAULT_IDLE_TIMEOUT = TimeSpan.timeSpan(60)
                                                                .seconds();

    /**
     * Creates a default configuration.
     *
     * @return Result containing default configuration
     */
    public static Result<HttpServerSliceConfig> httpServerSliceConfig() {
        return success(new HttpServerSliceConfig(DEFAULT_NAME,
                                                 DEFAULT_PORT,
                                                 DEFAULT_MAX_CONTENT_LENGTH,
                                                 DEFAULT_REQUEST_TIMEOUT,
                                                 DEFAULT_IDLE_TIMEOUT));
    }

    /**
     * Creates configuration with specified port.
     *
     * @param port Port to bind to
     * @return Result containing configuration or error if port is invalid
     */
    public static Result<HttpServerSliceConfig> httpServerSliceConfig(int port) {
        return validatePort(port)
                           .map(_ -> new HttpServerSliceConfig(DEFAULT_NAME,
                                                               port,
                                                               DEFAULT_MAX_CONTENT_LENGTH,
                                                               DEFAULT_REQUEST_TIMEOUT,
                                                               DEFAULT_IDLE_TIMEOUT));
    }

    /**
     * Creates configuration with specified name and port.
     *
     * @param name Server name
     * @param port Port to bind to
     * @return Result containing configuration or error if invalid
     */
    public static Result<HttpServerSliceConfig> httpServerSliceConfig(String name, int port) {
        return Result.all(validateName(name),
                          validatePort(port))
                     .map((n, p) -> new HttpServerSliceConfig(n,
                                                              p,
                                                              DEFAULT_MAX_CONTENT_LENGTH,
                                                              DEFAULT_REQUEST_TIMEOUT,
                                                              DEFAULT_IDLE_TIMEOUT));
    }

    private static Result<String> validateName(String name) {
        return org.pragmatica.lang.Option.option(name)
                  .filter(n -> !n.isBlank())
                  .map(String::trim)
                  .toResult(HttpServerSliceError.invalidConfiguration("Server name cannot be null or empty"));
    }

    private static Result<Integer> validatePort(int port) {
        if (port < 1 || port > 65535) {
            return new HttpServerSliceError.InvalidConfiguration("Port must be between 1 and 65535, got: " + port).result();
        }
        return success(port);
    }

    /**
     * Creates a new configuration with the specified name.
     *
     * @param name Server name
     * @return New configuration with updated name
     */
    public HttpServerSliceConfig withName(String name) {
        return new HttpServerSliceConfig(name, port, maxContentLength, requestTimeout, idleTimeout);
    }

    /**
     * Creates a new configuration with the specified port.
     *
     * @param port Port to bind to
     * @return New configuration with updated port
     */
    public HttpServerSliceConfig withPort(int port) {
        return new HttpServerSliceConfig(name, port, maxContentLength, requestTimeout, idleTimeout);
    }

    /**
     * Creates a new configuration with the specified max content length.
     *
     * @param maxContentLength Maximum content length in bytes
     * @return New configuration with updated max content length
     */
    public HttpServerSliceConfig withMaxContentLength(int maxContentLength) {
        return new HttpServerSliceConfig(name, port, maxContentLength, requestTimeout, idleTimeout);
    }

    /**
     * Creates a new configuration with the specified request timeout.
     *
     * @param requestTimeout Request processing timeout
     * @return New configuration with updated request timeout
     */
    public HttpServerSliceConfig withRequestTimeout(TimeSpan requestTimeout) {
        return new HttpServerSliceConfig(name, port, maxContentLength, requestTimeout, idleTimeout);
    }

    /**
     * Creates a new configuration with the specified idle timeout.
     *
     * @param idleTimeout Idle connection timeout
     * @return New configuration with updated idle timeout
     */
    public HttpServerSliceConfig withIdleTimeout(TimeSpan idleTimeout) {
        return new HttpServerSliceConfig(name, port, maxContentLength, requestTimeout, idleTimeout);
    }
}
