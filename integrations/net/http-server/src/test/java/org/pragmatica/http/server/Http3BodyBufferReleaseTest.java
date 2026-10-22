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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// F8(f): the HTTP/3 request handler releases its body buffer on every exit, observed from a real HTTP/3 client with no
/// production seam. The handler buffers the body in an unpooled heap buffer, which the leak detector does not track
/// and the garbage collector reclaims whether or not it was released. What `release()` changes is the unpooled
/// allocator's heap counter: it counts a buffer's array from allocation until the buffer's last release, never at
/// garbage collection. So a body buffer the handler drops without releasing stays counted, and one it releases does
/// not.
///
/// The counter is JVM-wide; this module runs every test class in a fresh JVM, one class at a time, so nothing else
/// moves it here. Each test sends a 512 KiB body and asserts the counter returns to within [#SLACK] of its value
/// before the request, well under the buffer's capacity. The stalled and torn-down cases first assert that the counter
/// does see the server's buffer while the body is held, which shows the instrument can fail.
class Http3BodyBufferReleaseTest {
    private static final int BODY = 512 * 1024;
    private static final int LIMIT = 768 * 1024;
    private static final long SLACK = 128 * 1024;
    private static final long BUDGET_MS = 5_000;

    private final AtomicInteger dispatchedBodyBytes = new AtomicInteger(-1);
    private Http3Server server;
    private int port;
    private long baseline;

    @BeforeEach
    void start() {
        var config = HttpServerConfig.httpServerConfig("h3-release", 0)
                                     .withMaxContentLength(LIMIT)
                                     .withReadTimeout(timeSpan(3).seconds())
                                     .withBodyRateLimit(BodyRateLimit.bodyRateLimit(0,
                                                                                    BodyRateLimit.DEFAULT_GRACE,
                                                                                    BodyRateLimit.DEFAULT_WINDOW));

        server = Http3Server.create(config,
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    (request, writer) -> {
                                        dispatchedBodyBytes.set(request.body().length);
                                        writer.ok("{}");
                                    })
                            .await(timeSpan(10).seconds())
                            .unwrap();
        port = Http3TestClient.boundPort(server);
        baseline = usedHeap();
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    @Test
    void dispatchedRequest_releasesItsBodyBuffer() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> send(stream, BODY, true));

        assertThat(answer.status()).isEqualTo("200");
        assertThat(dispatchedBodyBytes.get()).as("control: the whole body was buffered and dispatched").isEqualTo(BODY);
        assertReleased();
    }

    @Test
    void requestOverTheLimit_releasesTheBodyBufferedBeforeIt() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> {
            send(stream, BODY, false);
            awaitHeld();
            // one byte past the limit
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[LIMIT - BODY + 1])))
                  .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        });

        assertThat(answer.status()).isEqualTo("413");
        assertReleased();
    }

    @Test
    void requestIdleForTheReadTimeout_releasesItsBodyBuffer() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> {
            send(stream, BODY, false);
            awaitHeld();
        });

        assertThat(answer.status()).isEqualTo("408");
        assertReleased();
    }

    @Test
    void requestTornDownBeforeItsBodyEnded_releasesItsBodyBuffer() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> {
            send(stream, BODY, false);
            awaitHeld();
            stream.parent().close().sync();
        });

        assertThat(answer.status()).as("control: the connection went before any response").isEqualTo("closed");
        assertReleased();
    }

    private void send(QuicStreamChannel stream, int size, boolean last) {
        stream.write(Http3TestClient.postHeaders(port));

        var written = stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[size])));

        if (last) {
            written.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }
    }

    /// Control: the server holds the body, and the counter sees it.
    private void awaitHeld() throws InterruptedException {
        assertThat(eventually(used -> used - baseline >= BODY))
            .as("control: the unpooled heap counter sees the server's %d-byte body buffer (baseline %d, now %d)",
                BODY,
                baseline,
                usedHeap())
            .isTrue();
    }

    private void assertReleased() throws InterruptedException {
        assertThat(eventually(used -> used - baseline < SLACK))
            .as("the body buffer was released (baseline %d, now %d)", baseline, usedHeap())
            .isTrue();
    }

    private static boolean eventually(LongPredicate condition) throws InterruptedException {
        var deadline = System.nanoTime() + BUDGET_MS * 1_000_000;

        while (System.nanoTime() < deadline) {
            if (condition.test(usedHeap())) {
                return true;
            }

            Thread.sleep(10);
        }

        return condition.test(usedHeap());
    }

    private static long usedHeap() {
        return UnpooledByteBufAllocator.DEFAULT.metric().usedHeapMemory();
    }
}
