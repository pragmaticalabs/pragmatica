// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"}) class KvStoreApiKeyValidator implements SecurityValidator {
    private static final Logger log = LoggerFactory.getLogger(KvStoreApiKeyValidator.class);

    private static final String API_KEY_HEADER = "X-API-Key";

    private final SecurityValidator configValidator;
    private final Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier;

    KvStoreApiKeyValidator(SecurityValidator configValidator,
                           Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        this.configValidator = configValidator;
        this.kvStoreSupplier = kvStoreSupplier;
    }

    @Override public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return switch (policy){
            case SecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            case SecurityPolicy.BearerTokenRequired() -> Result.success(SecurityContext.securityContext());
            default -> validateApiKey(request);
        };
    }

    private Result<SecurityContext> validateApiKey(HttpRequestContext request) {
        var configResult = configValidator.validate(request, SecurityPolicy.apiKeyRequired());
        if (configResult.isSuccess()) {return configResult;}
        var apiKeyOpt = extractApiKey(request.headers());
        if (apiKeyOpt.isEmpty()) {return hasConfiguredCredentials()
                                        ? SecurityError.MISSING_API_KEY.result()
                                        : Result.success(SecurityContext.securityContext());}
        return checkKvStoreKey(apiKeyOpt.unwrap());
    }

    @Override public boolean hasConfiguredCredentials() {
        return configValidator.hasConfiguredCredentials() || hasKvStoreKeys();
    }

    private boolean hasKvStoreKeys() {
        var kvStore = kvStoreSupplier.get();
        if (kvStore == null) {return false;}
        var found = new AtomicBoolean(false);
        kvStore.forEach(ApiKeyKey.class, ApiKeyValue.class, (_, _) -> found.set(true));
        return found.get();
    }

    private Result<SecurityContext> checkKvStoreKey(String apiKey) {
        var candidateHash = hashKey(apiKey);
        var kvStore = kvStoreSupplier.get();
        if (kvStore == null) {return SecurityError.INVALID_API_KEY.result();}
        var candidateHashBytes = candidateHash.getBytes(StandardCharsets.UTF_8);
        var match = new AtomicReference<ApiKeyValue>();
        kvStore.forEach(ApiKeyKey.class,
                        ApiKeyValue.class,
                        (_, keyValue) -> {
                            if (match.get() != null) {return;}
                            if (!keyValue.isValidForAuth()) {return;}
                            if (MessageDigest.isEqual(candidateHashBytes,
                                                      keyValue.keyHash().getBytes(StandardCharsets.UTF_8))) {match.set(keyValue);}
                        });
        var matched = match.get();
        return matched == null
              ? SecurityError.INVALID_API_KEY.result()
              : buildContext(matched);
    }

    private static Result<SecurityContext> buildContext(ApiKeyValue keyValue) {
        var role = parseAuthorizationRole(keyValue.authorizationRole());
        return SecurityContext.securityContext("api-key:" + keyValue.keyId(), Set.of(Role.ADMIN), role);
    }

    private static AuthorizationRole parseAuthorizationRole(String raw) {
        if (raw == null || raw.isBlank()) {return AuthorizationRole.VIEWER;}
        return switch (raw.toUpperCase()){
            case "ADMIN" -> AuthorizationRole.ADMIN;
            case "OPERATOR" -> AuthorizationRole.OPERATOR;
            case "VIEWER" -> AuthorizationRole.VIEWER;
            default -> {
                log.warn("Unknown authorization role '{}' on stored API key; defaulting to VIEWER", raw);
                yield AuthorizationRole.VIEWER;
            }
        };
    }

    private Option<String> extractApiKey(Map<String, List<String>> headers) {
        return extractCaseSensitive(headers).orElse(() -> extractCaseInsensitive(headers));
    }

    private static Option<String> extractCaseSensitive(Map<String, List<String>> headers) {
        return Option.option(headers.get(API_KEY_HEADER)).filter(values -> !values.isEmpty())
                            .map(List::getFirst);
    }

    private static Option<String> extractCaseInsensitive(Map<String, List<String>> headers) {
        var value = headers.entrySet().stream()
                                    .filter(e -> API_KEY_HEADER.equalsIgnoreCase(e.getKey()))
                                    .map(Map.Entry::getValue)
                                    .filter(values -> values != null && !values.isEmpty())
                                    .map(List::getFirst)
                                    .findFirst();
        return Option.from(value);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-EX-01"}) static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
