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

        static final List<String> ADMIN = List.of("/api/v1/blueprints",
                                                  "/api/v1/nodes/shutdown",
                                                  "/api/v1/backups/restore",
                                                  "/api/v1/logging/levels",
                                                  "/api/v1/observability/depth");

        static final List<String> OPERATOR = List.of("/api/v1/nodes/drain",
                                                     "/api/v1/nodes/activate",
                                                     "/api/v1/schema",
                                                     "/api/v1/canary",
                                                     "/api/v1/blue-green",
                                                     "/api/v1/rolling-update",
                                                     "/api/v1/ab-tests",
                                                     "/api/v1/backups",
                                                     "/api/v1/scale",
                                                     "/api/v1/scheduled-tasks",
                                                     "/api/v1/controller",
                                                     "/api/v1/thresholds",
                                                     "/api/v1/alerts/clear",
                                                     "/api/v1/config",
                                                     "/api/v1/invocations/metrics/strategy",
                                                     "/api/v1/streams",
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
            if (path.startsWith("/api/v1/blueprints/deploy")) {
                return OPERATOR_AND_ABOVE;
            }

            if (path.startsWith("/api/v1/blueprints/validate")) {
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
