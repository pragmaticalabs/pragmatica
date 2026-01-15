package org.pragmatica.aether.http.security;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Result;

import java.util.Set;

/**
 * Validates request security based on route policy.
 * <p>
 * Extensible interface for different authentication mechanisms.
 * Implementations validate requests and produce {@link SecurityContext}.
 */
public interface SecurityValidator {
    /**
     * Validate request against security policy.
     *
     * @param request the HTTP request context
     * @param policy  the route's security policy
     * @return Result containing SecurityContext on success, or failure with SecurityError
     */
    Result<SecurityContext> validate(HttpRequestContext request, RouteSecurityPolicy policy);

    /**
     * Create API key validator with given valid keys.
     *
     * @param validKeys set of valid API key values
     * @return SecurityValidator for API key authentication
     */
    static SecurityValidator apiKeyValidator(Set<String> validKeys) {
        return new ApiKeySecurityValidator(validKeys);
    }

    /**
     * Create a no-op validator that allows all requests (for disabled security).
     *
     * @return SecurityValidator that always returns anonymous context
     */
    static SecurityValidator noOpValidator() {
        return (_, _) -> Result.success(SecurityContext.anonymous());
    }
}
