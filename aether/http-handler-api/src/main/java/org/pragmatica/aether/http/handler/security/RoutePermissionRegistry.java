package org.pragmatica.aether.http.handler.security;

import java.util.List;

import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;

/// Registry that resolves route permissions based on HTTP method and path.
///
/// Pure logic component: maps management API endpoints to their minimum
/// required authorization role. Used by the security pipeline to enforce
/// role-based access control after authentication succeeds.
///
/// Default policy:
///   - GET requests → ALL_AUTHENTICATED (any authenticated role)
///   - POST/PUT/DELETE requests → ADMIN_ONLY (unless explicitly overridden)
public sealed interface RoutePermissionRegistry {

    /// Resolve the route permission for a given HTTP method and path.
    ///
    /// @param method HTTP method (GET, POST, PUT, DELETE, etc.)
    /// @param path   request path (e.g., "/api/status")
    /// @return the minimum route permission required
    static RoutePermission resolve(String method, String path) {
        return isReadMethod(method)
               ? ALL_AUTHENTICATED
               : Prefixes.resolveMutationPermission(path);
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    /// Internal holder for prefix lists and mutation resolution logic.
    @SuppressWarnings("unused")
    final class Prefixes {
        private Prefixes() {}

        /// Admin-only mutation paths — destructive or configuration-changing operations.
        static final List<String> ADMIN = List.of(
            "/api/blueprint",
            "/api/node/shutdown",
            "/api/backup/restore",
            "/api/logging/levels",
            "/api/observability/depth"
        );

        /// Operator-and-above mutation paths — operational but non-destructive.
        static final List<String> OPERATOR = List.of(
            "/api/node/drain",
            "/api/node/activate",
            "/api/schema",
            "/api/canary",
            "/api/blue-green",
            "/api/rolling-update",
            "/api/ab-test",
            "/api/backup",
            "/api/scale",
            "/api/scheduled-tasks",
            "/api/controller",
            "/api/thresholds",
            "/api/alerts/clear",
            "/api/config",
            "/api/invocation-metrics/strategy",
            "/repository/"
        );

        static RoutePermission resolveMutationPermission(String path) {
            if (matchesAny(path, ADMIN)) {
                return resolveAdminOverrides(path);
            }
            if (matchesAny(path, OPERATOR)) {
                return OPERATOR_AND_ABOVE;
            }
            return ADMIN_ONLY;
        }

        /// Some admin-prefix paths have operator-level sub-paths.
        /// Blueprint deploy from artifact is operator-level; raw blueprint POST is admin-only.
        static RoutePermission resolveAdminOverrides(String path) {
            if (path.startsWith("/api/blueprint/deploy")) {
                return OPERATOR_AND_ABOVE;
            }
            if (path.startsWith("/api/blueprint/validate")) {
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
