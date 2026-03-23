package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Option;

/// ScopedValue-based holder for propagating SecurityContext to slice handlers.
///
/// Set by AppHttpServer during request processing when security is enabled.
/// Slice handlers access it via `SecurityContextHolder.currentContext()`.
///
/// Usage:
/// ```{@code
/// // In slice handler:
/// var ctx = SecurityContextHolder.currentContext();
/// ctx.onPresent(sc -> log.info("Authenticated as: {}", sc.principal().value()));
/// }```
public final class SecurityContextHolder {
    private static final ScopedValue<SecurityContext> SECURITY_CONTEXT = ScopedValue.newInstance();

    private SecurityContextHolder() {}

    /// Get the current security context if set.
    ///
    /// @return Option containing the SecurityContext, or empty if not in an authenticated scope
    public static Option<SecurityContext> currentContext() {
        return SECURITY_CONTEXT.isBound()
               ? Option.option(SECURITY_CONTEXT.get())
               : Option.empty();
    }

    /// Check if a security context is available in the current scope.
    public static boolean isAuthenticated() {
        return SECURITY_CONTEXT.isBound() && SECURITY_CONTEXT.get().isAuthenticated();
    }

    /// Get the underlying ScopedValue for use in ScopedValue.where() chains.
    ///
    /// @return the ScopedValue instance
    public static ScopedValue<SecurityContext> scopedValue() {
        return SECURITY_CONTEXT;
    }
}
