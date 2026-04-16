// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.http;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import java.net.http.HttpClient.Redirect;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.http.HttpClientConfig.httpClientConfig;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class HttpClientConfigTest {

    private static final TimeSpan DEFAULT_CONNECT_TIMEOUT = timeSpan(10).seconds();
    private static final TimeSpan DEFAULT_REQUEST_TIMEOUT = timeSpan(30).seconds();

    @Nested
    class NoArgFactory {

        @Test
        void httpClientConfig_succeeds_withDefaults() {
            var config = httpClientConfig().unwrap();

            assertThat(config.baseUrl().isEmpty()).isTrue();
            assertThat(config.connectTimeout()).isEqualTo(DEFAULT_CONNECT_TIMEOUT);
            assertThat(config.requestTimeout()).isEqualTo(DEFAULT_REQUEST_TIMEOUT);
            assertThat(config.followRedirects()).isEqualTo(Redirect.NORMAL);
            assertThat(config.json().isEmpty()).isTrue();
            assertThat(config.defaultHeaders()).isEmpty();
        }
    }

    @Nested
    class BaseUrlFactory {

        @Test
        void httpClientConfig_setsBaseUrl_whenProvided() {
            var config = httpClientConfig("https://api.example.com").unwrap();

            assertThat(config.baseUrl().isPresent()).isTrue();
            config.baseUrl().onPresent(url -> assertThat(url).isEqualTo("https://api.example.com"));
        }

        @Test
        void httpClientConfig_setsEmptyBaseUrl_whenNull() {
            var config = httpClientConfig((String) null).unwrap();

            assertThat(config.baseUrl().isEmpty()).isTrue();
        }

        @Test
        void httpClientConfig_usesDefaultTimeouts_withBaseUrlOnly() {
            var config = httpClientConfig("https://api.example.com").unwrap();

            assertThat(config.connectTimeout()).isEqualTo(DEFAULT_CONNECT_TIMEOUT);
            assertThat(config.requestTimeout()).isEqualTo(DEFAULT_REQUEST_TIMEOUT);
        }
    }

    @Nested
    class TimeoutFactory {

        @Test
        void httpClientConfig_appliesCustomTimeouts_whenProvided() {
            var connectTimeout = timeSpan(5).seconds();
            var requestTimeout = timeSpan(15).seconds();
            var config = httpClientConfig("https://api.example.com", connectTimeout, requestTimeout).unwrap();

            assertThat(config.connectTimeout()).isEqualTo(connectTimeout);
            assertThat(config.requestTimeout()).isEqualTo(requestTimeout);
        }
    }

    @Nested
    class FullFactory {

        @Test
        void httpClientConfig_succeeds_withAllParams() {
            var connectTimeout = timeSpan(3).seconds();
            var requestTimeout = timeSpan(10).seconds();
            var headers = Map.of("Authorization", "Bearer token", "Accept", "application/json");
            var json = some(JsonConfig.jsonConfig());

            var config = httpClientConfig(some("https://api.example.com"),
                                           connectTimeout,
                                           requestTimeout,
                                           Redirect.NEVER,
                                           json,
                                           headers)
                .unwrap();

            assertThat(config.followRedirects()).isEqualTo(Redirect.NEVER);
            assertThat(config.defaultHeaders()).hasSize(2);
            assertThat(config.defaultHeaders()).containsEntry("Authorization", "Bearer token");
            assertThat(config.json().isPresent()).isTrue();
        }

        @Test
        void httpClientConfig_succeeds_withRedirectPolicyOnly() {
            var config = httpClientConfig(none(),
                                           DEFAULT_CONNECT_TIMEOUT,
                                           DEFAULT_REQUEST_TIMEOUT,
                                           Redirect.ALWAYS)
                .unwrap();

            assertThat(config.followRedirects()).isEqualTo(Redirect.ALWAYS);
        }
    }

}
