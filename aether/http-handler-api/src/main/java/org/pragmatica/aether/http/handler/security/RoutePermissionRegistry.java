// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import java.util.List;

import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;


public sealed interface RoutePermissionRegistry {
    static RoutePermission resolve(String method, String path) {
        if (isReadMethod(method)) {return ALL_AUTHENTICATED;}
        return Prefixes.resolveMutationPermission(method, path);
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    @SuppressWarnings("unused") final class Prefixes {
        private Prefixes() {}

        static final List<String> ADMIN = List.of("/api/blueprints",
                                                  "/api/nodes/shutdown",
                                                  "/api/backups/restore",
                                                  "/api/logging/levels",
                                                  "/api/observability/depth");

        static final List<String> OPERATOR = List.of("/api/nodes/drain",
                                                     "/api/nodes/activate",
                                                     "/api/schema",
                                                     "/api/canary",
                                                     "/api/blue-green",
                                                     "/api/rolling-update",
                                                     "/api/ab-tests",
                                                     "/api/backups",
                                                     "/api/scale",
                                                     "/api/scheduled-tasks",
                                                     "/api/controller",
                                                     "/api/thresholds",
                                                     "/api/alerts/clear",
                                                     "/api/config",
                                                     "/api/invocations/metrics/strategy",
                                                     "/api/streams",
                                                     "/repository/");

        static RoutePermission resolveMutationPermission(String method, String path) {
            if (matchesAny(path, ADMIN)) {return resolveAdminOverrides(path);}
            if (matchesAny(path, OPERATOR)) {return resolveOperatorOverrides(method, path);}
            return ADMIN_ONLY;
        }

        static RoutePermission resolveAdminOverrides(String path) {
            if (path.startsWith("/api/blueprints/deploy")) {return OPERATOR_AND_ABOVE;}
            if (path.startsWith("/api/blueprints/validate")) {return ALL_AUTHENTICATED;}
            return ADMIN_ONLY;
        }

        /// Per-spec event-stream-namespaces §12.1: DELETE on /api/streams/{ns}/{stream}/{version}
        /// is force-purge of a stream version and requires ADMIN. The /groups/{group} DELETE
        /// remains OPERATOR_AND_ABOVE because removing a durable consumer group is reversible
        /// by re-creation. All other /api/streams writes stay at OPERATOR_AND_ABOVE per the
        /// /api/streams prefix default.
        static RoutePermission resolveOperatorOverrides(String method, String path) {
            if (isStreamVersionDelete(method, path)) {return ADMIN_ONLY;}
            return OPERATOR_AND_ABOVE;
        }

        static boolean isStreamVersionDelete(String method, String path) {
            if (!"DELETE".equalsIgnoreCase(method)) {return false;}
            if (!path.startsWith("/api/streams/")) {return false;}
            var rest = stripQuery(path).substring("/api/streams/".length());
            var parts = rest.split("/");
            // Stream version DELETE is exactly /api/streams/{ns}/{stream}/{version} → 3 segments
            // and no /groups/ literal in the path. Anything that contains /groups/ at the right
            // position is the consumer-group DELETE which stays OPERATOR_AND_ABOVE.
            return parts.length == 3 && !path.contains("/groups/");
        }

        private static String stripQuery(String path) {
            var idx = path.indexOf('?');
            return idx < 0 ? path : path.substring(0, idx);
        }

        static boolean matchesAny(String path, List<String> prefixes) {
            for (var prefix : prefixes) {if (path.startsWith(prefix)) {return true;}}
            return false;
        }
    }

    record unused() implements RoutePermissionRegistry{}
}
