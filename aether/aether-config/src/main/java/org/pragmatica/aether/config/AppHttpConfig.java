package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import java.util.Set;

import static org.pragmatica.lang.Result.success;

/// Configuration for application HTTP server.
///
/// @param enabled            whether the app HTTP server is enabled
/// @param port               base port for app HTTP server (nodes use port, port+1, etc.)
/// @param apiKeys            valid API keys for authentication (empty set disables API key security)
/// @param forwardTimeoutMs   timeout for HTTP forwarding requests in milliseconds
/// @param forwardMaxRetries  maximum number of retries for failed forwarding requests
public record AppHttpConfig(boolean enabled,
                            int port,
                            Set<String> apiKeys,
                            long forwardTimeoutMs,
                            int forwardMaxRetries) {
    public static final int DEFAULT_APP_HTTP_PORT = 8070;
    public static final long DEFAULT_FORWARD_TIMEOUT_MS = 5000;
    public static final int DEFAULT_FORWARD_MAX_RETRIES = 2;

    /// Canonical constructor with defaults.
    public AppHttpConfig {
        apiKeys = Set.copyOf(apiKeys);
        forwardTimeoutMs = normalizeTimeout(forwardTimeoutMs);
        forwardMaxRetries = normalizeRetries(forwardMaxRetries);
    }

    /// Factory method following JBCT naming convention.
    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Set<String> apiKeys,
                                                      long forwardTimeoutMs,
                                                      int forwardMaxRetries) {
        return success(new AppHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries));
    }

    /// Default (disabled) configuration.
    public static AppHttpConfig appHttpConfig() {
        return appHttpConfig(false,
                             DEFAULT_APP_HTTP_PORT,
                             Set.of(),
                             DEFAULT_FORWARD_TIMEOUT_MS,
                             DEFAULT_FORWARD_MAX_RETRIES).unwrap();
    }

    /// Enabled on default port.
    public static AppHttpConfig appHttpConfig(boolean enabled) {
        return appHttpConfig(enabled,
                             DEFAULT_APP_HTTP_PORT,
                             Set.of(),
                             DEFAULT_FORWARD_TIMEOUT_MS,
                             DEFAULT_FORWARD_MAX_RETRIES).unwrap();
    }

    /// Enabled on specified port.
    public static AppHttpConfig appHttpConfig(int port) {
        return appHttpConfig(true, port, Set.of(), DEFAULT_FORWARD_TIMEOUT_MS, DEFAULT_FORWARD_MAX_RETRIES).unwrap();
    }

    /// Create enabled config with API keys for security.
    public static AppHttpConfig appHttpConfig(int port, Set<String> apiKeys) {
        return appHttpConfig(true, port, apiKeys, DEFAULT_FORWARD_TIMEOUT_MS, DEFAULT_FORWARD_MAX_RETRIES).unwrap();
    }

    /// Get app HTTP port for a specific node (0-indexed).
    public int portFor(int nodeIndex) {
        return port + nodeIndex;
    }

    /// Check if security is enabled (has API keys configured).
    public boolean securityEnabled() {
        return ! apiKeys.isEmpty();
    }

    public AppHttpConfig withEnabled(boolean enabled) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries).unwrap();
    }

    public AppHttpConfig withPort(int port) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries).unwrap();
    }

    public AppHttpConfig withApiKeys(Set<String> apiKeys) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries).unwrap();
    }

    public AppHttpConfig withForwardTimeoutMs(long forwardTimeoutMs) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries).unwrap();
    }

    public AppHttpConfig withForwardMaxRetries(int forwardMaxRetries) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeoutMs, forwardMaxRetries).unwrap();
    }

    private static long normalizeTimeout(long forwardTimeoutMs) {
        return forwardTimeoutMs > 0
               ? forwardTimeoutMs
               : DEFAULT_FORWARD_TIMEOUT_MS;
    }

    private static int normalizeRetries(int forwardMaxRetries) {
        return forwardMaxRetries >= 0
               ? forwardMaxRetries
               : DEFAULT_FORWARD_MAX_RETRIES;
    }
}
