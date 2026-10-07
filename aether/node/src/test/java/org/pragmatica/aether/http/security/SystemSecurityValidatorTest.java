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
        var request = org.pragmatica.aether.http.handler.HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test");

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isService()).isTrue();
                     assertThat(context.principal().value()).isEqualTo("service:system");
                     assertThat(context.hasRole(Role.ADMIN)).isTrue();
                     assertThat(context.hasRole(Role.SERVICE)).isTrue();
                 });
    }

}
