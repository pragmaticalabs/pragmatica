// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class AuditLogTest {

    @Test
    void authSuccess_doesNotThrow_withValidParams() {
        assertThatCode(() -> AuditLog.authSuccess("req-1", "api-key:admin", "GET", "/api/v1/test"))
            .doesNotThrowAnyException();
    }

    @Test
    void authFailure_doesNotThrow_withValidParams() {
        assertThatCode(() -> AuditLog.authFailure("req-2", "Invalid API key", "POST", "/api/v1/data"))
            .doesNotThrowAnyException();
    }

    @Test
    void managementAccess_doesNotThrow() {
        assertThatCode(() -> AuditLog.managementAccess("req-3", "service:system", "GET", "/management/health"))
            .doesNotThrowAnyException();
    }

    @Test
    void wsAuthSuccess_doesNotThrow() {
        assertThatCode(() -> AuditLog.wsAuthSuccess("session-1", "api-key:gateway"))
            .doesNotThrowAnyException();
    }

    @Test
    void wsAuthFailure_doesNotThrow() {
        assertThatCode(() -> AuditLog.wsAuthFailure("session-2", "Missing credentials"))
            .doesNotThrowAnyException();
    }

    @Test
    void authSuccess_doesNotThrow_withNullPrincipal() {
        assertThatCode(() -> AuditLog.authSuccess("req-4", null, "GET", "/api/v1/test"))
            .doesNotThrowAnyException();
    }

    @Test
    void authFailure_doesNotThrow_withNullReason() {
        assertThatCode(() -> AuditLog.authFailure("req-5", null, "GET", "/api/v1/test"))
            .doesNotThrowAnyException();
    }

    @Test
    void managementAccess_doesNotThrow_withNullPrincipal() {
        assertThatCode(() -> AuditLog.managementAccess("req-6", null, "GET", "/management/status"))
            .doesNotThrowAnyException();
    }
}
