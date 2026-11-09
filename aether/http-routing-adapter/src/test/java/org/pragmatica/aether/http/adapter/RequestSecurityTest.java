// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.impl.SliceRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.http.routing.RequestContext;

import static org.assertj.core.api.Assertions.assertThat;

/// #2157: a generated route reads the caller's context from the request. A request the edge put a context on yields that
/// context; any other request yields the anonymous context the edge gives an unauthenticated request, never null and
/// never an identity.
class RequestSecurityTest {
    private static final SecurityContext ALICE = SecurityContext.securityContext("user:alice", Set.of(Role.role("auditor").unwrap()))
                                                                .unwrap();

    @Test
    void securityOf_aRequestTheEdgeBuilt_isTheContextItCarries() {
        var http = HttpRequestContext.httpRequestContext("/p", "GET", Map.of(), Map.of(), new byte[0], "req_1", ALICE);

        assertThat(RequestSecurity.securityOf(SliceRequestContext.sliceRequestContext(http, null, null))).isEqualTo(ALICE);
    }

    @Test
    void securityOf_aRequestCarryingNoContext_isTheAnonymousContext() {
        var foreign = (RequestContext) Proxy.newProxyInstance(RequestContext.class.getClassLoader(),
                                                              new Class<?>[]{RequestContext.class},
                                                              (_, method, _) -> {
                                                                  throw new UnsupportedOperationException(method.getName());
                                                              });
        var security = RequestSecurity.securityOf(foreign);

        assertThat(security).isEqualTo(SecurityContext.securityContext());
        assertThat(security.principal()).isEqualTo(Principal.ANONYMOUS);
        assertThat(security.isAuthenticated()).isFalse();
        assertThat(security.roles()).isEqualTo(Set.of());
    }
}
