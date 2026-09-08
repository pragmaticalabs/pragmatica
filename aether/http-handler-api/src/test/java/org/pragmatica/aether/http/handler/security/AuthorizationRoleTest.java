// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.ADMIN;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.OPERATOR;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.UNKNOWN;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.VIEWER;

class AuthorizationRoleTest {
    @Nested
    class AdminAccess {
        @Test
        void admin_hasAccessToAdminRoutes() {
            assertThat(ADMIN.hasAccess(ADMIN)).isTrue();
        }

        @Test
        void admin_hasAccessToOperatorRoutes() {
            assertThat(ADMIN.hasAccess(OPERATOR)).isTrue();
        }

        @Test
        void admin_hasAccessToViewerRoutes() {
            assertThat(ADMIN.hasAccess(VIEWER)).isTrue();
        }
    }

    @Nested
    class OperatorAccess {
        @Test
        void operator_noAccessToAdminRoutes() {
            assertThat(OPERATOR.hasAccess(ADMIN)).isFalse();
        }

        @Test
        void operator_hasAccessToOperatorRoutes() {
            assertThat(OPERATOR.hasAccess(OPERATOR)).isTrue();
        }

        @Test
        void operator_hasAccessToViewerRoutes() {
            assertThat(OPERATOR.hasAccess(VIEWER)).isTrue();
        }
    }

    @Nested
    class ViewerAccess {
        @Test
        void viewer_noAccessToAdminRoutes() {
            assertThat(VIEWER.hasAccess(ADMIN)).isFalse();
        }

        @Test
        void viewer_noAccessToOperatorRoutes() {
            assertThat(VIEWER.hasAccess(OPERATOR)).isFalse();
        }

        @Test
        void viewer_hasAccessToViewerRoutes() {
            assertThat(VIEWER.hasAccess(VIEWER)).isTrue();
        }
    }

    /// #964. `hasAccess` is `this.ordinal() <= required.ordinal()`, and UNKNOWN is appended LAST, so it
    /// carries the HIGHEST ordinal. That makes the caller side deny by accident and the REQUIREMENT
    /// side grant EVERYTHING by accident — `ADMIN.ordinal() <= UNKNOWN.ordinal()` is true, and so is
    /// every other role's. A route whose required role arrived from a peer this node cannot read would
    /// have become open to all comers: a fail-open introduced by a robustness fix.
    ///
    /// The two nested classes below are deliberately separate. The caller-side case passes even with
    /// the guard removed (ordinal arithmetic already denies), so pairing them is what distinguishes
    /// "the guard works" from "the ordinals happen to line up".
    @Nested
    class UnknownRoleIsRefusedAsCaller {
        @Test
        void unknown_hasNoAccessToAdminRoutes() {
            assertThat(UNKNOWN.hasAccess(ADMIN)).isFalse();
        }

        @Test
        void unknown_hasNoAccessToOperatorRoutes() {
            assertThat(UNKNOWN.hasAccess(OPERATOR)).isFalse();
        }

        @Test
        void unknown_hasNoAccessToViewerRoutes() {
            assertThat(UNKNOWN.hasAccess(VIEWER)).isFalse();
        }

        @Test
        void unknown_hasNoAccessEvenToAnUnknownRequirement() {
            assertThat(UNKNOWN.hasAccess(UNKNOWN)).isFalse();
        }
    }

    /// The half that reddens when the guard is removed: without it, every one of these is `true`.
    @Nested
    class UnknownRequirementGrantsNobody {
        @Test
        void admin_isRefusedByAnUnreadableRequirement() {
            assertThat(ADMIN.hasAccess(UNKNOWN)).isFalse();
        }

        @Test
        void operator_isRefusedByAnUnreadableRequirement() {
            assertThat(OPERATOR.hasAccess(UNKNOWN)).isFalse();
        }

        @Test
        void viewer_isRefusedByAnUnreadableRequirement() {
            assertThat(VIEWER.hasAccess(UNKNOWN)).isFalse();
        }
    }
}
