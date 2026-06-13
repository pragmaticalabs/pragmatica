// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextHolderTest {

    @Nested
    class WhenNoContextBound {
        @Test
        void currentContext_returnsEmpty() {
            assertThat(SecurityContextHolder.currentContext().isEmpty()).isTrue();
        }

        @Test
        void isAuthenticated_returnsFalse() {
            assertThat(SecurityContextHolder.isAuthenticated()).isFalse();
        }
    }

    @Nested
    class WhenContextBound {
        @Test
        void currentContext_returnsSecurityContext() {
            var context = SecurityContext.securityContext(
                Principal.principal("test-key", Principal.PrincipalType.API_KEY).unwrap(),
                Set.of(Role.SERVICE),
                Map.of()
            );

            var result = ScopedValue.where(SecurityContextHolder.scopedValue(), context)
                                    .call(() -> SecurityContextHolder.currentContext());

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap().principal().value()).contains("test-key");
        }

        @Test
        void isAuthenticated_returnsTrue() {
            var context = SecurityContext.securityContext(
                Principal.principal("test-key", Principal.PrincipalType.API_KEY).unwrap(),
                Set.of(Role.SERVICE),
                Map.of()
            );

            var authenticated = ScopedValue.where(SecurityContextHolder.scopedValue(), context)
                                           .call(SecurityContextHolder::isAuthenticated);

            assertThat(authenticated).isTrue();
        }

        @Test
        void anonymousContext_isNotAuthenticated() {
            var context = SecurityContext.securityContext();

            var authenticated = ScopedValue.where(SecurityContextHolder.scopedValue(), context)
                                           .call(SecurityContextHolder::isAuthenticated);

            assertThat(authenticated).isFalse();
        }
    }
}
