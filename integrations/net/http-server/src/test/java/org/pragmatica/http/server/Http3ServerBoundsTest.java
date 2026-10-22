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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// F6: the server's configured body limit, read timeout and minimum body rate reach every request stream a real HTTP/3
/// connection opens, through the QUIC codec's connection initializer and the connection handler's stream initializer.
/// Each bound is set to a value the server default would answer differently, so a stream handler built from a default
/// config fails the assertion:
/// - body limit 1 KiB (default 64 KiB): a 4 KiB body is answered 413, where the default dispatches it;
/// - read timeout 1 s (default 60 s): a body that stalls is answered 408, where the default leaves it to QUIC's 5 s idle
///   close, which answers nothing;
/// - minimum rate 100 kB/s after a 2 s grace (default 1 KiB/s after 10 s): a body trickled at 100 B/s is answered 408
///   at about 2 s, where the default dispatches it before its grace ends. The grace is longer than the read timeout so
///   that a stalled body meets the read timeout first.
class Http3ServerBoundsTest {
    private static final int LIMIT = 1024;
    private static final int TRICKLE_CHUNK = 20;
    private static final int TRICKLE_CHUNKS = 15;
    private static final long TRICKLE_INTERVAL_MS = 200;

    private Http3Server server;
    private int port;

    @BeforeEach
    void start() {
        var config = HttpServerConfig.httpServerConfig("h3-bounds", 0)
                                     .withMaxContentLength(LIMIT)
                                     .withReadTimeout(timeSpan(1).seconds())
                                     .withBodyRateLimit(BodyRateLimit.bodyRateLimit(100_000,
                                                                                    timeSpan(2).seconds(),
                                                                                    timeSpan(1).seconds()));

        server = Http3Server.create(config,
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    (_, writer) -> writer.ok("{}"))
                            .await(timeSpan(10).seconds())
                            .unwrap();
        port = Http3TestClient.boundPort(server);
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    /// Control for the three bounds: a small body sent at once is dispatched.
    @Test
    void smallBody_isDispatched() throws Exception {
        assertThat(Http3TestClient.exchange(port, stream -> body(stream, 16)).status()).isEqualTo("200");
    }

    @Test
    void bodyOverTheConfiguredLimit_isAnswered413() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> body(stream, 4 * LIMIT));

        assertThat(answer.status()).isEqualTo("413");
        assertThat(answer.body()).contains("1024-byte limit");
    }

    @Test
    void bodyIdleForTheConfiguredReadTimeout_isAnswered408() throws Exception {
        var answer = Http3TestClient.exchange(port, this::stall);

        assertThat(answer.status()).isEqualTo("408");
        assertThat(answer.body()).contains("idle");
    }

    @Test
    void bodyBelowTheConfiguredMinimumRate_isAnswered408() throws Exception {
        var answer = Http3TestClient.exchange(port, this::trickle);

        assertThat(answer.status()).isEqualTo("408");
        assertThat(answer.body()).as("the rate bound, not the read timeout: a frame arrives every 200 ms")
                                 .contains("minimum rate");
    }

    private void body(QuicStreamChannel stream, int size) {
        stream.write(Http3TestClient.postHeaders(port));
        stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[size])))
              .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    /// A first body frame, then nothing: the stream stays open with its body incomplete.
    private void stall(QuicStreamChannel stream) {
        stream.write(Http3TestClient.postHeaders(port));
        stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[16])));
    }

    /// 300 bytes over 3 s, one frame every 200 ms: inside the limit, never idle for the read timeout, past the
    /// configured grace and well inside the default one.
    private void trickle(QuicStreamChannel stream) throws InterruptedException {
        stream.writeAndFlush(Http3TestClient.postHeaders(port));

        for (int i = 0; i < TRICKLE_CHUNKS; i++) {
            Thread.sleep(TRICKLE_INTERVAL_MS);
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[TRICKLE_CHUNK])));
        }

        stream.shutdownOutput();
    }
}
