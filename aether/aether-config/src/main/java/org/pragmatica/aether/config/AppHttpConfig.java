package org.pragmatica.aether.config;

import java.util.Set;

/**
 * Configuration for application HTTP server.
 *
 * @param enabled            whether the app HTTP server is enabled
 * @param port               base port for app HTTP server (nodes use port, port+1, etc.)
 * @param apiKeys            valid API keys for authentication (empty set disables API key security)
 * @param forwardTimeoutMs   timeout for HTTP forwarding requests in milliseconds
 * @param forwardMaxRetries  maximum number of retries for failed forwarding requests
 */
public record AppHttpConfig(boolean enabled,
                            int port,
                            Set<String> apiKeys,
                            long forwardTimeoutMs,
                            int forwardMaxRetries) {
    public static final int DEFAULT_APP_HTTP_PORT = 8070;
    public static final long DEFAULT_FORWARD_TIMEOUT_MS = 5000;

    // 5 seconds
    public static final int DEFAULT_FORWARD_MAX_RETRIES = 2;

    // 2 retries = 3 total attempts
    /**
     * Canonical constructor with validation.
     */
    public AppHttpConfig {
        apiKeys = Set.copyOf(apiKeys);
        if (forwardTimeoutMs <= 0) {
            forwardTimeoutMs = DEFAULT_FORWARD_TIMEOUT_MS;
        }
        if (forwardMaxRetries < 0) {
            forwardMaxRetries = DEFAULT_FORWARD_MAX_RETRIES;
        }
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static AppHttpConfig appHttpConfig(boolean enabled, int port, Set<String> apiKeys, long forwardTimeoutMs, int forwardMaxRetries) {
        return new AppHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries);
    }

    public static AppHttpConfig defaultConfig() {
        return appHttpConfig(false,
                             DEFAULT_APP_HTTP_PORT,
                             Set.of(),
                             DEFAULT_FORWARD_TIMEOUT_MS,
                             DEFAULT_FORWARD_MAX_RETRIES);
    }

    public static AppHttpConfig enabledOnDefaultPort() {
        return appHttpConfig(true,
                             DEFAULT_APP_HTTP_PORT,
                             Set.of(),
                             DEFAULT_FORWARD_TIMEOUT_MS,
                             DEFAULT_FORWARD_MAX_RETRIES);
    }

    public static AppHttpConfig enabledOnPort(int port) {
        return appHttpConfig(true, port, Set.of(), DEFAULT_FORWARD_TIMEOUT_MS, DEFAULT_FORWARD_MAX_RETRIES);
    }

    public static AppHttpConfig disabled() {
        return appHttpConfig(false,
                             DEFAULT_APP_HTTP_PORT,
                             Set.of(),
                             DEFAULT_FORWARD_TIMEOUT_MS,
                             DEFAULT_FORWARD_MAX_RETRIES);
    }

    /**
     * Create enabled config with API keys for security.
     */
    public static AppHttpConfig enabledWithSecurity(int port, Set<String> apiKeys) {
        return appHttpConfig(true, port, apiKeys, DEFAULT_FORWARD_TIMEOUT_MS, DEFAULT_FORWARD_MAX_RETRIES);
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
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries);
    }

    public AppHttpConfig withPort(int port) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries);
    }

    public AppHttpConfig withApiKeys(Set<String> apiKeys) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries);
    }

    public AppHttpConfig withForwardTimeoutMs(long forwardTimeoutMs) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries);
    }

    public AppHttpConfig withForwardMaxRetries(int forwardMaxRetries) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries);
    }
}
