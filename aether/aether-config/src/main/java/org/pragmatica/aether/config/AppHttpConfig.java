package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
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
/// @param securityMode       authentication mode for app HTTP endpoints (NONE, API_KEY, JWT)
/// @param jwtConfig          JWT configuration (present only when securityMode is JWT)
/// @param httpProtocol       HTTP protocol mode (H1, H3, BOTH) — default H1
public record AppHttpConfig(boolean enabled,
                            int port,
                            Map<String, ApiKeyEntry> apiKeys,
                            TimeSpan forwardTimeout,
                            int maxRequestSize,
                            SecurityMode securityMode,
                            Option<JwtConfig> jwtConfig,
                            HttpProtocol httpProtocol) {
    public static final int DEFAULT_APP_HTTP_PORT = 8070;

    public static final TimeSpan DEFAULT_FORWARD_TIMEOUT = timeSpan(5).seconds();

    public static final int DEFAULT_MAX_REQUEST_SIZE = 10 * 1024 * 1024;

    public AppHttpConfig {
        apiKeys = Map.copyOf(apiKeys);
        forwardTimeout = normalizeTimeout(forwardTimeout);
        maxRequestSize = normalizeMaxRequestSize(maxRequestSize);
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      TimeSpan forwardTimeout,
                                                      int maxRequestSize,
                                                      SecurityMode securityMode,
                                                      Option<JwtConfig> jwtConfig,
                                                      HttpProtocol httpProtocol) {
        return success(new AppHttpConfig(enabled,
                                         port,
                                         apiKeys,
                                         forwardTimeout,
                                         maxRequestSize,
                                         securityMode,
                                         jwtConfig,
                                         httpProtocol));
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      TimeSpan forwardTimeout,
                                                      int maxRequestSize,
                                                      SecurityMode securityMode,
                                                      Option<JwtConfig> jwtConfig) {
        return success(new AppHttpConfig(enabled,
                                         port,
                                         apiKeys,
                                         forwardTimeout,
                                         maxRequestSize,
                                         securityMode,
                                         jwtConfig,
                                         HttpProtocol.H1));
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      TimeSpan forwardTimeout,
                                                      int maxRequestSize,
                                                      SecurityMode securityMode) {
        return success(new AppHttpConfig(enabled,
                                         port,
                                         apiKeys,
                                         forwardTimeout,
                                         maxRequestSize,
                                         securityMode,
                                         Option.empty(),
                                         HttpProtocol.H1));
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      TimeSpan forwardTimeout,
                                                      int maxRequestSize) {
        return success(new AppHttpConfig(enabled,
                                         port,
                                         apiKeys,
                                         forwardTimeout,
                                         maxRequestSize,
                                         SecurityMode.NONE,
                                         Option.empty(),
                                         HttpProtocol.H1));
    }

    public static AppHttpConfig appHttpConfig() {
        return appHttpConfig(false,
                             DEFAULT_APP_HTTP_PORT,
                             Map.of(),
                             DEFAULT_FORWARD_TIMEOUT,
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.NONE).unwrap();
    }

    public static AppHttpConfig appHttpConfig(boolean enabled) {
        return appHttpConfig(enabled,
                             DEFAULT_APP_HTTP_PORT,
                             Map.of(),
                             DEFAULT_FORWARD_TIMEOUT,
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.NONE).unwrap();
    }

    public static AppHttpConfig appHttpConfig(int port) {
        return appHttpConfig(true, port, Map.of(), DEFAULT_FORWARD_TIMEOUT, DEFAULT_MAX_REQUEST_SIZE, SecurityMode.NONE).unwrap();
    }

    public static AppHttpConfig appHttpConfig(int port, Set<String> apiKeys) {
        var mode = apiKeys.isEmpty()
                  ? SecurityMode.NONE
                  : SecurityMode.API_KEY;
        return appHttpConfig(true,
                             port,
                             wrapSimpleKeys(apiKeys),
                             DEFAULT_FORWARD_TIMEOUT,
                             DEFAULT_MAX_REQUEST_SIZE,
                             mode).unwrap();
    }

    public Set<String> apiKeyValues() {
        return Set.copyOf(apiKeys.keySet());
    }

    public int portFor(int nodeIndex) {
        return port + nodeIndex;
    }

    public boolean securityEnabled() {
        return securityMode != SecurityMode.NONE;
    }

    public AppHttpConfig withEnabled(boolean enabled) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withPort(int port) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withApiKeys(Set<String> apiKeys) {
        var mode = apiKeys.isEmpty()
                  ? securityMode
                  : SecurityMode.API_KEY;
        return appHttpConfig(enabled,
                             port,
                             wrapSimpleKeys(apiKeys),
                             forwardTimeout,
                             maxRequestSize,
                             mode,
                             jwtConfig,
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withApiKeyMap(Map<String, ApiKeyEntry> apiKeys) {
        var mode = apiKeys.isEmpty()
                  ? securityMode
                  : SecurityMode.API_KEY;
        return appHttpConfig(enabled, port, apiKeys, forwardTimeout, maxRequestSize, mode, jwtConfig, httpProtocol).unwrap();
    }

    public AppHttpConfig withForwardTimeout(TimeSpan forwardTimeout) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withMaxRequestSize(int maxRequestSize) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withSecurityMode(SecurityMode securityMode) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withJwtConfig(JwtConfig jwtConfig) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             Option.some(jwtConfig),
                             httpProtocol).unwrap();
    }

    public AppHttpConfig withHttpProtocol(HttpProtocol httpProtocol) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             forwardTimeout,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol).unwrap();
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
