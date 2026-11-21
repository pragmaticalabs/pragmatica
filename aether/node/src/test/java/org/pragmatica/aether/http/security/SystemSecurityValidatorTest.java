// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class SystemSecurityValidatorTest {
    @Test
    void validate_returnsSystemContext_forNoOpValidator() {
        var validator = SecurityValidator.permitAllValidator();
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test");

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .map(Authenticated::context)
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isService()).isTrue();
                     assertThat(context.principal().value()).isEqualTo("service:system");
                     assertThat(context.hasRole(Role.ADMIN)).isTrue();
                     assertThat(context.hasRole(Role.SERVICE)).isTrue();
                 });
    }

    /// The ADMIN role permitAll hands out matched no credential, so it mints no grant. Mutation: minting from the
    /// system context turns this red. The control beside it shows a real ADMIN credential does mint on this classpath,
    /// so "none" here is the validator's answer and not a mint that cannot happen.
    @Test
    void permitAll_mintsNoGrant_althoughItHandsOutAdmin() {
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test-3303");

        SecurityValidator.permitAllValidator()
                         .validate(request, SecurityPolicy.apiKeyRequired())
                         .onFailureRun(() -> fail("Expected success"))
                         .onSuccess(authenticated -> assertThat(authenticated.grant().isEmpty()).as("permitAll minted a grant")
                                                                                                 .isTrue());
    }

    @Test
    void control_aRealAdminCredentialMintsALiveGrant() {
        var minted = new boolean[1];

        TestAdminGrants.withAdminGrant(grant -> minted[0] = grant.live());

        assertThat(minted[0]).as("the ADMIN test credential minted a live grant").isTrue();
    }
}
