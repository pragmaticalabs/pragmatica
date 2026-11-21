// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Proof that an ADMIN credential matched during one request. The only factory is the package-private [#mint], called
/// where a verifier matched a credential; the type is final with one private constructor, so no other code can build one.
/// A grant is live only inside the [RequestScope] that was bound when it was minted; it is passed as an argument, and is
/// never read from the scope.
public final class AdminGrant {
    private final Principal grantedBy;
    private final RequestScope scope;

    private AdminGrant(Principal grantedBy, RequestScope scope) {
        this.grantedBy = grantedBy;
        this.scope = scope;
    }

    /// A grant for an ADMIN `authorizationRole()`, none for any other; `roles()` never mints. The grant captures the
    /// scope bound on this thread, or a scope that is never bound when none is, so a grant minted outside a request is
    /// born dead. Takes no scope parameter: the validators feeding `SecurityValidator` are method references.
    static Option<AdminGrant> mint(SecurityContext context) {
        return Option.some(context)
                     .filter(AdminGrant::isAdmin)
                     .map(admin -> new AdminGrant(admin.principal(),
                                                  RequestScope.current()));
    }

    private static boolean isAdmin(SecurityContext context) {
        return context.authorizationRole() == AuthorizationRole.ADMIN;
    }

    /// The principal whose credential matched.
    public Principal grantedBy() {
        return grantedBy;
    }

    /// True only on a thread where this grant's own scope is bound: a scope is bound (open), and it is this grant's
    /// scope (identity). Open is tested first, so the identity read never meets an unbound key.
    public boolean live() {
        return RequestScope.isBound(scope);
    }

    /// One request's scope: a `ScopedValue` binding of the scope itself, never of a grant. It runs once; the JDK unbinds
    /// it when [#run] returns or throws, so no exit can leave it open, and it does not reach another thread.
    public static final class RequestScope {
        private static final ScopedValue<RequestScope> BOUND = ScopedValue.newInstance();
        private static final RequestScope DEAD = new RequestScope(true);

        private final AtomicBoolean entered;

        private RequestScope(boolean entered) {
            this.entered = new AtomicBoolean(entered);
        }

        public static RequestScope requestScope() {
            return new RequestScope(false);
        }

        /// Binds this scope for the duration of `action`. A second call throws [IllegalStateException]: a scope that
        /// has closed never opens again. Void and throwing by contract, as `ScopedValue.Carrier.run` it wraps.
        @Contract
        public void run(Runnable action) {
            if (!entered.compareAndSet(false, true)) {
                throw new IllegalStateException("A request scope runs once");
            }

            ScopedValue.where(BOUND, this).run(action);
        }

        private static RequestScope current() {
            return BOUND.orElse(DEAD);
        }

        private static boolean isBound(RequestScope scope) {
            return BOUND.isBound() && BOUND.get() == scope;
        }
    }
}
