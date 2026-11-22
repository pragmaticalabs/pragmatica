// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.AdminGrant.RequestScope;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

class SystemSecurityValidatorTest {
    @Test
    void validate_returnsSystemContext_forNoOpValidator() {
        var validator = SecurityValidator.permitAllValidator();
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test");

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .map(Authenticated::context)
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isService()).isTrue();
                     assertThat(context.principal().value()).isEqualTo("service:system");
                     assertThat(context.hasRole(Role.ADMIN)).isTrue();
                     assertThat(context.hasRole(Role.SERVICE)).isTrue();
                 });
    }

    /// The ADMIN role permitAll hands out matched no credential, so it mints no grant. Mutation: minting from the
    /// system context turns this red. The control beside it shows a real ADMIN credential does mint on this classpath,
    /// so "none" here is the validator's answer and not a mint that cannot happen.
    @Test
    void permitAll_mintsNoGrant_althoughItHandsOutAdmin() {
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test-3303");

        SecurityValidator.permitAllValidator()
                         .validate(request, SecurityPolicy.apiKeyRequired())
                         .onFailureRun(() -> fail("Expected success"))
                         .onSuccess(authenticated -> assertThat(authenticated.grant().isEmpty()).as("permitAll minted a grant")
                                                                                                 .isTrue());
    }

    @Test
    void control_aRealAdminCredentialMintsALiveGrant() {
        var minted = new boolean[1];

        TestAdminGrants.withAdminGrant(grant -> minted[0] = grant.live());

        assertThat(minted[0]).as("the ADMIN test credential minted a live grant").isTrue();
    }

    private static final HttpRequestContext REQUEST = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test-3303-a3c");

    private static AppHttpConfig config(SecurityMode appMode, Option<SecurityMode> managementMode) {
        return AppHttpConfig.appHttpConfig(true, 8080, Map.of(), 1024, appMode, Option.none(), HttpProtocol.H1)
                            .unwrap()
                            .withManagementSecurityMode(managementMode);
    }

    private static SecurityValidator.ManagementSecurity managementFor(AppHttpConfig config) {
        return SecurityValidator.managementValidator(config, () -> mock(KVStore.class));
    }

    /// Validates inside an open request scope and records, inside it, whether the minted grant was live: a grant
    /// is live only while its scope is open, so liveness must be read before `run` returns.
    private static Authenticated validatedInScope(SecurityValidator validator, boolean[] liveInScope) {
        var holder = new Authenticated[1];

        RequestScope.requestScope().run(() -> holder[0] = observeInScope(validator, liveInScope));

        return holder[0];
    }

    private static Authenticated observeInScope(SecurityValidator validator, boolean[] liveInScope) {
        var authenticated = validator.validate(REQUEST, SecurityPolicy.apiKeyRequired()).unwrap();

        liveInScope[0] = authenticated.grant().map(AdminGrant::live).or(false);
        return authenticated;
    }

    /// A NONE config: not enabled, and inside a request scope the validator answers with a LIVE grant for the
    /// `security-off` principal over the empty context. Red if the enabled test is inverted (the validator would refuse
    /// a credential-less request), or if the OFF validator mints nothing.
    @Test
    void managementValidator_noneConfig_inScope_mintsALiveGrantOverTheEmptyContext() {
        var management = managementFor(config(SecurityMode.NONE, Option.none()));
        var live = new boolean[1];
        var authenticated = validatedInScope(management.validator(), live);

        assertThat(management.enabled()).isFalse();
        assertThat(authenticated.context().isAuthenticated()).as("the context stays empty").isFalse();
        assertThat(live[0]).as("the grant was live at the match").isTrue();
        assertThat(authenticated.grant().map(grant -> grant.grantedBy().value()).or("")).isEqualTo("service:security-off");
    }

    /// Outside a request scope the OFF grant is born dead (`mint` captures the scope bound on the thread; none is bound
    /// here). Red if `mint` is given an empty context, or a scope that is open.
    @Test
    void managementValidator_noneConfig_outsideScope_theGrantIsNotLive() {
        var management = managementFor(config(SecurityMode.NONE, Option.none()));
        var authenticated = management.validator().validate(REQUEST, SecurityPolicy.apiKeyRequired()).unwrap();

        assertThat(authenticated.grant().isEmpty()).as("a grant is minted").isFalse();
        assertThat(authenticated.grant().map(AdminGrant::live).or(true)).as("but it is dead outside a scope").isFalse();
    }

    /// An ON config with no credential fails, enabled. Red if the enabled test is inverted.
    @Test
    void managementValidator_apiKeyConfig_noCredential_failsAndIsEnabled() {
        var management = managementFor(config(SecurityMode.API_KEY, Option.none()));
        var outcome = management.validator().validate(REQUEST, SecurityPolicy.apiKeyRequired());

        assertThat(management.enabled()).isTrue();
        assertThat(outcome.isFailure()).as("no credential, no admission").isTrue();
    }

    /// #962, the app plane NONE and the management plane api-key: management is enabled AND its validator refuses. Red if
    /// the validator is chosen by the app plane's `securityMode` alone.
    @Test
    void managementValidator_appNone_managementApiKey_isEnabledAndRefuses() {
        var management = managementFor(config(SecurityMode.NONE, Option.some(SecurityMode.API_KEY)));
        var outcome = management.validator().validate(REQUEST, SecurityPolicy.apiKeyRequired());

        assertThat(management.enabled()).isTrue();
        assertThat(outcome.isFailure()).as("the management plane's own mode arms the validator").isTrue();
    }

    /// #962, the reverse: the app plane api-key and the management plane none: not enabled, and a live grant in scope.
    @Test
    void managementValidator_appApiKey_managementNone_isNotEnabledAndGrantsLive() {
        var management = managementFor(config(SecurityMode.API_KEY, Option.some(SecurityMode.NONE)));
        var live = new boolean[1];

        validatedInScope(management.validator(), live);

        assertThat(management.enabled()).isFalse();
        assertThat(live[0]).as("the OFF validator answered").isTrue();
    }
}
