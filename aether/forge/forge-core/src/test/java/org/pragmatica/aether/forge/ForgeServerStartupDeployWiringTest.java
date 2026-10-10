// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberConfig;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #1218 — pins the CALL SITE, which `ForgeServerStartupDeployBudgetTest` cannot see: that test drives
/// `awaitStartupDeploy` directly, so `deployBlueprintFromArtifact` could inline its own fixed await again
/// and every helper test would stay green.
///
/// Drives `deployBlueprintFromArtifact` itself with a 1 s start budget against a transport answering at
/// 2 s. Honouring the budget fails the deploy; any fixed await longer than 2 s (the old 10 s included)
/// lets it succeed and this test reddens. Takes about 1 s.
class ForgeServerStartupDeployWiringTest {
    private static final int CONFIGURED_BUDGET_SECONDS = 1;
    private static final int RESPONSE_DELAY_MILLIS = 2_000;

    @Test
    void startupDeploy_answeringAfterTheConfiguredBudget_failsClosedNamingTheBudget() {
        var base = EmberConfig.DEFAULT;
        var config = new EmberConfig(base.nodes(),
                                     base.basePort(),
                                     base.managementPort(),
                                     base.dashboardPort(),
                                     base.appHttpPort(),
                                     base.h2Config(),
                                     base.observability(),
                                     base.lbEnabled(),
                                     base.lbPort(),
                                     base.coreMax(),
                                     CONFIGURED_BUDGET_SECONDS);
        var startup = new StartupConfig(Option.none(),
                                        Option.some("org.example:hello:1.0.0:blueprint"),
                                        Option.none(),
                                        false,
                                        8888,
                                        base.nodes(),
                                        1000);
        var server = new ForgeServer(startup, config, slowTransport());

        assertThatThrownBy(() -> server.deployBlueprintFromArtifact("org.example:hello:1.0.0:blueprint"))
            .as("the startup deploy must be bounded by start_timeout_seconds, not a fixed await")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deploy budget 1s");
    }

    private static HttpOperations slowTransport() {
        return new HttpOperations() {
            @Override
            public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
                var promise = Promise.<HttpResult<T>> promise();

                Thread.ofVirtual()
                      .start(() -> respondAfterDelay(promise));

                return promise;
            }

            @SuppressWarnings("unchecked")
            private <T> void respondAfterDelay(Promise<HttpResult<T>> promise) {
                try {
                    Thread.sleep(RESPONSE_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                promise.succeed((HttpResult<T>) new HttpResult<>(200, null, "ok"));
            }
        };
    }
}
