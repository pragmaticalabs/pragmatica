package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;


/// Configuration for application HTTP server.
///
/// @param enabled            whether the app HTTP server is enabled
/// @param port               base port for app HTTP server (nodes use port, port+1, etc.)
/// @param apiKeys            API key map: raw key string to entry metadata (empty map disables security)
/// @param maxRequestSize     maximum request body size in bytes
/// @param securityMode       authentication mode for app HTTP endpoints (NONE, API_KEY, JWT)
/// @param jwtConfig          JWT configuration (present only when securityMode is JWT)
/// @param httpProtocol       HTTP protocol mode (H1, H3, BOTH) — default H1
public record AppHttpConfig(boolean enabled,
                            int port,
                            Map<String, ApiKeyEntry> apiKeys,
                            int maxRequestSize,
                            SecurityMode securityMode,
                            Option<JwtConfig> jwtConfig,
                            HttpProtocol httpProtocol) {
    public static final int DEFAULT_APP_HTTP_PORT = 8070;

    public static final int DEFAULT_MAX_REQUEST_SIZE = 10 * 1024 * 1024;

    public AppHttpConfig {
        apiKeys = Map.copyOf(apiKeys);
        maxRequestSize = normalizeMaxRequestSize(maxRequestSize);
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      int maxRequestSize,
                                                      SecurityMode securityMode,
                                                      Option<JwtConfig> jwtConfig,
                                                      HttpProtocol httpProtocol) {
        return success(new AppHttpConfig(enabled, port, apiKeys, maxRequestSize, securityMode, jwtConfig, httpProtocol));
    }

    public static AppHttpConfig appHttpConfig() {
        return appHttpConfig(false,
                             DEFAULT_APP_HTTP_PORT,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.NONE,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    public static AppHttpConfig appHttpConfig(boolean enabled) {
        return appHttpConfig(enabled,
                             DEFAULT_APP_HTTP_PORT,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.NONE,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    public static AppHttpConfig appHttpConfig(int port) {
        return appHttpConfig(true,
                             port,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.NONE,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    public static AppHttpConfig appHttpConfig(int port, Set<String> apiKeys) {
        var mode = apiKeys.isEmpty()
                  ? SecurityMode.NONE
                  : SecurityMode.API_KEY;
        return appHttpConfig(true,
                             port,
                             wrapSimpleKeys(apiKeys),
                             DEFAULT_MAX_REQUEST_SIZE,
                             mode,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
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

    private static int normalizeMaxRequestSize(int maxRequestSize) {
        return maxRequestSize > 0
              ? maxRequestSize
              : DEFAULT_MAX_REQUEST_SIZE;
    }

    private static Map<String, ApiKeyEntry> wrapSimpleKeys(Set<String> keys) {
        var map = new HashMap<String, ApiKeyEntry>();
        keys.forEach(key -> map.put(key, ApiKeyEntry.defaultEntry(key)));
        return Map.copyOf(map);
    }
}
