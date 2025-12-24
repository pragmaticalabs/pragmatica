package org.pragmatica.http.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServerConfigTest {

    @Test
    void http_creates_plain_http_config() {
        var config = HttpServerConfig.http(8080);

        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.maxContentLength()).isEqualTo(HttpServerConfig.DEFAULT_MAX_CONTENT);
        assertThat(config.tls().isPresent()).isFalse();
        assertThat(config.isTlsEnabled()).isFalse();
    }

    @Test
    void http_with_custom_max_content() {
        var config = HttpServerConfig.http(9000, 2048);

        assertThat(config.port()).isEqualTo(9000);
        assertThat(config.maxContentLength()).isEqualTo(2048);
        assertThat(config.isTlsEnabled()).isFalse();
    }

    @Test
    void https_creates_tls_config() {
        var tls = TlsConfig.selfSigned();
        var config = HttpServerConfig.https(8443, tls);

        assertThat(config.port()).isEqualTo(8443);
        assertThat(config.maxContentLength()).isEqualTo(HttpServerConfig.DEFAULT_MAX_CONTENT);
        assertThat(config.tls().isPresent()).isTrue();
        assertThat(config.isTlsEnabled()).isTrue();
    }

    @Test
    void https_with_custom_max_content() {
        var tls = TlsConfig.selfSigned();
        var config = HttpServerConfig.https(443, tls, 4096);

        assertThat(config.port()).isEqualTo(443);
        assertThat(config.maxContentLength()).isEqualTo(4096);
        assertThat(config.isTlsEnabled()).isTrue();
    }

    @Test
    void defaultConfig_uses_port_8080() {
        var config = HttpServerConfig.defaultConfig();

        assertThat(config.port()).isEqualTo(HttpServerConfig.DEFAULT_PORT);
        assertThat(config.isTlsEnabled()).isFalse();
    }
}
