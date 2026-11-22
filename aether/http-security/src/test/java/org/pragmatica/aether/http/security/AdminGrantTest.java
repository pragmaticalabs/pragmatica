// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.AdminGrant.RequestScope;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminGrantTest {
    private static final String ADMIN_KEY = "admin-key-12345";
    private static final String OPERATOR_KEY = "operator-key-67890";
    private static final HttpAuthenticator VALIDATOR = HttpAuthenticator.apiKeyValidator(Map.of(
        ADMIN_KEY, ApiKeyEntry.apiKeyEntry("admin-svc", Set.of("service"), "ADMIN"),
        OPERATOR_KEY, ApiKeyEntry.apiKeyEntry("operator-svc", Set.of("service"), "OPERATOR")));

    @Test
    void run_adminKeyInsideScope_mintsLiveGrant() {
        var live = new AtomicBoolean(false);

        RequestScope.requestScope().run(() -> live.set(grantFor(ADMIN_KEY).map(AdminGrant::live).or(false)));

        assertThat(live.get()).as("an ADMIN key inside the scope mints a live grant").isTrue();
    }

    @Test
    void run_operatorKeyInsideScope_mintsNone() {
        var grant = new AtomicReference<Option<AdminGrant>>(Option.none());

        RequestScope.requestScope().run(() -> grant.set(grantFor(OPERATOR_KEY)));

        assertThat(grant.get().isPresent()).as("an OPERATOR key mints no grant").isFalse();
    }

    @Test
    void grantedBy_isTheMatchedPrincipal() {
        var grant = new AtomicReference<Option<AdminGrant>>(Option.none());

        RequestScope.requestScope().run(() -> grant.set(grantFor(ADMIN_KEY)));

        assertThat(grant.get().map(AdminGrant::grantedBy).map(Principal::value).or(""))
            .isEqualTo("api-key:admin-svc");
    }

    @Test
    void live_afterRunReturns_isDead() {
        var grant = new AtomicReference<Option<AdminGrant>>(Option.none());

        RequestScope.requestScope().run(() -> grant.set(grantFor(ADMIN_KEY)));

        assertThat(grant.get().isPresent()).isTrue();
        assertThat(grant.get().map(AdminGrant::live).or(true)).as("a grant is dead once its run returned").isFalse();
    }

    @Test
    void live_afterRunThrows_isDead() {
        var grant = new AtomicReference<Option<AdminGrant>>(Option.none());

        assertThatThrownBy(() -> RequestScope.requestScope().run(() -> mintThenThrow(grant)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(grant.get().isPresent()).isTrue();
        assertThat(grant.get().map(AdminGrant::live).or(true)).as("a grant is dead once its run threw").isFalse();
    }

    @Test
    void live_insideAnotherScope_isDead() {
        var grant = new AtomicReference<Option<AdminGrant>>(Option.none());
        var liveElsewhere = new AtomicBoolean(true);

        RequestScope.requestScope().run(() -> grant.set(grantFor(ADMIN_KEY)));
        RequestScope.requestScope().run(() -> liveElsewhere.set(grant.get().map(AdminGrant::live).or(true)));

        assertThat(grant.get().isPresent()).isTrue();
        assertThat(liveElsewhere.get()).as("another bound scope is not the grant's own").isFalse();
    }

    @Test
    void live_onAnotherThreadDuringRun_isDead() {
        var liveOnOtherThread = new AtomicBoolean(true);
        var liveOnOwnThread = new AtomicBoolean(false);

        RequestScope.requestScope().run(() -> checkOnOtherThread(liveOnOwnThread, liveOnOtherThread));

        assertThat(liveOnOwnThread.get()).isTrue();
        assertThat(liveOnOtherThread.get()).as("the scope does not reach another thread").isFalse();
    }

    @Test
    void mint_outsideAnyScope_isBornDead() {
        var grant = grantFor(ADMIN_KEY);

        assertThat(grant.isPresent()).isTrue();
        assertThat(grant.map(AdminGrant::live).or(true)).as("a grant minted unbound is dead").isFalse();
    }

    @Test
    void mint_outsideAnyScope_staysDeadInsideALaterScope() {
        var grant = grantFor(ADMIN_KEY);
        var live = new AtomicBoolean(true);

        RequestScope.requestScope().run(() -> live.set(grant.map(AdminGrant::live).or(true)));

        assertThat(grant.isPresent()).isTrue();
        assertThat(live.get()).as("a grant minted unbound never becomes live").isFalse();
    }

    @Test
    void run_calledTwice_throwsIllegalStateException() {
        var scope = RequestScope.requestScope();
        var runs = new AtomicReference<Integer>(0);

        scope.run(() -> runs.set(runs.get() + 1));

        assertThatThrownBy(() -> scope.run(() -> runs.set(runs.get() + 1))).isInstanceOf(IllegalStateException.class);
        assertThat(runs.get()).as("the second run never executes its action").isEqualTo(1);
    }

    @Test
    void authenticate_defaultOverAdminContext_mintsNone() {
        HttpAuthenticator lambda = (_, _) -> Result.success(adminContext());
        var minted = new AtomicBoolean(true);

        RequestScope.requestScope().run(() -> minted.set(hasGrant(lambda)));

        assertThat(minted.get()).as("the default authenticate never mints, even over ADMIN").isFalse();
    }

    @Test
    void mint_adminContextReadsAuthorizationRole_notRoles() {
        var adminByRoleOnly = SecurityContext.securityContext(Principal.ANONYMOUS,
                                                              Set.of(Role.ADMIN),
                                                              Map.of(),
                                                              AuthorizationRole.OPERATOR);

        assertThat(AdminGrant.mint(adminContext()).isPresent()).isTrue();
        assertThat(AdminGrant.mint(adminByRoleOnly).isPresent()).isFalse();
    }

    private static boolean hasGrant(HttpAuthenticator authenticator) {
        return authenticator.authenticate(request(Map.of()), SecurityPolicy.authenticated())
                            .map(Authenticated::grant)
                            .map(Option::isPresent)
                            .or(true);
    }

    private static void mintThenThrow(AtomicReference<Option<AdminGrant>> grant) {
        grant.set(grantFor(ADMIN_KEY));
        throw new IllegalArgumentException("the request failed");
    }

    private static void checkOnOtherThread(AtomicBoolean liveOnOwnThread, AtomicBoolean liveOnOtherThread) {
        var grant = grantFor(ADMIN_KEY);
        var other = Thread.ofPlatform().start(() -> liveOnOtherThread.set(grant.map(AdminGrant::live).or(true)));

        liveOnOwnThread.set(grant.map(AdminGrant::live).or(false));
        joinQuietly(other);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Option<AdminGrant> grantFor(String key) {
        return VALIDATOR.authenticate(request(Map.of("X-API-Key", List.of(key))), SecurityPolicy.authenticated())
                        .map(Authenticated::grant)
                        .or(Option.none());
    }

    private static SecurityContext adminContext() {
        return SecurityContext.securityContext(Principal.ANONYMOUS, Set.of(), Map.of(), AuthorizationRole.ADMIN);
    }

    private static HttpRequestContext request(Map<String, List<String>> headers) {
        return HttpRequestContext.httpRequestContext("/test", "GET", Map.of(), headers, new byte[0], "test-request-id");
    }
}
