// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;


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
