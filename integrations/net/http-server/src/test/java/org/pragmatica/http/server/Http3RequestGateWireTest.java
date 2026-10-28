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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The ingest gate on HTTP/3 over a real QUIC connection, through the real stream pipeline: the same classifier and the
/// same assertions as [RequestGateWireTest], so each HTTP/1.1 behaviour has its HTTP/3 twin. The request is classified on
/// its HEADERS frame, before any DATA frame is read:
/// - `/denied`: refused 401, its input reset, never dispatched;
/// - `/small` 1 MiB, `/big` 100 MiB: the same body passes one and is refused 413 on the other, with a declared
///   `content-length` and without one (HTTP/3 has no chunked encoding: the length is unknown until the stream ends);
/// - `/timed`: upload bounds (idle 300 ms, 1000 B/s minimum after 300 ms): 408;
/// - `/idle`: the same idle bound with the rate bound off, so only the idle bound can answer;
/// - `/duration`: a 700 ms duration bound that a steady sender trips.
class Http3RequestGateWireTest {
    private static final int MIB = 1024 * 1024;
    private static final long TIMED_BOUND_MS = 300;

    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicLong bodyBytes = new AtomicLong();
    private Http3Server server;
    private int port;

    private final RequestClassifier classifier = head -> switch (head.path()) {
        case "/denied" -> Disposition.reject(writer -> writer.error(HttpStatus.UNAUTHORIZED, "no credentials"));
        case "/big" -> Disposition.aggregate(100L * MIB);
        case "/idle" -> Disposition.aggregate(MIB,
                                              BodyBounds.bodyBounds(timeSpan(TIMED_BOUND_MS).millis(),
                                                                    BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()),
                                                                    timeSpan(0).millis()),
                                              releases::incrementAndGet);
        case "/timed" -> Disposition.aggregate(MIB,
                                               BodyBounds.bodyBounds(timeSpan(TIMED_BOUND_MS).millis(),
                                                                     BodyRateLimit.bodyRateLimit(1000,
                                                                                                 timeSpan(TIMED_BOUND_MS).millis(),
                                                                                                 timeSpan(TIMED_BOUND_MS).millis()),
                                                                     timeSpan(0).millis()),
                                               releases::incrementAndGet);
        case "/duration" -> Disposition.aggregate(MIB,
                                                  BodyBounds.bodyBounds(timeSpan(10).seconds(),
                                                                        BodyRateLimit.bodyRateLimit(1, timeSpan(10).seconds(), timeSpan(10).seconds()),
                                                                        timeSpan(700).millis()),
                                                  releases::incrementAndGet);
        default -> Disposition.aggregate(MIB);
    };

    private void start() {
        var config = HttpServerConfig.httpServerConfig("h3-ingest-gate", 0).withClassifier(classifier);

        server = Http3Server.create(config,
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    (request, writer) -> {
                                        invocations.incrementAndGet();
                                        bodyBytes.set(request.body().length);
                                        writer.okText("handled " + request.body().length);
                                    })
                            .await(timeSpan(10).seconds())
                            .unwrap();
        port = Http3TestClient.boundPort(server);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// Auth before body, over HTTP/3: the request is refused on its HEADERS frame. The client then tries to send 50 MiB;
    /// the server never reads it, so the stream's flow-control window stops the client far short of that.
    @Test
    void deniedRequest_isAnswered401_neverDispatched_andItsBodyIsNotRead() throws Exception {
        start();
        var sent = new AtomicLong();

        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(headers("/denied", 50L * MIB));
            sent.set(sendUntilStalled(stream, 50 * MIB));
        });

        assertThat(answer.status()).isEqualTo("401");
        assertThat(invocations.get()).isZero();
        assertThat(sent.get()).as("the server did not read the body: the client stalled or was reset long before 50 MiB")
                              .isLessThan(20L * MIB);
    }

    @Test
    void sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute_withAndWithoutContentLength() throws Exception {
        start();
        var twoMiB = 2 * MIB;

        var big = Http3TestClient.exchange(port, stream -> body(stream, "/big", twoMiB, twoMiB));
        var bigUndeclared = Http3TestClient.exchange(port, stream -> body(stream, "/big", twoMiB, -1));

        assertThat(big.status()).isEqualTo("200");
        assertThat(big.body()).isEqualTo("handled " + twoMiB);
        assertThat(bigUndeclared.status()).isEqualTo("200");
        assertThat(bigUndeclared.body()).isEqualTo("handled " + twoMiB);
        assertThat(invocations.get()).as("control: both reached the handler").isEqualTo(2);

        var small = Http3TestClient.exchange(port, stream -> body(stream, "/small", twoMiB, twoMiB));
        var smallUndeclared = Http3TestClient.exchange(port, stream -> body(stream, "/small", twoMiB, -1));

        assertThat(small.status()).isEqualTo("413");
        assertThat(smallUndeclared.status()).isEqualTo("413");
        assertThat(invocations.get()).as("neither refused request reached the handler").isEqualTo(2);
    }

    @Test
    void bodyAtTheLimitPasses_oneByteOverIsRefused() throws Exception {
        start();

        assertThat(Http3TestClient.exchange(port, stream -> body(stream, "/small", MIB, -1)).status()).isEqualTo("200");
        assertThat(Http3TestClient.exchange(port, stream -> body(stream, "/small", MIB + 1, -1)).status()).isEqualTo("413");
        assertThat(Http3TestClient.exchange(port, stream -> body(stream, "/small", MIB, MIB)).status()).as("declared length at the limit")
                                                                                                         .isEqualTo("200");
        assertThat(Http3TestClient.exchange(port, stream -> body(stream, "/small", MIB + 1, MIB + 1)).status()).as("declared length over the limit")
                                                                                                                 .isEqualTo("413");
    }

    @Test
    void uploadBodyBelowTheMinimumRate_isAnswered408_andNeverDispatched() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> pace(stream, "/timed", 1, 100, 60));

        assertThat(answer.status()).isEqualTo("408");
        assertThat(answer.body()).contains("minimum rate");
        assertThat(invocations.get()).isZero();
        assertThat(releases.get()).as("the permit of the refused upload was released").isEqualTo(1);
    }

    @Test
    void uploadBodyIdleForTheUploadIdleBound_isAnswered408() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(headers("/idle", 1000));
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[10])));
        });

        assertThat(answer.status()).isEqualTo("408");
        assertThat(answer.body()).contains("idle");
        assertThat(invocations.get()).isZero();
    }

    @Test
    void uploadBodyOverTheRouteDuration_isAnswered408_evenAtASteadyRate() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> pace(stream, "/duration", 8, 50, 100));

        assertThat(answer.status()).isEqualTo("408");
        assertThat(answer.body()).contains("max_duration");
        assertThat(invocations.get()).isZero();
    }

    @Test
    void uploadBodyArrivingPromptly_isDispatched_andItsPermitReleased() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> body(stream, "/timed", 4096, 4096));

        assertThat(answer.status()).isEqualTo("200");
        assertThat(bodyBytes.get()).isEqualTo(4096);
        assertThat(releases.get()).isEqualTo(1);
    }

    // ---- client helpers

    private DefaultHttp3HeadersFrame headers(String path, long contentLength) {
        var frame = new DefaultHttp3HeadersFrame();

        frame.headers().method("POST").path(path).authority("127.0.0.1:" + port).scheme("https");
        if (contentLength >= 0) {
            frame.headers().add("content-length", String.valueOf(contentLength));
        }

        return frame;
    }

    /// Sends `size` bytes in 64 KiB frames and ends the stream, stopping at the first write the server's window or a
    /// reset refuses. `declared` is the `content-length`, or negative for none.
    private void body(QuicStreamChannel stream, String path, int size, long declared) {
        stream.write(headers(path, declared));
        var remaining = size;
        var chunk = 64 * 1024;

        while (remaining > 0 && stream.isActive()) {
            var frame = Math.min(chunk, remaining);
            var written = stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[frame])));

            if (!awaitWrite(written)) {
                return;
            }

            remaining -= frame;
        }

        if (stream.isActive()) {
            stream.shutdownOutput();
        }
    }

    /// Writes 64 KiB frames until one does not complete within 2 s (the window is full) or `limit` bytes were sent;
    /// returns the bytes whose write completed.
    private static long sendUntilStalled(QuicStreamChannel stream, int limit) {
        var sent = 0L;

        while (sent < limit && stream.isActive()) {
            var written = stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[64 * 1024])));

            if (!awaitWrite(written)) {
                return sent;
            }

            sent += 64 * 1024;
        }

        return sent;
    }

    /// `frames` frames of `frameSize` bytes, one every `intervalMs`, under a declared length far above what is sent.
    private void pace(QuicStreamChannel stream, String path, int frameSize, long intervalMs, int frames) {
        stream.write(headers(path, 100_000));
        try {
            for (int index = 0; index < frames && stream.isActive(); index++) {
                stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[frameSize])));
                Thread.sleep(intervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitWrite(io.netty.channel.ChannelFuture written) {
        try {
            return written.await(2, TimeUnit.SECONDS) && written.isSuccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }
}
