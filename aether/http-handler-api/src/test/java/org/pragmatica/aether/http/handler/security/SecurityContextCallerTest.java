// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.CallerIdentity;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2157: the SecurityContext as the caller identity of an inter-slice call, and its log form.
class SecurityContextCallerTest {
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.secret-token-value";
    private static final SecurityContext ALICE = SecurityContext.securityContext(Principal.principal("user:alice").unwrap(),
                                                                                 Set.of(Role.role("auditor").unwrap()),
                                                                                 Map.of("token", TOKEN, "tenant", "acme-tenant-secret"),
                                                                                 AuthorizationRole.OPERATOR);

    @Test
    void callerIdentity_ofAnAuthenticatedContext_isThatContextUnchanged() {
        assertThat(SecurityContext.callerIdentity(ALICE)).isEqualTo(Option.some(ALICE));
    }

    @Test
    void callerIdentity_ofAnAnonymousOrAbsentContext_isEmpty() {
        assertThat(SecurityContext.callerIdentity(SecurityContext.securityContext())).isEqualTo(Option.none());
        assertThat(SecurityContext.callerIdentity(null)).isEqualTo(Option.none());
    }

    @Test
    void forCaller_ofAnAuthenticatedIdentity_isTheContextUnchanged() {
        assertThat(SecurityContext.forCaller(Option.<CallerIdentity>some(ALICE))).isSameAs(ALICE);
    }

    @Test
    void forCaller_ofNoForeignOrAnonymousIdentity_isTheAnonymousContext() {
        CallerIdentity foreign = new CallerIdentity() {};
        var anonymousAdmin = SecurityContext.securityContext(Principal.ANONYMOUS, Set.of(Role.ADMIN), Map.of(), AuthorizationRole.ADMIN);

        assertThat(SecurityContext.forCaller(Option.none())).isEqualTo(SecurityContext.securityContext());
        assertThat(SecurityContext.forCaller(Option.some(foreign))).isEqualTo(SecurityContext.securityContext());
        assertThat(SecurityContext.forCaller(Option.<CallerIdentity>some(anonymousAdmin))).isEqualTo(SecurityContext.securityContext());
    }

    @Test
    void toString_withholdsClaimValues_andKeepsTheIdentityReadable() {
        var text = ALICE.toString();

        assertThat(text).doesNotContain(TOKEN)
                        .doesNotContain("secret-token-value")
                        .doesNotContain("acme-tenant-secret")
                        .contains("user:alice")
                        .contains("auditor")
                        .contains("token")
                        .contains("tenant")
                        .contains("OPERATOR");
    }
}
