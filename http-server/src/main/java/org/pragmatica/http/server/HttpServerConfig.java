package org.pragmatica.http.server;

import org.pragmatica.lang.Option;

/**
 * Configuration for HTTP server.
 *
 * @param port             server port
 * @param maxContentLength maximum request body size in bytes
 * @param tls              optional TLS configuration for HTTPS
 */
public record HttpServerConfig(
        int port,
        int maxContentLength,
        Option<TlsConfig> tls
) {
    public static final int DEFAULT_PORT = 8080;
    public static final int DEFAULT_MAX_CONTENT = 1024 * 1024; // 1MB

    /**
     * Create HTTP (non-TLS) server configuration.
     */
    public static HttpServerConfig http(int port) {
        return new HttpServerConfig(port, DEFAULT_MAX_CONTENT, Option.empty());
    }

    /**
     * Create HTTP server configuration with custom max content length.
     */
    public static HttpServerConfig http(int port, int maxContentLength) {
        return new HttpServerConfig(port, maxContentLength, Option.empty());
    }

    /**
     * Create HTTPS (TLS) server configuration.
     */
    public static HttpServerConfig https(int port, TlsConfig tls) {
        return new HttpServerConfig(port, DEFAULT_MAX_CONTENT, Option.some(tls));
    }

    /**
     * Create HTTPS server configuration with custom max content length.
     */
    public static HttpServerConfig https(int port, TlsConfig tls, int maxContentLength) {
        return new HttpServerConfig(port, maxContentLength, Option.some(tls));
    }

    /**
     * Create default HTTP server on port 8080.
     */
    public static HttpServerConfig defaultConfig() {
        return http(DEFAULT_PORT);
    }

    /**
     * Check if TLS is enabled.
     */
    public boolean isTlsEnabled() {
        return tls.isPresent();
    }
}
