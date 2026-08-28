// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec event-stream-namespaces §6.1/§12.2: writes to `system:*` streams over the HTTP surface are
/// rejected with 405 Method Not Allowed regardless of caller role, independent of the role/auth
/// pipeline. The compile-time SPI split already blocks app code; this exercises the HTTP-path guard
/// ([ManagementServer#isSystemStreamWriteOverHttp]) that backs that 405 response.
class SystemStreamWriteGateTest {

    @Nested
    class Rejected {

        @Test
        void pathBased_publish_toSystemNamespace_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish/system/cluster-events/1.0.0")).isTrue();
        }

        @Test
        void pathBased_publishBatch_toSystemNamespace_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish-batch/system/cluster-events/1.0.0")).isTrue();
        }

        @Test
        void pathBased_delete_systemStream_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "DELETE", "/api/v1/streams/delete/system/cluster-events/1.0.0")).isTrue();
        }

        @Test
        void groupCreate_onSystemStream_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/groups/create/system/cluster-events/1.0.0")).isTrue();
        }

        @Test
        void legacyColonForm_systemName_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish/system:cluster-events:1.0.0")).isTrue();
        }

        @Test
        void legacyColonForm_urlEncoded_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish/system%3Acluster-events%3A1.0.0")).isTrue();
        }

        @Test
        void caseInsensitive_namespace_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish/System/audit/1.0.0")).isTrue();
        }
    }

    @Nested
    class Allowed {

        @Test
        void appNamespace_publish_isNotGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish/com.example.app/orders/1.0.0")).isFalse();
        }

        @Test
        void systemNamespace_read_isNotGated() {
            // GET reads of system streams are allowed; the gate is write-only.
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "GET", "/api/v1/streams/events/system/cluster-events/1.0.0")).isFalse();
        }

        @Test
        void appNamespaceContainingSystemSubstring_isNotGated() {
            // "systemic" must not be mistaken for the reserved "system" namespace.
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/publish/systemic/orders/1.0.0")).isFalse();
        }

        @Test
        void nonStreamPath_isNotGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/deploy")).isFalse();
        }
    }
}
