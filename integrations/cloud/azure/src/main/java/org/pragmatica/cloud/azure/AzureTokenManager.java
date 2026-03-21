/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.cloud.azure;

import org.pragmatica.cloud.azure.api.TokenResponse;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Manages OAuth2 tokens for Azure management and Key Vault scopes.
public class AzureTokenManager {
    private static final String MANAGEMENT_SCOPE = "https://management.azure.com/.default";
    private static final String VAULT_SCOPE = "https://vault.azure.net/.default";
    private static final long REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L;

    private final AzureConfig config;
    private final ConcurrentMap<String, TokenState> tokens = new ConcurrentHashMap<>();

    /// Cached token state.
    record TokenState(String accessToken, long expiresAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis - REFRESH_MARGIN_MILLIS;
        }
    }

    /// Creates a new token manager for the given configuration.
    public static AzureTokenManager azureTokenManager(AzureConfig config) {
        return new AzureTokenManager(config);
    }

    private AzureTokenManager(AzureConfig config) {
        this.config = config;
    }

    /// Gets a valid management plane token, refreshing if necessary.
    public Promise<String> getManagementToken(HttpOperations http, JsonMapper mapper) {
        return getToken(MANAGEMENT_SCOPE, http, mapper);
    }

    /// Gets a valid Key Vault token, refreshing if necessary.
    public Promise<String> getVaultToken(HttpOperations http, JsonMapper mapper) {
        return getToken(VAULT_SCOPE, http, mapper);
    }

    private Promise<String> getToken(String scope, HttpOperations http, JsonMapper mapper) {
        return Option.option(tokens.get(scope))
                     .filter(state -> !state.isExpired())
                     .map(state -> Promise.success(state.accessToken()))
                     .or(() -> refreshToken(scope, http, mapper));
    }

    private Promise<String> refreshToken(String scope, HttpOperations http, JsonMapper mapper) {
        return http.sendString(buildTokenRequest(scope))
                   .flatMap(result -> parseTokenResponse(result, scope, mapper));
    }

    private Promise<String> parseTokenResponse(org.pragmatica.http.HttpResult<String> result, String scope, JsonMapper mapper) {
        if (result.isSuccess()) {
            return mapper.readString(result.body(), TokenResponse.class)
                         .map(token -> cacheToken(scope, token))
                         .async();
        }
        return new AzureError.AuthError("Token request failed with status " + result.statusCode(),
                                        Option.none())
            .promise();
    }

    private String cacheToken(String scope, TokenResponse token) {
        var expiresAt = System.currentTimeMillis() + (long) token.expiresIn() * 1000L;
        tokens.put(scope, new TokenState(token.accessToken(), expiresAt));
        return token.accessToken();
    }

    private HttpRequest buildTokenRequest(String scope) {
        var tokenUrl = "https://login.microsoftonline.com/" + config.tenantId() + "/oauth2/v2.0/token";
        var body = "grant_type=client_credentials"
                   + "&client_id=" + encode(config.clientId())
                   + "&client_secret=" + encode(config.clientSecret())
                   + "&scope=" + encode(scope);
        return HttpRequest.newBuilder()
                          .uri(URI.create(tokenUrl))
                          .POST(BodyPublishers.ofString(body))
                          .header("Content-Type", "application/x-www-form-urlencoded")
                          .build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
