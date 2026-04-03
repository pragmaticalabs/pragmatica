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

    public static Option<SecurityContext> currentContext() {
        return SECURITY_CONTEXT.isBound()
              ? Option.option(SECURITY_CONTEXT.get())
              : Option.empty();
    }

    public static boolean isAuthenticated() {
        return SECURITY_CONTEXT.isBound() && SECURITY_CONTEXT.get().isAuthenticated();
    }

    public static ScopedValue<SecurityContext> scopedValue() {
        return SECURITY_CONTEXT;
    }
}
