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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The HTTP/3 request handler releases its body buffer on every exit, observed from a real HTTP/3 client with no
/// production seam. The handler buffers the body in an unpooled heap buffer, which the leak detector does not track
/// and the garbage collector reclaims whether or not it was released. What `release()` changes is the unpooled
/// allocator's heap counter: it counts a buffer's array from allocation until the buffer's last release, never at
/// garbage collection. So a body buffer the handler drops without releasing stays counted, and one it releases does
/// not.
///
/// The counter is JVM-wide; this module runs every test class in a fresh JVM, one class at a time, so nothing else
/// moves it here. Each test sends a 512 KiB body and asserts the counter returns to within [#SLACK] of its value
/// before the request, well under the buffer's capacity. The over-limit, stalled and torn-down cases first assert that
/// the counter does see the server's buffer while the body is held, which shows the instrument can fail.
///
/// Release is also pinned at its moment, not only eventually: a stream's teardown releases whatever is still held, so
/// a dispatch or a rejection that skipped its own release would still pass a check made after the stream closed.
class Http3BodyBufferReleaseTest {
    private static final int BODY = 512 * 1024;
    private static final int LIMIT = 768 * 1024;
    private static final long SLACK = 128 * 1024;
    private static final long BUDGET_MS = 5_000;

    private final AtomicInteger dispatchedBodyBytes = new AtomicInteger(-1);
    private final CompletableFuture<ResponseWriter> unanswered = new CompletableFuture<>();
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
                                        unanswered.complete(writer);
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

    /// The handler answers only after the check: a slow handler (a forward to another node) must not keep the buffer
    /// beside the body it was handed, and the stream is still open while it works.
    @Test
    void dispatchedRequest_releasesItsBodyBuffer_beforeTheHandlerAnswers() throws Exception {
        var answer = Http3TestClient.exchange(port, stream -> {
            send(stream, BODY, true);

            var writer = unanswered.get(5, TimeUnit.SECONDS);

            assertReleased();
            writer.ok("{}");
        });

        assertThat(answer.status()).isEqualTo("200");
        assertThat(dispatchedBodyBytes.get()).as("control: the whole body was buffered and dispatched").isEqualTo(BODY);
    }

    /// A rejection releases the buffer when it answers, not when the stream later closes. Over a real connection the
    /// stream closes moments after the answer, too soon to tell the two apart, so this drives the handler in an
    /// `EmbeddedChannel`, which stays open until the test closes it.
    @Test
    void rejection_releasesTheBodyBuffer_whileTheStreamIsStillOpen() {
        var channel = new EmbeddedChannel(new Http3Server.Http3RequestHandler((_, writer) -> writer.ok("{}"),
                                                                              LIMIT,
                                                                              timeSpan(60).seconds(),
                                                                              BodyRateLimit.bodyRateLimit(0,
                                                                                                          BodyRateLimit.DEFAULT_GRACE,
                                                                                                          BodyRateLimit.DEFAULT_WINDOW)));
        var before = usedHeap();
        var headers = new DefaultHttp3Headers();

        headers.method("POST");
        headers.path("/");
        channel.writeInbound(new DefaultHttp3HeadersFrame(headers));
        channel.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[BODY])));

        assertThat(usedHeap() - before).as("control: the counter sees the held body").isGreaterThanOrEqualTo(BODY);

        channel.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[LIMIT - BODY + 1])));

        Object first = channel.readOutbound();

        assertThat(first).isInstanceOf(Http3HeadersFrame.class);
        assertThat(((Http3HeadersFrame) first).headers().status().toString()).isEqualTo("413");
        assertThat(channel.isOpen()).as("control: the stream has not closed").isTrue();
        assertThat(usedHeap() - before).as("released at the rejection").isLessThan(SLACK);
        channel.finishAndReleaseAll();
    }

    /// Input that closes on a body with no header section releases the body at once. A real HTTP/3 stream never reaches
    /// this state, because Netty's request-stream validation refuses DATA before HEADERS; it is pinned here at the
    /// handler level, in an `EmbeddedChannel` that stays open until the test closes it.
    @Test
    void inputClosedOnABodyWithoutHeaders_releasesTheBodyBuffer_whileTheStreamIsStillOpen() {
        var channel = new EmbeddedChannel(new Http3Server.Http3RequestHandler((_, writer) -> writer.ok("{}"),
                                                                              LIMIT,
                                                                              timeSpan(60).seconds(),
                                                                              BodyRateLimit.bodyRateLimit(0,
                                                                                                          BodyRateLimit.DEFAULT_GRACE,
                                                                                                          BodyRateLimit.DEFAULT_WINDOW)));
        var before = usedHeap();

        channel.writeInbound(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[BODY])));

        assertThat(usedHeap() - before).as("control: the counter sees the held body").isGreaterThanOrEqualTo(BODY);

        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);

        assertThat(channel.isOpen()).as("control: the stream has not closed").isTrue();
        assertThat(usedHeap() - before).as("released when the input closed").isLessThan(SLACK);
        channel.finishAndReleaseAll();
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
