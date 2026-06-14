// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.http.handler.security.RoutePermission;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.HttpMethod;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;


/// #299 — per-operation authorization. Replaces the coarse path-prefix `startsWith` matching of
/// [`org.pragmatica.aether.http.handler.security.RoutePermissionRegistry`] (where a prefix grant
/// unintentionally covered every nested route and all reads collapsed to a single bucket) with an
/// exact per-route permission keyed by the matched [`ManagementRoute`] enum constant.
///
/// Each management operation has its own descriptor: the route is identified by exact method+path
/// match (via [`ManagementRoute#match`]) and mapped to a minimum authorization role. Reads are
/// VIEWER; operational mutations are OPERATOR; destructive / identity-affecting mutations are ADMIN.
/// Any route not present in the table is denied by default (ADMIN-only) rather than silently granted.
public sealed interface ManagementRoutePermissions {
    Map<ManagementRoute, RoutePermission> TABLE = buildTable();

    /// Resolve the minimum role for an exactly-matched management route. Deny-by-default
    /// (ADMIN-only) for any route missing from the table.
    static RoutePermission permissionFor(ManagementRoute route) {
        return TABLE.getOrDefault(route, ADMIN_ONLY);
    }

    private static Map<ManagementRoute, RoutePermission> buildTable() {
        var table = new EnumMap<ManagementRoute, RoutePermission>(ManagementRoute.class);

        assignReads(table);
        assignOperatorMutations(table);
        assignAdminMutations(table);

        return Map.copyOf(table);
    }

    /// All read (GET) operations require an authenticated VIEWER (or higher). Probes are reachable
    /// without a route entry (they bypass the security gate in ManagementServer), but listing them as
    /// VIEWER is harmless and keeps the table exhaustive over GET routes.
    private static void assignReads(Map<ManagementRoute, RoutePermission> table) {
        for (var route : ManagementRoute.values()) {
            if (route.method() == HttpMethod.GET) {
                table.put(route, ALL_AUTHENTICATED);
            }
        }
    }

    /// Operational mutations — day-2 operations an OPERATOR performs: deploy strategies, scaling,
    /// schema migrations, streams, scheduled-task actions, backups (create), config set/delete,
    /// thresholds, alerts clear, controller tuning, cluster topology remediation, artifact publish,
    /// cluster-config apply, key listing/audit (read-shaped POSTs are absent — those are GET).
    private static void assignOperatorMutations(Map<ManagementRoute, RoutePermission> table) {
        for (var route : operatorRoutes()) {
            table.put(route, OPERATOR_AND_ABOVE);
        }
    }

    /// Destructive / identity-affecting mutations — ADMIN only: raw blueprint publish + delete, node
    /// shutdown/promote, backup restore, log-level and observability-depth changes, config-node
    /// delete, API-key create/revoke, dev-only inject endpoints.
    private static void assignAdminMutations(Map<ManagementRoute, RoutePermission> table) {
        for (var route : adminRoutes()) {
            table.put(route, ADMIN_ONLY);
        }
    }

    private static List<ManagementRoute> operatorRoutes() {
        return List.of(
            ManagementRoute.CLUSTER_AWAIT_QUIESCED,
            ManagementRoute.CLUSTER_CONFIG_APPLY,
            ManagementRoute.CLUSTER_SCALE,
            ManagementRoute.CLUSTER_CIRCUIT_BREAKER_RESET,
            ManagementRoute.CLUSTER_AUTO_HEAL_ENABLE,
            ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE,
            ManagementRoute.CLUSTER_UPGRADE,
            ManagementRoute.CLUSTER_MIGRATE,
            ManagementRoute.CLUSTER_MIGRATE_PLAN,
            ManagementRoute.DEPLOY_START,
            ManagementRoute.DEPLOY_PROMOTE,
            ManagementRoute.DEPLOY_ROLLBACK,
            ManagementRoute.DEPLOY_COMPLETE,
            ManagementRoute.AB_TEST_CREATE,
            ManagementRoute.AB_TEST_CONCLUDE,
            ManagementRoute.BLUEPRINT_DEPLOY,
            ManagementRoute.SLICE_SCALE,
            ManagementRoute.SCHEMA_MIGRATE,
            ManagementRoute.SCHEMA_UNDO,
            ManagementRoute.SCHEMA_BASELINE,
            ManagementRoute.SCHEMA_RETRY,
            ManagementRoute.STORAGE_SNAPSHOT,
            ManagementRoute.STREAM_CREATE,
            ManagementRoute.STREAM_PUBLISH,
            ManagementRoute.STREAM_DELETE,
            ManagementRoute.STREAMS_PUBLISH,
            ManagementRoute.STREAMS_PUBLISH_BATCH,
            ManagementRoute.STREAMS_GROUP_CREATE,
            ManagementRoute.STREAMS_GROUP_DELETE,
            ManagementRoute.STREAMS_DELETE,
            ManagementRoute.CONSUMER_GROUP_JOIN,
            ManagementRoute.CONSUMER_GROUP_LEAVE,
            ManagementRoute.SCHEDULED_TASK_PAUSE,
            ManagementRoute.SCHEDULED_TASK_RESUME,
            ManagementRoute.SCHEDULED_TASK_TRIGGER,
            ManagementRoute.CONTROLLER_CONFIG_SET,
            ManagementRoute.CONTROLLER_EVALUATE,
            ManagementRoute.THRESHOLD_SET,
            ManagementRoute.THRESHOLD_DELETE,
            ManagementRoute.ALERTS_CLEAR,
            ManagementRoute.CONFIG_SET,
            ManagementRoute.CONFIG_DELETE,
            ManagementRoute.INVOCATION_METRICS_STRATEGY_SET,
            ManagementRoute.ARTIFACT_PUT,
            ManagementRoute.ARTIFACT_POST,
            ManagementRoute.ARTIFACT_DELETE,
            ManagementRoute.NODE_DRAIN,
            ManagementRoute.BACKUP_TRIGGER);
    }

    private static List<ManagementRoute> adminRoutes() {
        return List.of(
            ManagementRoute.BLUEPRINT_PUBLISH_BODY,
            ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT,
            ManagementRoute.BLUEPRINT_DELETE,
            ManagementRoute.NODE_SHUTDOWN,
            ManagementRoute.NODE_PROMOTE,
            ManagementRoute.BACKUP_RESTORE,
            ManagementRoute.LOG_LEVEL_SET,
            ManagementRoute.LOG_LEVEL_RESET,
            ManagementRoute.OBSERVABILITY_DEPTH_SET,
            ManagementRoute.OBSERVABILITY_DEPTH_DELETE,
            ManagementRoute.CONFIG_NODE_DELETE,
            ManagementRoute.CLUSTER_KEYS_CREATE,
            ManagementRoute.CLUSTER_KEYS_REVOKE,
            ManagementRoute.CERT_CONFIGURE_SHORT_VALIDITY,
            ManagementRoute.SCHEDULED_TASK_INJECT,
            ManagementRoute.ALERTS_INJECT,
            ManagementRoute.TRACES_INJECT,
            ManagementRoute.DHT_INJECT,
            ManagementRoute.METRICS_BACKFILL);
    }

    record unused() implements ManagementRoutePermissions {}
}
