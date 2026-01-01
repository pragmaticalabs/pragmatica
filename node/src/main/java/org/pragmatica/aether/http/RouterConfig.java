package org.pragmatica.aether.http;

import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

/**
 * Configuration for the HTTP router.
 *
 * @param port             Port to bind to
 * @param maxContentLength Maximum content length for requests
 * @param tls              TLS configuration (empty for plain HTTP)
 */
public record RouterConfig(
 int port,
 int maxContentLength,
 Option<TlsConfig> tls) {
    public static final int DEFAULT_PORT = 8081;
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;

    // 1MB
    public static RouterConfig defaultConfig() {
        return new RouterConfig(DEFAULT_PORT, DEFAULT_MAX_CONTENT_LENGTH, Option.empty());
    }

    public static RouterConfig routerConfig(int port) {
        return new RouterConfig(port, DEFAULT_MAX_CONTENT_LENGTH, Option.empty());
    }

    public static RouterConfig routerConfig(int port, int maxContentLength) {
        return new RouterConfig(port, maxContentLength, Option.empty());
    }

    /**
     * Create a new configuration with TLS enabled.
     */
    public RouterConfig withTls(TlsConfig tlsConfig) {
        return new RouterConfig(port, maxContentLength, Option.some(tlsConfig));
    }
}
