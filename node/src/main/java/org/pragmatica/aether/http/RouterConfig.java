package org.pragmatica.aether.http;

/**
 * Configuration for the HTTP router.
 */
public record RouterConfig(
        int port,
        int maxContentLength
) {
    public static final int DEFAULT_PORT = 8081;
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024; // 1MB

    public static RouterConfig defaultConfig() {
        return new RouterConfig(DEFAULT_PORT, DEFAULT_MAX_CONTENT_LENGTH);
    }

    public static RouterConfig routerConfig(int port) {
        return new RouterConfig(port, DEFAULT_MAX_CONTENT_LENGTH);
    }

    public static RouterConfig routerConfig(int port, int maxContentLength) {
        return new RouterConfig(port, maxContentLength);
    }
}
