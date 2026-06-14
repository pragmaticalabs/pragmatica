// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;

/// #299 — per-operation authorization keyed by the exact matched ManagementRoute, replacing coarse
/// path-prefix matching. Verifies reads are VIEWER, operational mutations OPERATOR, destructive /
/// identity mutations ADMIN, and that every route resolves to a deterministic per-operation role
/// (no prefix over-reach, no collapse of all reads into one bucket).
class ManagementRoutePermissionsTest {

    @Nested
    class Reads {
        @Test
        void allGetRoutes_resolveToViewer() {
            for (var route : ManagementRoute.values()) {
                if (route.method() == org.pragmatica.http.routing.HttpMethod.GET) {
                    assertThat(ManagementRoutePermissions.permissionFor(route))
                        .as("GET route %s must be VIEWER-readable", route)
                        .isEqualTo(ALL_AUTHENTICATED);
                }
            }
        }
    }

    @Nested
    class OperatorMutations {
        @Test
        void deployStart_isOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.DEPLOY_START)).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void sliceScale_isOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.SLICE_SCALE)).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void blueprintDeploy_isOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BLUEPRINT_DEPLOY)).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void backupTrigger_isOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BACKUP_TRIGGER)).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void artifactPush_isOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.ARTIFACT_PUT)).isEqualTo(OPERATOR_AND_ABOVE);
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.ARTIFACT_POST)).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void clusterConfigApply_isOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CLUSTER_CONFIG_APPLY)).isEqualTo(OPERATOR_AND_ABOVE);
        }

        @Test
        void configSetAndDelete_areOperator() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CONFIG_SET)).isEqualTo(OPERATOR_AND_ABOVE);
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CONFIG_DELETE)).isEqualTo(OPERATOR_AND_ABOVE);
        }
    }

    @Nested
    class AdminMutations {
        @Test
        void blueprintPublishBody_isAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BLUEPRINT_PUBLISH_BODY)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void blueprintDelete_isAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BLUEPRINT_DELETE)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void nodeShutdown_isAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.NODE_SHUTDOWN)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void backupRestore_isAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BACKUP_RESTORE)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void logLevelSet_isAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.LOG_LEVEL_SET)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void clusterKeyCreateAndRevoke_areAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CLUSTER_KEYS_CREATE)).isEqualTo(ADMIN_ONLY);
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CLUSTER_KEYS_REVOKE)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void configNodeDelete_isAdmin() {
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CONFIG_NODE_DELETE)).isEqualTo(ADMIN_ONLY);
        }
    }

    @Nested
    class PerOperationDistinctness {
        @Test
        void siblingRoutesUnderSamePrefix_resolveIndependently() {
            // /api/blueprints prefix used to collapse all nested routes to one bucket. Now each is
            // resolved per-operation: GET (list) = VIEWER, deploy = OPERATOR, raw publish = ADMIN.
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BLUEPRINT_LIST)).isEqualTo(ALL_AUTHENTICATED);
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BLUEPRINT_DEPLOY)).isEqualTo(OPERATOR_AND_ABOVE);
            assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.BLUEPRINT_PUBLISH_BODY)).isEqualTo(ADMIN_ONLY);
        }

        @Test
        void everyRouteResolvesDeterministically() {
            for (var route : ManagementRoute.values()) {
                assertThat(ManagementRoutePermissions.permissionFor(route))
                    .as("route %s must resolve to a permission", route)
                    .isNotNull();
            }
        }
    }
}
