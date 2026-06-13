// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityContextHolder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Tests for the `/api/whoami` response builder. Validates that
/// `StatusRoutes.buildWhoamiResponse()` reads the current `SecurityContext`
/// from the `ScopedValue` binding installed by `AppHttpServer` and renders
/// it as a `WhoamiResponse` payload.
///
/// See `aether/docs/reference/management-api.md` and
/// `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2
/// for the motivating contract.
class StatusRoutesWhoamiTest {

    @Nested
    class WhenAuthenticatedContextBound {
        @Test
        void buildWhoamiResponse_adminApiKey_returnsPrincipalRolesAndAuthorization() {
            var principal = Principal.principal("admin-key", Principal.PrincipalType.API_KEY).unwrap();
            var context = SecurityContext.securityContext(principal,
                                                          Set.of(Role.ADMIN, Role.SERVICE),
                                                          Map.of(),
                                                          AuthorizationRole.ADMIN);

            var response = ScopedValue.where(SecurityContextHolder.scopedValue(), context)
                                                       .call(StatusRoutes::buildWhoamiResponse);

            assertThat(response.authenticated()).isTrue();
            assertThat(response.principal()).isEqualTo("api-key:admin-key");
            assertThat(response.authorizationRole()).isEqualTo("ADMIN");
            assertThat(response.roles()).containsExactly("admin", "service");
        }

        @Test
        void buildWhoamiResponse_userPrincipalWithViewerRole_reflectsAuthorization() {
            var principal = Principal.principal("alice", Principal.PrincipalType.USER).unwrap();
            var context = SecurityContext.securityContext(principal,
                                                          Set.of(Role.USER),
                                                          Map.of(),
                                                          AuthorizationRole.VIEWER);

            var response = ScopedValue.where(SecurityContextHolder.scopedValue(), context)
                                                       .call(StatusRoutes::buildWhoamiResponse);

            assertThat(response.authenticated()).isTrue();
            assertThat(response.principal()).isEqualTo("user:alice");
            assertThat(response.authorizationRole()).isEqualTo("VIEWER");
            assertThat(response.roles()).containsExactly("user");
        }
    }

    @Nested
    class WhenNoContextBound {
        @Test
        void buildWhoamiResponse_anonymous_returnsAnonymousPrincipal() {
            var response = StatusRoutes.buildWhoamiResponse();

            assertThat(response.authenticated()).isFalse();
            assertThat(response.principal()).isEqualTo("anonymous");
            assertThat(response.authorizationRole()).isEqualTo("VIEWER");
            assertThat(response.roles()).isEmpty();
        }
    }
}
