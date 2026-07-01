// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.http.HttpClientConfig.HttpBackend;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.http.NettyHttpOperations;

import java.net.http.HttpClient.Redirect;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies backend selection in JdkHttpClient.createOperations: JDK is the default when the
/// selector is unset, and NETTY opts into the Netty-backed HttpOperations. Assertions are on
/// the concrete HttpOperations implementation class.
class HttpBackendSelectionTest {

    private static HttpClientConfig configWith(HttpBackend backend) {
        return HttpClientConfig.httpClientConfig(none(),
                                                 timeSpan(10).seconds(),
                                                 timeSpan(30).seconds(),
                                                 Redirect.NORMAL,
                                                 none(),
                                                 Map.of(),
                                                 some(backend))
                               .unwrap();
    }

    @Test
    void createOperations_defaultsToJdk_whenBackendUnset() {
        var config = HttpClientConfig.httpClientConfig().unwrap();

        assertThat(config.backend().isEmpty()).isTrue();
        assertThat(JdkHttpClient.createOperations(config)).isInstanceOf(JdkHttpOperations.class);
    }

    @Test
    void createOperations_selectsJdk_whenBackendJdk() {
        assertThat(JdkHttpClient.createOperations(configWith(HttpBackend.JDK))).isInstanceOf(JdkHttpOperations.class);
    }

    @Test
    void createOperations_selectsNetty_whenBackendNetty() {
        var operations = JdkHttpClient.createOperations(configWith(HttpBackend.NETTY));

        assertThat(operations).isInstanceOf(NettyHttpOperations.class);
        ((NettyHttpOperations) operations).close().await();
    }
}
