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
    void httpClientConfig_withMethods_createNewInstances() {
        var original = HttpClientConfig.httpClientConfig().unwrap();

        var withBaseUrl = original.withBaseUrl("https://example.com");
        assertThat(withBaseUrl.baseUrl().fold(() -> "", v -> v)).isEqualTo("https://example.com");
        assertThat(original.baseUrl().isEmpty()).isTrue();

        var withTimeout = original.withConnectTimeout(TimeSpan.timeSpan(5).seconds());
        assertThat(withTimeout.connectTimeout()).isEqualTo(TimeSpan.timeSpan(5).seconds());
        assertThat(original.connectTimeout()).isEqualTo(TimeSpan.timeSpan(10).seconds());

        var withRedirect = original.withFollowRedirects(Redirect.NEVER);
        assertThat(withRedirect.followRedirects()).isEqualTo(Redirect.NEVER);
        assertThat(original.followRedirects()).isEqualTo(Redirect.NORMAL);
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

    @Test
    void httpClientConfig_withJson_preservesJsonConfig() {
        var json = JsonConfig.jsonConfig();
        var config = HttpClientConfig.httpClientConfig().unwrap()
            .withJson(json);

        JsonConfig actual = config.json().fold(() -> null, v -> v);
        assertThat(actual).isEqualTo(json);
    }

    @Test
    void httpClientConfig_withDefaultHeaders_preservesHeaders() {
        var config = HttpClientConfig.httpClientConfig().unwrap()
            .withDefaultHeaders(Map.of("Authorization", "Bearer token"));

        assertThat(config.defaultHeaders()).containsEntry("Authorization", "Bearer token");
    }

    @Test
    void httpClientConfig_withMethods_preserveJsonAndHeaders() {
        var json = JsonConfig.jsonConfig();
        var config = HttpClientConfig.httpClientConfig().unwrap()
            .withJson(json)
            .withDefaultHeaders(Map.of("X-Key", "val"));

        var updated = config.withBaseUrl("https://new.example.com");

        JsonConfig actualJson = updated.json().fold(() -> null, v -> v);
        assertThat(actualJson).isEqualTo(json);
        assertThat(updated.defaultHeaders()).containsEntry("X-Key", "val");
        assertThat(updated.baseUrl().fold(() -> "", v -> v)).isEqualTo("https://new.example.com");
    }
}
