package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for application HTTP server.
///
/// @param enabled            whether the app HTTP server is enabled
/// @param port               base port for app HTTP server (nodes use port, port+1, etc.)
/// @param apiKeys            API key map: raw key string to entry metadata (empty map disables security)
/// @param forwardTimeout     timeout for HTTP forwarding requests
/// @param maxRequestSize     maximum request body size in bytes
public record AppHttpConfig(boolean enabled,
                            int port,
                            Map<String, ApiKeyEntry> apiKeys,
                            TimeSpan forwardTimeout,
                            int maxRequestSize) {
    public static final int DEFAULT_APP_HTTP_PORT = 8070;
    public static final TimeSpan DEFAULT_FORWARD_TIMEOUT = timeSpan(5).seconds();
    public static final int DEFAULT_MAX_REQUEST_SIZE = 10 * 1024 * 1024; // 10MB

    /// Canonical constructor with defaults.
    public AppHttpConfig {
        apiKeys = Map.copyOf(apiKeys);
        forwardTimeout = normalizeTimeout(forwardTimeout);
        maxRequestSize = normalizeMaxRequestSize(maxRequestSize);
    }

    /// Factory method following JBCT naming convention.
    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      TimeSpan forwardTimeout,
                                                      int maxRequestSize) {
        return success(new AppHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize));
    }

    /// Default (disabled) configuration.
    public static AppHttpConfig appHttpConfig() {
        return appHttpConfig(false, DEFAULT_APP_HTTP_PORT, Map.of(), DEFAULT_FORWARD_TIMEOUT, DEFAULT_MAX_REQUEST_SIZE).unwrap();
    }

    /// Enabled on default port.
    public static AppHttpConfig appHttpConfig(boolean enabled) {
        return appHttpConfig(enabled, DEFAULT_APP_HTTP_PORT, Map.of(), DEFAULT_FORWARD_TIMEOUT, DEFAULT_MAX_REQUEST_SIZE).unwrap();
    }

    /// Enabled on specified port.
    public static AppHttpConfig appHttpConfig(int port) {
        return appHttpConfig(true, port, Map.of(), DEFAULT_FORWARD_TIMEOUT, DEFAULT_MAX_REQUEST_SIZE).unwrap();
    }

    /// Create enabled config with API keys for security (backward-compat convenience).
    public static AppHttpConfig appHttpConfig(int port, Set<String> apiKeys) {
        return appHttpConfig(true, port, wrapSimpleKeys(apiKeys), DEFAULT_FORWARD_TIMEOUT, DEFAULT_MAX_REQUEST_SIZE).unwrap();
    }

    /// Raw API key values for backward-compatible security checks.
    public Set<String> apiKeyValues() {
        return Set.copyOf(apiKeys.keySet());
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
        return appHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize).unwrap();
    }

    public AppHttpConfig withPort(int port) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize).unwrap();
    }

    /// Backward-compat: wraps each key string with default metadata.
    public AppHttpConfig withApiKeys(Set<String> apiKeys) {
        return appHttpConfig(enabled, port, wrapSimpleKeys(apiKeys), forwardTimeout, maxRequestSize).unwrap();
    }

    /// Rich config: accepts pre-built key-to-entry map.
    public AppHttpConfig withApiKeyMap(Map<String, ApiKeyEntry> apiKeys) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize).unwrap();
    }

    public AppHttpConfig withForwardTimeout(TimeSpan forwardTimeout) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize).unwrap();
    }

    public AppHttpConfig withMaxRequestSize(int maxRequestSize) {
        return appHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize).unwrap();
    }

    private static int normalizeMaxRequestSize(int maxRequestSize) {
        return maxRequestSize > 0
               ? maxRequestSize
               : DEFAULT_MAX_REQUEST_SIZE;
    }

    private static TimeSpan normalizeTimeout(TimeSpan forwardTimeout) {
        return forwardTimeout.millis() > 0
               ? forwardTimeout
               : DEFAULT_FORWARD_TIMEOUT;
    }

    private static Map<String, ApiKeyEntry> wrapSimpleKeys(Set<String> keys) {
        var map = new HashMap<String, ApiKeyEntry>();
        keys.forEach(key -> map.put(key, ApiKeyEntry.defaultEntry(key)));
        return Map.copyOf(map);
    }
}
