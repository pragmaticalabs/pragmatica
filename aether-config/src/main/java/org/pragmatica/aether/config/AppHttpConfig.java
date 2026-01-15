package org.pragmatica.aether.config;

import java.util.Objects;
import java.util.Set;

/**
 * Configuration for application HTTP server.
 *
 * @param enabled whether the app HTTP server is enabled
 * @param port    base port for app HTTP server (nodes use port, port+1, etc.)
 * @param apiKeys valid API keys for authentication (empty set disables API key security)
 */
public record AppHttpConfig(boolean enabled,
                            int port,
                            Set<String> apiKeys) {
    public static final int DEFAULT_APP_HTTP_PORT = 8070;

    /**
     * Canonical constructor with validation.
     */
    public AppHttpConfig {
        Objects.requireNonNull(apiKeys, "apiKeys");
    }

    public static AppHttpConfig defaults() {
        return new AppHttpConfig(false, DEFAULT_APP_HTTP_PORT, Set.of());
    }

    public static AppHttpConfig enabledOnDefaultPort() {
        return new AppHttpConfig(true, DEFAULT_APP_HTTP_PORT, Set.of());
    }

    public static AppHttpConfig enabledOnPort(int port) {
        return new AppHttpConfig(true, port, Set.of());
    }

    public static AppHttpConfig disabled() {
        return new AppHttpConfig(false, DEFAULT_APP_HTTP_PORT, Set.of());
    }

    /**
     * Create enabled config with API keys for security.
     */
    public static AppHttpConfig enabledWithSecurity(int port, Set<String> apiKeys) {
        return new AppHttpConfig(true, port, apiKeys);
    }

    /**
     * Get app HTTP port for a specific node (0-indexed).
     */
    public int portFor(int nodeIndex) {
        return port + nodeIndex;
    }

    /**
     * Check if security is enabled (has API keys configured).
     */
    public boolean securityEnabled() {
        return ! apiKeys.isEmpty();
    }

    public AppHttpConfig withEnabled(boolean enabled) {
        return new AppHttpConfig(enabled, port, apiKeys);
    }

    public AppHttpConfig withPort(int port) {
        return new AppHttpConfig(enabled, port, apiKeys);
    }

    public AppHttpConfig withApiKeys(Set<String> apiKeys) {
        return new AppHttpConfig(enabled, port, apiKeys);
    }
}
