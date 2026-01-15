package org.pragmatica.aether.http.security;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates API key authentication.
 * <p>
 * Checks X-API-Key header against configured valid keys.
 */
class ApiKeySecurityValidator implements SecurityValidator {
    private static final String API_KEY_HEADER = "X-API-Key";
    private final Set<String> validKeys;

    ApiKeySecurityValidator(Set<String> validKeys) {
        this.validKeys = Set.copyOf(validKeys);
    }

    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, RouteSecurityPolicy policy) {
        return switch (policy) {
            case RouteSecurityPolicy.Public() -> Result.success(SecurityContext.anonymous());
            case RouteSecurityPolicy.ApiKeyRequired() -> validateApiKey(request);
        };
    }

    private Result<SecurityContext> validateApiKey(HttpRequestContext request) {
        return extractApiKey(request.headers())
                            .toResult(SecurityError.MISSING_API_KEY)
                            .flatMap(this::checkApiKey);
    }

    private Result<SecurityContext> checkApiKey(String apiKey) {
        return validKeys.contains(apiKey)
               ? Result.success(SecurityContext.forApiKey(apiKey))
               : SecurityError.INVALID_API_KEY.result();
    }

    private Option<String> extractApiKey(Map<String, List<String>> headers) {
        // Try case-sensitive first
        var values = headers.get(API_KEY_HEADER);
        if (values != null && !values.isEmpty()) {
            return Option.option(values.getFirst());
        }
        // Fall back to case-insensitive search
        for (var entry : headers.entrySet()) {
            if (API_KEY_HEADER.equalsIgnoreCase(entry.getKey())) {
                var headerValues = entry.getValue();
                if (headerValues != null && !headerValues.isEmpty()) {
                    return Option.option(headerValues.getFirst());
                }
            }
        }
        return Option.none();
    }
}
