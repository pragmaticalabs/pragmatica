package org.pragmatica.http.routing.security;

/// Security policy attached to an HTTP route.
/// Determines whether a request with a given security context is allowed.
///
/// [#canAccess] HAS NO DEFAULT, deliberately (#876). It used to default to [Access#ALLOW], which
/// made this read as a backstop while permitting every request an implementor forgot to decide
/// about — and the compiler could not see the omission, because inheriting a default is silent.
/// Option (b) of the three the ticket offered was taken: removing the default turns "forgot to
/// decide" into a COMPILE error, which is stronger than flipping it to [Access#DENY] (option (a),
/// which leaves the silent-inheritance mechanism intact and merely changes what it silently
/// decides) and stronger than deleting the method (option (c), which would drop the one place the
/// policy hierarchy states its own contract).
///
/// It was not a hypothetical hazard: `SecurityPolicy.unused()` in `aether/http-handler-api` was a
/// real unoverriding implementor, and therefore answered ALLOW, while its sibling members were
/// each chosen to fail closed. Removing the default is what surfaced it.
///
/// A route that is genuinely public says so at the site with [#permitAll()], which NAMES the grant
/// instead of inheriting it — the same reasoning that named `SecurityValidator.permitAllValidator`
/// rather than `noOp` (#573): "no-op" reads as harmless, and an unconditional authorization grant
/// is the opposite.
public interface RouteSecurityPolicy {
    /// Check if the given security context grants access to this route.
    <T extends RequestSecurityContext> Access canAccess(T context);

    /// GRANTS ACCESS TO EVERY CALLER, authenticated or not. The explicit, named form of a public
    /// route, and the replacement for the removed ALLOW default: identical behavior, stated at the
    /// call site instead of inherited by omission.
    static RouteSecurityPolicy permitAll() {
        return PermitAll.INSTANCE;
    }

    record PermitAll() implements RouteSecurityPolicy {
        private static final PermitAll INSTANCE = new PermitAll();

        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.ALLOW;
        }
    }
}
