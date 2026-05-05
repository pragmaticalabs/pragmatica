// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Spec event-stream-namespaces §6.1, §12.2 — HTTP writes to `/api/streams/system/...` return
/// 405 regardless of authenticated role. This test pins the static path-classification helper
/// that drives the short-circuit in `ManagementServer.handleRequest` and `dispatchManagementForward`.
///
/// Package-private access via `ManagementServerImpl.isSystemNamespaceWrite` would be cleanest;
/// the helper lives on `ManagementServerImpl` and exposed package-private. We use the package
/// to access it.
class ManagementServerSystemNamespaceTest {

    @Nested
    class WritePathsAreBlocked {
        @Test
        void postPublishToSystem_isBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "POST", "/api/streams/system/cluster-events/1.0.0/publish")).isTrue();
        }

        @Test
        void postPublishBatchToSystem_isBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "POST", "/api/streams/system/cluster-events/1.0.0/publish-batch")).isTrue();
        }

        @Test
        void postGroupCreateOnSystem_isBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "POST", "/api/streams/system/cluster-events/1.0.0/groups")).isTrue();
        }

        @Test
        void deleteSystemVersion_isBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "DELETE", "/api/streams/system/cluster-events/1.0.0")).isTrue();
        }

        @Test
        void deleteGroupUnderSystem_isBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "DELETE", "/api/streams/system/cluster-events/1.0.0/groups/g1")).isTrue();
        }

        @Test
        void putAnythingToSystem_isBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "PUT", "/api/streams/system/whatever/1.0.0")).isTrue();
        }
    }

    @Nested
    class ReadsAndNonSystemAreAllowed {
        @Test
        void getOnSystem_isNotBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "GET", "/api/streams/system/cluster-events/1.0.0")).isFalse();
        }

        @Test
        void getTailOnSystem_isNotBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "GET", "/api/streams/system/cluster-events/1.0.0/tail")).isFalse();
        }

        @Test
        void postPublishToAppNamespace_isNotBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "POST", "/api/streams/com.example.app/orders/1.0.0/publish")).isFalse();
        }

        @Test
        void postToSystemSimilarPrefix_isNotBlocked() {
            // `system-events` is a different namespace from `system` — must NOT be blocked.
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "POST", "/api/streams/system-events/foo/1.0.0/publish")).isFalse();
        }

        @Test
        void writeOutsideStreams_isNotBlocked() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite("POST", "/api/blueprint")).isFalse();
            assertThat(ManagementServerImpl.isSystemNamespaceWrite("DELETE", "/api/config/foo")).isFalse();
        }

        @Test
        void caseInsensitiveMethod_recognized() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "post", "/api/streams/system/cluster-events/1.0.0/publish")).isTrue();
        }

        @Test
        void queryStringIgnoredOnPath() {
            assertThat(ManagementServerImpl.isSystemNamespaceWrite(
                "POST", "/api/streams/system/cluster-events/1.0.0/publish?dryRun=true")).isTrue();
        }
    }
}
