// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example.caller.leaf;

import java.util.List;

import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;

/// What the last slice of a Terra call chain saw (#2157): the caller's principal and roles, and the slices passed.
public record Trail(String principal, List<String> roles, String path) {
    public static Trail trail(SecurityContext context, String path) {
        return new Trail(context.principal()
                                .value(),
                         context.roles()
                                .stream()
                                .map(Role::value)
                                .sorted()
                                .toList(),
                         path);
    }
}
