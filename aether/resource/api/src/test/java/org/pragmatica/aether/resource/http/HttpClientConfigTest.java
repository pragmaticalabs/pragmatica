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

    @Nested
    class WithMethods {

        @Test
        void withBaseUrl_returnsNewConfig_withUpdatedUrl() {
            var config = httpClientConfig().unwrap();

            var updated = config.withBaseUrl("https://new.api.com");

            assertThat(updated.baseUrl().isPresent()).isTrue();
            updated.baseUrl().onPresent(url -> assertThat(url).isEqualTo("https://new.api.com"));
            assertThat(updated.connectTimeout()).isEqualTo(config.connectTimeout());
        }

        @Test
        void withConnectTimeout_returnsNewConfig_withUpdatedTimeout() {
            var config = httpClientConfig().unwrap();
            var newTimeout = timeSpan(2).seconds();

            var updated = config.withConnectTimeout(newTimeout);

            assertThat(updated.connectTimeout()).isEqualTo(newTimeout);
            assertThat(updated.requestTimeout()).isEqualTo(config.requestTimeout());
        }

        @Test
        void withRequestTimeout_returnsNewConfig_withUpdatedTimeout() {
            var config = httpClientConfig().unwrap();
            var newTimeout = timeSpan(60).seconds();

            var updated = config.withRequestTimeout(newTimeout);

            assertThat(updated.requestTimeout()).isEqualTo(newTimeout);
            assertThat(updated.connectTimeout()).isEqualTo(config.connectTimeout());
        }

        @Test
        void withFollowRedirects_returnsNewConfig_withUpdatedPolicy() {
            var config = httpClientConfig().unwrap();

            var updated = config.withFollowRedirects(Redirect.NEVER);

            assertThat(updated.followRedirects()).isEqualTo(Redirect.NEVER);
        }

        @Test
        void withJson_returnsNewConfig_withJsonConfig() {
            var config = httpClientConfig().unwrap();
            var json = JsonConfig.jsonConfig();

            var updated = config.withJson(json);

            assertThat(updated.json().isPresent()).isTrue();
        }

        @Test
        void withDefaultHeaders_returnsNewConfig_withHeaders() {
            var config = httpClientConfig().unwrap();
            var headers = Map.of("X-Custom", "value");

            var updated = config.withDefaultHeaders(headers);

            assertThat(updated.defaultHeaders()).containsEntry("X-Custom", "value");
            assertThat(config.defaultHeaders()).isEmpty();
        }

        @Test
        void withMethods_canBeChained_toCustomizeMultipleFields() {
            var config = httpClientConfig().unwrap()
                .withBaseUrl("https://api.example.com")
                .withConnectTimeout(timeSpan(5).seconds())
                .withFollowRedirects(Redirect.NEVER)
                .withDefaultHeaders(Map.of("Accept", "application/json"));

            assertThat(config.baseUrl().isPresent()).isTrue();
            assertThat(config.connectTimeout()).isEqualTo(timeSpan(5).seconds());
            assertThat(config.followRedirects()).isEqualTo(Redirect.NEVER);
            assertThat(config.defaultHeaders()).containsEntry("Accept", "application/json");
        }
    }
}
