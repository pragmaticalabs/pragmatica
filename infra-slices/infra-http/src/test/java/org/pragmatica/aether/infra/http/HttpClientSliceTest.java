package org.pragmatica.aether.infra.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import java.net.http.HttpClient.Redirect;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientSliceTest {
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
    void httpClientSlice_factory_createsInstance() {
        var client = HttpClientSlice.httpClientSlice();

        assertThat(client).isNotNull();
        assertThat(client.config()).isNotNull();
        assertThat(client.config().baseUrl().isEmpty()).isTrue();
    }

    @Test
    void httpClientSlice_factoryWithConfig_usesConfig() {
        var config = HttpClientConfig.httpClientConfig("https://api.example.com").unwrap();
        var client = HttpClientSlice.httpClientSlice(config);

        assertThat(client.config()).isEqualTo(config);
        assertThat(client.config().baseUrl().fold(() -> "", v -> v)).isEqualTo("https://api.example.com");
    }

    @Test
    void httpClientSlice_start_succeeds() {
        var client = HttpClientSlice.httpClientSlice();

        client.start()
            .await()
            .onFailureRun(Assertions::fail);
    }

    @Test
    void httpClientSlice_stop_succeeds() {
        var client = HttpClientSlice.httpClientSlice();

        client.stop()
            .await()
            .onFailureRun(Assertions::fail);
    }

    @Test
    void httpClientSlice_methods_returnsEmptyList() {
        var client = HttpClientSlice.httpClientSlice();

        assertThat(client.methods()).isEmpty();
    }
}
