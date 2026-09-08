// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import org.pragmatica.serialization.Codec;


@Codec
public enum AuthorizationRole {
    ADMIN,
    OPERATOR,
    VIEWER,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Grants nothing and satisfies nothing -- see hasAccess, which special-cases it on BOTH sides.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN;
    /// Privilege is ordinal order: ADMIN(0) outranks OPERATOR(1) outranks VIEWER(2).
    ///
    /// UNKNOWN is checked on BOTH sides before that comparison, and the second check is the one that
    /// matters (#964). As the caller it denies anyway, since its ordinal is the highest. As the
    /// REQUIREMENT it would satisfy every role -- `ADMIN.ordinal() <= UNKNOWN.ordinal()` is true, and
    /// so is every other -- so a route whose required role arrived from a peer this node cannot read
    /// would become open to everyone. That is a fail-OPEN substitution introduced by a robustness fix,
    /// which is precisely what the sentinel exists to prevent, so it is refused explicitly rather than
    /// left to ordinal arithmetic.
    public boolean hasAccess(AuthorizationRole required) {
        if (this == UNKNOWN || required == UNKNOWN) {
            return false;
        }

        return this.ordinal() <= required.ordinal();
    }
}
