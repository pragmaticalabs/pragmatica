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
        return isReadMethod(method)
               ? ALL_AUTHENTICATED
               : Prefixes.resolveMutationPermission(path);
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    @SuppressWarnings("unused")
    final class Prefixes {
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

        static RoutePermission resolveMutationPermission(String path) {
            if (matchesAny(path, ADMIN)) {
                return resolveAdminOverrides(path);
            }

            if (matchesAny(path, OPERATOR)) {
                return OPERATOR_AND_ABOVE;
            }

            return ADMIN_ONLY;
        }

        static RoutePermission resolveAdminOverrides(String path) {
            if (path.startsWith("/api/blueprints/deploy")) {
                return OPERATOR_AND_ABOVE;
            }

            if (path.startsWith("/api/blueprints/validate")) {
                return ALL_AUTHENTICATED;
            }

            return ADMIN_ONLY;
        }

        static boolean matchesAny(String path, List<String> prefixes) {
            for (var prefix : prefixes) {
                if (path.startsWith(prefix)) {
                    return true;
                }
            }

            return false;
        }
    }

    record unused() implements RoutePermissionRegistry {}
}
