package org.pragmatica.http.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.server.HttpServerConfig.httpServerConfig;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class HttpServerConfigTest {

    @Test
    void httpServerConfig_creates_http_config_with_defaults() {
        var config = httpServerConfig("test", 8080);

        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.name()).isEqualTo("test");
        assertThat(config.tls().isPresent()).isFalse();
        assertThat(config.maxContentLength()).isEqualTo(65536);
    }

    @Test
    void withMaxContentLength_customizes_max_content() {
        var config = httpServerConfig("test", 9000)
            .withMaxContentLength(2048);

        assertThat(config.port()).isEqualTo(9000);
        assertThat(config.maxContentLength()).isEqualTo(2048);
    }

    @Test
    void withTls_creates_tls_config() {
        var tls = TlsConfig.selfSigned();
        var config = httpServerConfig("test", 8443)
            .withTls(tls);

        assertThat(config.port()).isEqualTo(8443);
        assertThat(config.tls().isPresent()).isTrue();
    }

    @Test
    void withTls_and_maxContentLength_combines_options() {
        var tls = TlsConfig.selfSigned();
        var config = httpServerConfig("test", 443)
            .withTls(tls)
            .withMaxContentLength(4096);

        assertThat(config.port()).isEqualTo(443);
        assertThat(config.maxContentLength()).isEqualTo(4096);
        assertThat(config.tls().isPresent()).isTrue();
    }

    @Test
    void withChunkedWrite_enables_chunked_transfer() {
        var config = httpServerConfig("test", 8080)
            .withChunkedWrite();

        assertThat(config.chunkedWriteEnabled()).isTrue();
    }

    @Test
    void webSocketEndpoints_is_empty_by_default() {
        var config = httpServerConfig("test", 8080);

        assertThat(config.webSocketEndpoints()).isEmpty();
    }

    /// #2180: the default is no bind host (every interface), and every other `with*` keeps a configured one: a
    /// copy that dropped it would silently widen the listener back to every interface.
    @Test
    void bindHost_isNoneByDefault_andSurvivesEveryOtherWith() {
        assertThat(httpServerConfig("test", 8080).bindHost().isPresent()).isFalse();

        var config = httpServerConfig("test", 8080).withBindHost("127.0.0.1")
                                                    .withMaxContentLength(2048)
                                                    .withTls(TlsConfig.selfSigned())
                                                    .withWebSocket(webSocketEndpoint("/ws", () -> (_, _) -> {}))
                                                    .withChunkedWrite()
                                                    .withReadTimeout(timeSpan(5).seconds())
                                                    .withResponseTimeout(timeSpan(5).seconds())
                                                    .withBodyRateLimit(BodyRateLimit.DEFAULT)
                                                    .withClassifier(RequestClassifier.aggregateAll(1024));

        assertThat(config.bindHost().or("none")).isEqualTo("127.0.0.1");
    }

    /// #2180: the host is resolved when the address is asked for: the wildcard when none, the resolved literal
    /// when set, a typed failure for a blank host (which `InetAddress` would otherwise read as loopback).
    @Test
    void bindAddress_isWildcardByDefault_theHostWhenSet_andRefusesABlankHost() {
        assertThat(httpServerConfig("test", 8080).bindAddress().unwrap().getAddress().isAnyLocalAddress()).isTrue();
        assertThat(httpServerConfig("test", 8080).withBindHost("127.0.0.1").bindAddress().unwrap())
            .isEqualTo(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080));
        assertThat(httpServerConfig("test", 8080).withBindHost("").bindAddress().isFailure()).isTrue();
    }
}
