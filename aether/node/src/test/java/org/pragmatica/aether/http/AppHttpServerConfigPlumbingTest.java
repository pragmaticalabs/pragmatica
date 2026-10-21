// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.http.server.BodyRateLimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// F6: the request bounds `[app-http]` declares reach the listener configuration every app server, HTTP/1.1 and
/// HTTP/3, at start and at certificate rotation, is built from. Values differing from every default, so a default
/// that slipped in instead cannot pass.
class AppHttpServerConfigPlumbingTest {
    @Test
    void serverConfig_carriesTheConfiguredReadTimeoutMinimumRateAndBodyLimit() {
        var appConfig = AppHttpConfig.insecureAppHttpConfig(18123)
                                     .withReadTimeout(timeSpan(7).seconds())
                                     .withMinBodyRate(4321);

        var serverConfig = AppHttpServerAdapter.serverConfig("app-http", appConfig);

        assertThat(serverConfig.port()).isEqualTo(18123);
        assertThat(serverConfig.maxContentLength()).isEqualTo(appConfig.maxRequestSize());
        assertThat(serverConfig.readTimeout()).isEqualTo(timeSpan(7).seconds());
        assertThat(serverConfig.bodyRateLimit().bytesPerSecond()).isEqualTo(4321);
        assertThat(serverConfig.bodyRateLimit().grace()).isEqualTo(BodyRateLimit.DEFAULT_GRACE);
        assertThat(serverConfig.bodyRateLimit().window()).isEqualTo(BodyRateLimit.DEFAULT_WINDOW);
    }
}
