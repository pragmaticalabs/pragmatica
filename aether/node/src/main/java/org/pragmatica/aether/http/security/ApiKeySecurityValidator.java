package org.pragmatica.aether.http.security;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/// Validates API key authentication.
///
/// Checks X-API-Key header against configured valid keys.
/// Stores SHA-256 hashes of keys — raw key values are never held in memory.
class ApiKeySecurityValidator implements SecurityValidator {
    private static final String API_KEY_HEADER = "X-API-Key";
    private final Map<String, ApiKeyEntry> keyEntries;

    ApiKeySecurityValidator(Map<String, ApiKeyEntry> keyEntries) {
        var hashedEntries = new HashMap<String, ApiKeyEntry>();
        keyEntries.forEach((key, entry) -> hashedEntries.put(hashKey(key), entry));
        this.keyEntries = Map.copyOf(hashedEntries);
    }

    /// Convert a simple key set to key entries (backward compatibility).
    static Map<String, ApiKeyEntry> fromKeySet(Set<String> validKeys) {
        var entries = new HashMap<String, ApiKeyEntry>();
        validKeys.forEach(key -> entries.put(key, ApiKeyEntry.defaultEntry(key)));
        return entries;
    }

    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, RouteSecurityPolicy policy) {
        return switch (policy) {
            case RouteSecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            case RouteSecurityPolicy.ApiKeyRequired() -> validateApiKey(request);
            default -> Result.success(SecurityContext.securityContext());
        };
    }

    private Result<SecurityContext> validateApiKey(HttpRequestContext request) {
        return extractApiKey(request.headers())
            .toResult(SecurityError.MISSING_API_KEY)
            .flatMap(this::checkApiKey);
    }

    private Result<SecurityContext> checkApiKey(String apiKey) {
        var hash = hashKey(apiKey);
        return Option.option(keyEntries.get(hash))
                     .toResult(SecurityError.INVALID_API_KEY)
                     .flatMap(ApiKeySecurityValidator::toSecurityContext);
    }

    private static Result<SecurityContext> toSecurityContext(ApiKeyEntry entry) {
        var roles = entry.roles().stream()
                         .map(Role::role)
                         .flatMap(r -> r.stream())
                         .collect(Collectors.toSet());
        return SecurityContext.securityContext(entry.name(), roles);
    }

    private Option<String> extractApiKey(Map<String, List<String>> headers) {
        return extractCaseSensitive(headers)
            .orElse(() -> extractCaseInsensitive(headers));
    }

    private static Option<String> extractCaseSensitive(Map<String, List<String>> headers) {
        return Option.option(headers.get(API_KEY_HEADER))
                     .filter(values -> !values.isEmpty())
                     .map(List::getFirst);
    }

    private static Option<String> extractCaseInsensitive(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                      .filter(e -> API_KEY_HEADER.equalsIgnoreCase(e.getKey()))
                      .map(Map.Entry::getValue)
                      .filter(values -> values != null && !values.isEmpty())
                      .map(List::getFirst)
                      .findFirst()
                      .map(Option::option)
                      .orElse(Option.none());
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
