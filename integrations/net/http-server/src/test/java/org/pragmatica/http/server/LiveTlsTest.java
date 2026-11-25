/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http.server;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import io.netty.handler.ssl.SslContext;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.http.server.TlsCertificates.bundle;
import static org.pragmatica.http.server.TlsCertificates.http1Client;
import static org.pragmatica.http.server.TlsCertificates.http1Server;
import static org.pragmatica.http.server.TlsCertificates.http3Client;
import static org.pragmatica.http.server.TlsCertificates.http3Server;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2032: a [LiveTls] holder admits only a context its protocol can serve, a swap installs the new context, and a
/// refused swap leaves the current one installed. The wire behaviour is in [LiveTlsRotationTest].
class LiveTlsTest {
    private static final SslContext FIRST = http1Server(bundle("unit-first"));
    private static final SslContext SECOND = http1Server(bundle("unit-second"));

    @Test
    void liveTls_serverContext_isCurrent() {
        assertThat(LiveTls.liveTls(FIRST).unwrap().current()).isSameAs(FIRST);
    }

    @Test
    void liveTls_clientContext_isRefused() throws Exception {
        assertThat(refusal(LiveTls.liveTls(http1Client())))
            .isEqualTo(HttpServerError.TlsContextRefused.NOT_HTTP1_SERVER);
    }

    /// A QUIC context builds no `SslHandler`, so it cannot be an HTTP/1.1 listener's context.
    @Test
    void liveTls_quicServerContext_isRefused() {
        SslContext quic = http3Server(bundle("unit-quic"));

        assertThat(refusal(LiveTls.liveTls(quic)))
            .isEqualTo(HttpServerError.TlsContextRefused.NOT_HTTP1_SERVER);
    }

    @Test
    void liveTls_unbuildableConfig_fails() {
        var config = TlsConfig.server(Path.of("/missing/cert.pem"), Path.of("/missing/key.pem"));

        assertThat(LiveTls.liveTls(config).isFailure()).isTrue();
    }

    @Test
    void liveQuicTls_clientContext_isRefused() {
        assertThat(refusal(LiveTls.liveQuicTls(http3Client())))
            .isEqualTo(HttpServerError.TlsContextRefused.NOT_HTTP3_SERVER);
    }

    @Test
    void swap_serverContext_installsIt() {
        var holder = LiveTls.liveTls(FIRST).unwrap();

        assertThat(holder.swap(SECOND).isSuccess()).isTrue();
        assertThat(holder.current()).isSameAs(SECOND);
    }

    @Test
    void swap_refusedContext_keepsCurrentAndSaysWhy() throws Exception {
        var holder = LiveTls.liveTls(FIRST).unwrap();

        assertThat(refusal(holder.swap(http1Client())))
            .isEqualTo(HttpServerError.TlsContextRefused.NOT_HTTP1_SERVER);
        assertThat(holder.current()).isSameAs(FIRST);
    }

    @Test
    void swap_quicServerContext_installsIt_refusedQuicContext_keepsCurrent() {
        var first = http3Server(bundle("unit-quic-first"));
        var second = http3Server(bundle("unit-quic-second"));
        var holder = LiveTls.liveQuicTls(first).unwrap();

        assertThat(refusal(holder.swap(http3Client())))
            .isEqualTo(HttpServerError.TlsContextRefused.NOT_HTTP3_SERVER);
        assertThat(holder.current()).isSameAs(first);
        assertThat(holder.swap(second).isSuccess()).isTrue();
        assertThat(holder.current()).isSameAs(second);
    }

    /// The existing entry point refuses a client QUIC context with the typed cause, before any bind; Netty's builder
    /// would otherwise throw `IllegalArgumentException` from `sslContext(ctx)`.
    @Test
    void http3Server_clientQuicContext_failsWithTypedRefusal() {
        var outcome = HttpServer.http3Server(HttpServerConfig.httpServerConfig("h3-client-ctx", 0),
                                             http3Client(),
                                             (_, writer) -> writer.okText("must not serve"))
                                .await(timeSpan(10).seconds());

        outcome.onSuccess(HttpServer::stop);
        assertThat(refusal(outcome)).isEqualTo(HttpServerError.TlsContextRefused.NOT_HTTP3_SERVER);
    }

    /// `withTls` and `withLiveTls` each clear the other, so a config carries at most one TLS source.
    @Test
    void config_withTlsAndWithLiveTls_clearEachOther() {
        var holder = LiveTls.liveTls(FIRST).unwrap();
        var base = HttpServerConfig.httpServerConfig("tls-sources", 0);
        var live = base.withTls(TlsConfig.selfSignedServer()).withLiveTls(holder);
        var fixed = live.withTls(TlsConfig.selfSignedServer());

        assertThat(live.tls().isEmpty()).isTrue();
        assertThat(live.liveTls().or(() -> null)).isSameAs(holder);
        assertThat(fixed.liveTls().isEmpty()).isTrue();
        assertThat(fixed.tls().isPresent()).isTrue();
    }

    /// Every other `with...` keeps the holder: a dropped one would turn a TLS listener into a plain-HTTP one.
    @Test
    void config_otherWithers_keepTheHolder() {
        var holder = LiveTls.liveTls(FIRST).unwrap();
        var live = HttpServerConfig.httpServerConfig("tls-kept", 0).withLiveTls(holder);
        var withers = Map.<String, UnaryOperator<HttpServerConfig>> of("withMaxContentLength",
                                                                       config -> config.withMaxContentLength(1024),
                                                                       "withWebSocket",
                                                                       config -> config.withWebSocket(webSocketEndpoint("/ws", () -> null)),
                                                                       "withChunkedWrite",
                                                                       HttpServerConfig::withChunkedWrite,
                                                                       "withBodyRateLimit",
                                                                       config -> config.withBodyRateLimit(BodyRateLimit.DEFAULT),
                                                                       "withReadTimeout",
                                                                       config -> config.withReadTimeout(timeSpan(1).seconds()),
                                                                       "withResponseTimeout",
                                                                       config -> config.withResponseTimeout(timeSpan(1).seconds()),
                                                                       "withClassifier",
                                                                       config -> config.withClassifier(RequestClassifier.aggregateAll(1024)),
                                                                       "withBindHost",
                                                                       config -> config.withBindHost("127.0.0.1"));
        var softly = new SoftAssertions();

        withers.forEach((name, wither) -> softly.assertThat(wither.apply(live).liveTls().or(() -> null))
                                                .as(name)
                                                .isSameAs(holder));
        softly.assertAll();
    }

    /// The cause a refused result carries, or `null` when it succeeded (the assertion then names the missing refusal).
    private static Cause refusal(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }
}
