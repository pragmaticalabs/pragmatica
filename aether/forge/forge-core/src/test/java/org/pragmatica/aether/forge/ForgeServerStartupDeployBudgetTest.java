// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberConfig;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #1218 — drives the REAL startup-deploy wait against a transport that answers after 11 s.
///
/// A helper test cannot see a call site that still passes a hardcoded 10 s; this one can, because the
/// wait itself is what is measured. With `start_timeout_seconds = 12` the deploy must succeed; with the
/// old fixed 10 s it fails with a timeout. It takes about 11 s by design: a shorter delay could not
/// separate the configured budget from the old constant.
class ForgeServerStartupDeployBudgetTest {
    private static final int OLD_FIXED_BUDGET_SECONDS = 10;
    private static final int CONFIGURED_BUDGET_SECONDS = 12;
    private static final int RESPONSE_DELAY_MILLIS = 11_000;

    @Test
    void startupDeploy_answeringAfterTheOldFixedBudget_succeedsWithinTheConfiguredBudget() {
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
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost/api/v1/blueprints/deploy"))
                                 .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                 .build();

        assertThat(RESPONSE_DELAY_MILLIS).isGreaterThan(OLD_FIXED_BUDGET_SECONDS * 1000)
                                         .isLessThan(CONFIGURED_BUDGET_SECONDS * 1000);

        var result = ForgeServer.awaitStartupDeploy(slowTransport(), config, request);

        assertThat(result.isSuccess()).as("a deploy answering at 11 s must be inside a 12 s start budget: %s", result)
                                      .isTrue();
    }

    private static HttpOperations slowTransport() {
        return new HttpOperations() {
            @Override
            @SuppressWarnings("unchecked")
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
