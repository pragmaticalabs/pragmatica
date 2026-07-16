// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.pragmatica.http.routing.security.RequestSecurityContext;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.aether.http.handler.security.Principal.PrincipalType;
import static org.pragmatica.lang.Result.success;


@Codec
public record SecurityContext(Principal principal,
                              Set<Role> roles,
                              Map<String, String> claims,
                              AuthorizationRole authorizationRole) implements RequestSecurityContext {
    private static final SecurityContext ANONYMOUS_CONTEXT = securityContext(Principal.ANONYMOUS,
                                                                             Set.of(),
                                                                             Map.of(),
                                                                             AuthorizationRole.VIEWER);

    public SecurityContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(authorizationRole, "authorizationRole");
    }

    public static SecurityContext securityContext() {
        return ANONYMOUS_CONTEXT;
    }

    public static Result<SecurityContext> securityContext(String keyName) {
        return Principal.principal(keyName, PrincipalType.API_KEY).map(p -> securityContext(p,
                                                                                            Set.of(Role.SERVICE),
                                                                                            Map.of()));
    }

    public static Result<SecurityContext> securityContext(String keyName, Set<Role> roles) {
        return Principal.principal(keyName, PrincipalType.API_KEY).map(p -> securityContext(p, roles, Map.of()));
    }

    public static Result<SecurityContext> securityContext(String keyName,
                                                          Set<Role> roles,
                                                          AuthorizationRole authorizationRole) {
        return Principal.principal(keyName, PrincipalType.API_KEY).map(p -> securityContext(p,
                                                                                            roles,
                                                                                            Map.of(),
                                                                                            authorizationRole));
    }

    public static Result<SecurityContext> securityContext(String subject, Set<Role> roles, Map<String, String> claims) {
        return Principal.principal(subject, PrincipalType.USER).map(p -> securityContext(p, roles, claims));
    }

    public static SecurityContext securityContext(Principal principal, Set<Role> roles, Map<String, String> claims) {
        return securityContext(principal, roles, claims, AuthorizationRole.ADMIN);
    }

    public static SecurityContext securityContext(Principal principal,
                                                  Set<Role> roles,
                                                  Map<String, String> claims,
                                                  AuthorizationRole authorizationRole) {
        return Result.all(success(principal),
                          success(roles),
                          success(claims),
                          success(authorizationRole))
                     .map(SecurityContext::new)
                     .unwrap();
    }

    public boolean isAuthenticated() {
        return ! principal.isAnonymous();
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean hasRole(String roleName) {
        return Role.role(roleName)
                   .map(roles::contains)
                   .or(false);
    }

    public boolean hasAnyRole(Set<Role> requiredRoles) {
        return requiredRoles.stream()
                            .anyMatch(roles::contains);
    }

    public String claim(String key) {
        return claims.get(key);
    }
}
