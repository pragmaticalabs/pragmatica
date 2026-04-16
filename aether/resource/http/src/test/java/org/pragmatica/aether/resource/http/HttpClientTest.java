// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import java.net.http.HttpClient.Redirect;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientTest {
    @Test
    void httpClientConfig_withDefaults_hasCorrectValues() {
        HttpClientConfig.httpClientConfig()
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.baseUrl().isEmpty()).isTrue();
                assertThat(config.connectTimeout()).isEqualTo(TimeSpan.timeSpan(10).seconds());
                assertThat(config.requestTimeout()).isEqualTo(TimeSpan.timeSpan(30).seconds());
                assertThat(config.followRedirects()).isEqualTo(Redirect.NORMAL);
            });
    }

    @Test
    void httpClientConfig_withBaseUrl_hasBaseUrl() {
        HttpClientConfig.httpClientConfig("https://api.example.com")
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.baseUrl().fold(() -> "", v -> v)).isEqualTo("https://api.example.com");
            });
    }

    @Test
    void httpClientConfig_withCustomTimeouts_hasCorrectTimeouts() {
        HttpClientConfig.httpClientConfig(
                "https://api.example.com",
                TimeSpan.timeSpan(5).seconds(),
                TimeSpan.timeSpan(60).seconds()
            )
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.connectTimeout()).isEqualTo(TimeSpan.timeSpan(5).seconds());
                assertThat(config.requestTimeout()).isEqualTo(TimeSpan.timeSpan(60).seconds());
            });
    }

    @Test
    void httpClient_factory_createsInstance() {
        var client = JdkHttpClient.jdkHttpClient();

        assertThat(client).isNotNull();
        assertThat(client.config()).isNotNull();
        assertThat(client.config().baseUrl().isEmpty()).isTrue();
    }

    @Test
    void httpClient_factoryWithConfig_usesConfig() {
        var config = HttpClientConfig.httpClientConfig("https://api.example.com").unwrap();
        var client = JdkHttpClient.jdkHttpClient(config);

        assertThat(client.config()).isEqualTo(config);
        assertThat(client.config().baseUrl().fold(() -> "", v -> v)).isEqualTo("https://api.example.com");
    }

    @Test
    void httpClientConfig_withDefaults_hasJsonAndHeadersDefaults() {
        HttpClientConfig.httpClientConfig()
            .onFailureRun(Assertions::fail)
            .onSuccess(config -> {
                assertThat(config.json().isEmpty()).isTrue();
                assertThat(config.defaultHeaders()).isEmpty();
            });
    }

}
