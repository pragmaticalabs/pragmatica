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
    static RoutePermission resolve(String method, String path) {
        return isReadMethod(method)
              ? ALL_AUTHENTICATED
              : Prefixes.resolveMutationPermission(path);
    }

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method);
    }

    @SuppressWarnings("unused") final class Prefixes {
        private Prefixes() {}

        static final List<String> ADMIN = List.of("/api/blueprint",
                                                  "/api/node/shutdown",
                                                  "/api/backup/restore",
                                                  "/api/logging/levels",
                                                  "/api/observability/depth");

        static final List<String> OPERATOR = List.of("/api/node/drain",
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
                                                     "/api/streams",
                                                     "/repository/");

        static RoutePermission resolveMutationPermission(String path) {
            if (matchesAny(path, ADMIN)) {return resolveAdminOverrides(path);}
            if (matchesAny(path, OPERATOR)) {return OPERATOR_AND_ABOVE;}
            return ADMIN_ONLY;
        }

        static RoutePermission resolveAdminOverrides(String path) {
            if (path.startsWith("/api/blueprint/deploy")) {return OPERATOR_AND_ABOVE;}
            if (path.startsWith("/api/blueprint/validate")) {return ALL_AUTHENTICATED;}
            return ADMIN_ONLY;
        }

        static boolean matchesAny(String path, List<String> prefixes) {
            for (var prefix : prefixes) {if (path.startsWith(prefix)) {return true;}}
            return false;
        }
    }

    record unused() implements RoutePermissionRegistry{}
}
