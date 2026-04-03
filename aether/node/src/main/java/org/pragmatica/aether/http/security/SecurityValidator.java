package org.pragmatica.aether.http.security;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.Set;


/// Validates request security based on route policy.
///
/// Extensible interface for different authentication mechanisms.
/// Implementations validate requests and produce {@link SecurityContext}.
public interface SecurityValidator {
    Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy);

    static SecurityValidator apiKeyValidator(Set<String> validKeys) {
        return new ApiKeySecurityValidator(ApiKeySecurityValidator.fromKeySet(validKeys));
    }

    static SecurityValidator apiKeyValidator(Map<String, ApiKeyEntry> keyEntries) {
        return new ApiKeySecurityValidator(keyEntries);
    }

    static SecurityValidator jwtValidator(JwtConfig jwtConfig) {
        return new JwtSecurityValidator(jwtConfig);
    }

    static SecurityValidator noOpValidator() {
        var systemContext = SecurityContext.securityContext(Principal.principal("system",
                                                                                Principal.PrincipalType.SERVICE)
        .unwrap(),
                                                            Set.of(Role.ADMIN, Role.SERVICE),
                                                            Map.of());
        return (_, _) -> Result.success(systemContext);
    }
}
