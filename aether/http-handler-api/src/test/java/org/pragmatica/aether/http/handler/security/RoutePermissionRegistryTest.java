// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;

class RoutePermissionRegistryTest {

    @Nested
    class GetRequests {
        @Test
        void resolve_allAuthenticated_forGetStatus() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/nodes/status")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetNodes() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/nodes")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetSlices() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/slices")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetMetrics() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/metrics")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetBlueprints() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/blueprints")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetCanaries() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/canaries")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetSchemaStatus() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/schema/status")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forGetTraces() {
            assertThat(RoutePermissionRegistry.resolve("GET", "/api/v1/traces")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forHeadMethod() {
            assertThat(RoutePermissionRegistry.resolve("HEAD", "/api/v1/nodes/status")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forOptionsMethod() {
            assertThat(RoutePermissionRegistry.resolve("OPTIONS", "/api/v1/nodes/status")).isEqualTo(ALL_AUTHENTICATED);
        }

        @Test
        void resolve_allAuthenticated_forCaseInsensitiveGet() {
            assertThat(RoutePermissionRegistry.resolve("get", "/api/v1/nodes/status")).isEqualTo(ALL_AUTHENTICATED);
        }
    }

    @Nested
    class AdminOnlyMutations {
        @Test
        void resolve_adminOnly_forBlueprintPost() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/blueprints")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forBlueprintDelete() {
            assertThat(RoutePermissionRegistry.resolve("DELETE", "/api/v1/blueprints/some-id")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forNodeShutdown() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/nodes/shutdown/node-1")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forBackupRestore() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/backups/restore")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forLogLevelSet() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/logging/levels")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forLogLevelDelete() {
            assertThat(RoutePermissionRegistry.resolve("DELETE", "/api/v1/logging/levels/some.logger")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forObservabilityDepthSet() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/observability/depth")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forObservabilityDepthDelete() {
            assertThat(RoutePermissionRegistry.resolve("DELETE", "/api/v1/observability/depth/artifact/method")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forUnknownPostEndpoint() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/unknown-endpoint")).isEqualTo(ADMIN_ONLY);
        }
    }

    @Nested
    class OperatorMutations {
        @Test
        void resolve_operatorAndAbove_forNodeDrain() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/nodes/drain/node-1")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forNodeActivate() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/nodes/activate/node-1")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forSchemaMigrate() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/schema/migrate/mydb")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forSchemaRetry() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/schema/retry/mydb")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forCanaryStart() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/canary/start")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forCanaryPromote() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/canary/abc/promote")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forCanaryRollback() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/canary/abc/rollback")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forBlueGreenDeploy() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/blue-green/deploy")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forBlueGreenSwitch() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/blue-green/abc/switch")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forRollingUpdateStart() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/rolling-update/start")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forAbTestCreate() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/ab-tests/create")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forBackupCreate() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/backups")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forScale() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/scale")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forScheduledTaskAction() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/scheduled-tasks/trigger/section/artifact/method")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forControllerConfig() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/controller/config")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forControllerEvaluate() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/controller/evaluate")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forThresholdSet() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/thresholds")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forThresholdDelete() {
            assertThat(RoutePermissionRegistry.resolve("DELETE", "/api/v1/thresholds/cpu.usage")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forAlertsClear() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/alerts/clear")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forConfigSet() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/config")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forConfigDelete() {
            assertThat(RoutePermissionRegistry.resolve("DELETE", "/api/v1/config/some-key")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_operatorAndAbove_forRepositoryPut() {
            assertThat(RoutePermissionRegistry.resolve("PUT", "/repository/org/example/my-artifact/1.0.0/my-artifact-1.0.0.jar")).isEqualTo(OPERATOR_AND_ABOVE);
        }
    }

    @Nested
    class AdminOverrides {
        @Test
        void resolve_operatorAndAbove_forBlueprintDeploy() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/blueprints/deploy")).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void resolve_allAuthenticated_forBlueprintValidate() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/blueprints/validate")).isEqualTo(ALL_AUTHENTICATED);
        }
    }

    @Nested
    class DefaultPolicy {
        @Test
        void resolve_adminOnly_forUnknownPostPath() {
            assertThat(RoutePermissionRegistry.resolve("POST", "/api/v1/some-new-endpoint")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forUnknownDeletePath() {
            assertThat(RoutePermissionRegistry.resolve("DELETE", "/api/v1/some-resource/123")).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void resolve_adminOnly_forUnknownPutPath() {
            assertThat(RoutePermissionRegistry.resolve("PUT", "/api/v1/some-resource/123")).isEqualTo(ADMIN_ONLY);
        }
    }
}
