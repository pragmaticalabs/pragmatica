package org.pragmatica.aether.http.security;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.Set;

/// Validates request security based on route policy.
///
/// Extensible interface for different authentication mechanisms.
/// Implementations validate requests and produce {@link SecurityContext}.
public interface SecurityValidator {
    /// Validate request against security policy.
    ///
    /// @param request the HTTP request context
    /// @param policy  the route's security policy
    /// @return Result containing SecurityContext on success, or failure with SecurityError
    Result<SecurityContext> validate(HttpRequestContext request, RouteSecurityPolicy policy);

    /// Create API key validator with given valid keys.
    ///
    /// @param validKeys set of valid API key values
    /// @return SecurityValidator for API key authentication
    static SecurityValidator apiKeyValidator(Set<String> validKeys) {
        return new ApiKeySecurityValidator(ApiKeySecurityValidator.fromKeySet(validKeys));
    }

    /// Create API key validator with named key entries.
    ///
    /// @param keyEntries map of raw API key to entry metadata
    /// @return SecurityValidator for API key authentication with custom roles
    static SecurityValidator apiKeyValidator(Map<String, ApiKeyEntry> keyEntries) {
        return new ApiKeySecurityValidator(keyEntries);
    }

    /// Create a no-op validator that allows all requests (for disabled security).
    /// Returns a system principal with full access — used when security is disabled.
    ///
    /// @return SecurityValidator that always returns system context
    static SecurityValidator noOpValidator() {
        var systemContext = SecurityContext.securityContext(Principal.principal("system",
                                                                                Principal.PrincipalType.SERVICE)
                                                                     .unwrap(),
                                                            Set.of(Role.ADMIN, Role.SERVICE),
                                                            Map.of());
        return (_, _) -> Result.success(systemContext);
    }
}
