package org.pragmatica.http.routing.security;

/// Security policy attached to an HTTP route.
/// Determines whether a request with a given security context is allowed.
///
/// Default implementation allows all requests (public route).
public interface RouteSecurityPolicy {
    /// Check if the given security context grants access to this route.
    default <T extends RequestSecurityContext> Access canAccess(T context) {
        return Access.ALLOW;
    }
}
