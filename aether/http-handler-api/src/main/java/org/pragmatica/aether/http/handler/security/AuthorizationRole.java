// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import org.pragmatica.serialization.Codec;


/// Authorization roles for management API access.
/// Roles are hierarchical: ADMIN > OPERATOR > VIEWER.
///
/// Used by {@link RoutePermission} to enforce minimum access levels
/// on management API endpoints. Distinct from {@link Role} which is
/// a string-based identity role for authentication context.
@Codec public enum AuthorizationRole {
    ADMIN,
    OPERATOR,
    VIEWER;
    public boolean hasAccess(AuthorizationRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
