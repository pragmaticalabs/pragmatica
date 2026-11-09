// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.pragmatica.aether.slice.CallerIdentity;
import org.pragmatica.http.routing.security.RequestSecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.aether.http.handler.security.Principal.PrincipalType;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@Codec
public record SecurityContext(Principal principal,
                              Set<Role> roles,
                              Map<String, String> claims,
                              AuthorizationRole authorizationRole) implements RequestSecurityContext, CallerIdentity {
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

    /// The identity an inter-slice call carries for a caller holding `context` (#2157): the context exactly as the edge
    /// validated it when it is authenticated; none for an unauthenticated (anonymous) or absent context, so "no
    /// identity" travels as empty, never as a stand-in principal or a default role.
    public static Option<CallerIdentity> callerIdentity(SecurityContext context) {
        return option(context).filter(SecurityContext::isAuthenticated)
                     .map(CallerIdentity.class::cast);
    }

    /// The authenticated context a caller identity carries (#2157); none for an empty identity, one that is not a
    /// [SecurityContext], or an anonymous one.
    public static Option<SecurityContext> fromCallerIdentity(Option<CallerIdentity> caller) {
        return caller.filter(SecurityContext.class::isInstance)
                     .map(SecurityContext.class::cast)
                     .filter(SecurityContext::isAuthenticated);
    }

    /// The context a receiving slice method is given for a caller identity (#2157): the caller's authenticated context,
    /// or the anonymous context when the caller had no identity, exactly as the edge answers an unauthenticated request.
    public static SecurityContext securityContext(Option<CallerIdentity> caller) {
        return fromCallerIdentity(caller).or(ANONYMOUS_CONTEXT);
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

    /// Claim VALUES are withheld (#2157): a claim may carry a credential or token, and this context now travels on the
    /// invoke message, which is logged. The principal, roles, claim names and authorization role stay readable.
    @Override
    public String toString() {
        return "SecurityContext[principal=" + principal.value()
             + ", roles=" + new TreeSet<>(roles.stream().map(Role::value).toList())
             + ", claims=" + new TreeSet<>(claims.keySet())
             + ", authorizationRole=" + authorizationRole
             + "]";
    }
}
