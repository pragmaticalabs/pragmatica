package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Enforces authorization by checking a principal's role against route permissions.
///
/// Pure logic component that sits between authentication (SecurityValidator)
/// and route handling. Takes an authenticated SecurityContext and verifies
/// the principal has sufficient authorization for the requested route.
public sealed interface RoleEnforcer {
    /// Authorization denial errors.
    sealed interface AuthorizationError extends Cause {
        /// Principal's role is insufficient for the route's minimum requirement.
        record AccessDenied(String message) implements AuthorizationError{}

        @SuppressWarnings("unused") record unused() implements AuthorizationError {
            @Override public String message() {
                return "";
            }
        }
    }

    /// Check if the security context satisfies the route permission.
    ///
    /// @param context    the authenticated security context
    /// @param permission the route's minimum role requirement
    /// @return success with the same context if authorized, or failure with AccessDenied cause
    static Result<SecurityContext> enforce(SecurityContext context, RoutePermission permission) {
        return permission.allows(context.authorizationRole())
               ? success(context)
               : accessDeniedCause(context.authorizationRole(), permission.minimumRole()).result();
    }

    private static AuthorizationError.AccessDenied accessDeniedCause(AuthorizationRole actual,
                                                                     AuthorizationRole required) {
        return new AuthorizationError.AccessDenied("Access denied: role " + actual + " cannot access " + required + " endpoint");
    }

    record unused() implements RoleEnforcer{}
}
