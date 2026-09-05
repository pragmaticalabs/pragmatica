// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class ApiKeySecurityValidator implements SecurityValidator {
    private static final Logger log = LoggerFactory.getLogger(ApiKeySecurityValidator.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final Map<String, ApiKeyEntry> keyEntries;

    ApiKeySecurityValidator(Map<String, ApiKeyEntry> keyEntries) {
        var hashedEntries = new HashMap<String, ApiKeyEntry>();

        keyEntries.forEach((key, entry) -> hashedEntries.put(hashKey(key), entry));
        this.keyEntries = Map.copyOf(hashedEntries);
    }

    static Map<String, ApiKeyEntry> fromKeySet(Set<String> validKeys) {
        var entries = new HashMap<String, ApiKeyEntry>();

        validKeys.forEach(key -> entries.put(key, ApiKeyEntry.defaultEntry(key)));

        return entries;
    }

    @Override
    public boolean hasConfiguredCredentials() {
        return ! keyEntries.isEmpty();
    }

    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return switch (policy) {
            case SecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            case SecurityPolicy.ApiKeyRequired() -> validateApiKey(request);
            case SecurityPolicy.Authenticated() -> validateApiKey(request);
            case SecurityPolicy.RoleRequired _ -> validateApiKey(request);
            case SecurityPolicy.BearerTokenRequired() -> Result.success(SecurityContext.securityContext());
            case SecurityPolicy.Unspecified() -> SecurityError.UNRESOLVED_POLICY.result();
            case SecurityPolicy.unused() -> SecurityError.UNRESOLVED_POLICY.result();
        };
    }

    private Result<SecurityContext> validateApiKey(HttpRequestContext request) {
        return extractApiKey(request.headers()).toResult(SecurityError.MISSING_API_KEY)
                            .flatMap(this::checkApiKey);
    }

    private Result<SecurityContext> checkApiKey(String apiKey) {
        var candidateHash = hashKey(apiKey).getBytes(StandardCharsets.UTF_8);

        return Option.from(keyEntries.entrySet()
                                     .stream()
                                     .filter(e -> MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.UTF_8),
                                                                        candidateHash))
                                     .map(Map.Entry::getValue)
                                     .findFirst())
                     .toResult(SecurityError.INVALID_API_KEY)
                     .flatMap(ApiKeySecurityValidator::toSecurityContext);
    }

    private static Result<SecurityContext> toSecurityContext(ApiKeyEntry entry) {
        var roles = entry.roles().stream().map(Role::role).flatMap(r -> r.stream()).collect(Collectors.toSet());
        var authRole = parseAuthorizationRole(entry.authorizationRole());

        return SecurityContext.securityContext(entry.name(), roles, authRole);
    }

    private static AuthorizationRole parseAuthorizationRole(String value) {
        return switch (value) {
            case "ADMIN" -> AuthorizationRole.ADMIN;
            case "OPERATOR" -> AuthorizationRole.OPERATOR;
            case "VIEWER" -> AuthorizationRole.VIEWER;
            default -> {
                log.warn("Unknown authorization role '{}', defaulting to VIEWER", value);
                yield AuthorizationRole.VIEWER;
            }
        };
    }

    private Option<String> extractApiKey(Map<String, List<String>> headers) {
        return extractCaseSensitive(headers).orElse(() -> extractCaseInsensitive(headers));
    }

    private static Option<String> extractCaseSensitive(Map<String, List<String>> headers) {
        return Option.option(headers.get(API_KEY_HEADER))
                     .filter(values -> !values.isEmpty())
                     .map(List::getFirst);
    }

    private static Option<String> extractCaseInsensitive(Map<String, List<String>> headers) {
        var value = headers.entrySet()
                           .stream()
                           .filter(e -> API_KEY_HEADER.equalsIgnoreCase(e.getKey()))
                           .map(Map.Entry::getValue)
                           .filter(values -> values != null && !values.isEmpty())
                           .map(List::getFirst)
                           .findFirst();

        return Option.from(value);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-EX-01"})
    private static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
