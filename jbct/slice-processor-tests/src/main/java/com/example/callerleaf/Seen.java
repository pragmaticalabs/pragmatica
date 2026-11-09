// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.callerleaf;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;


/// What reached a caller-taking slice method (#2157): the principal and roles it was given, and the path the call took.
public record Seen(String principal, List<String> roles, String trail) {
    public static Seen seen(SecurityContext context, String trail) {
        return new Seen(context.principal().value(),
                        sorted(context.roles()),
                        trail);
    }

    public static Seen seen(Principal principal, String trail) {
        return new Seen(principal.value(), List.of(), trail);
    }

    private static List<String> sorted(Set<Role> roles) {
        return roles.stream()
                    .map(Role::value)
                    .sorted()
                    .toList();
    }
}
