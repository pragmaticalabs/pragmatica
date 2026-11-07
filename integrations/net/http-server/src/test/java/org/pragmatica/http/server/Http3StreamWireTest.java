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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Streamed request bodies ([Disposition.Stream]) on HTTP/3 over a real QUIC connection, through the real stream
/// pipeline: the HTTP/3 twin of each test in [RequestGateStreamWireTest], with the same classifier.
class Http3StreamWireTest {
    private static final int KIB = 1024;
    private static final int MIB = 1024 * KIB;
    /// The server's stream flow-control window (`initialMaxStreamDataBidirectionalRemote`): reading the stream stops while a
    /// chunk is pending, so QUIC grants no more credit and no accept is ever given more than this.
    private static final long CHUNK_BOUND = 1_000_000;
    /// The direct memory the server may gain while a slow sink takes 64 MiB: about one stream window plus the allocator's
    /// chunk granularity (the in-process client shares the allocator, and its sending is bounded by the same window). A
    /// server that kept reading would hold most of the 64 MiB.
    private static final long HELD_BOUND = 24L * MIB;

    private final List<RecordingSink> sinks = new CopyOnWriteArrayList<>();
    private volatile Supplier<RecordingSink> nextSink = RecordingSink::new;
    private Http3Server server;
    private int port;

    private final RequestClassifier classifier = head -> switch (head.path()) {
        case "/small" -> new Disposition.Stream(MIB, Option.none(), sink());
        case "/idle" -> Disposition.stream(MIB,
                                           BodyBounds.bodyBounds(timeSpan(300).millis(),
                                                                 BodyRateLimit.bodyRateLimit(0, timeSpan(1).seconds(), timeSpan(1).seconds()),
                                                                 timeSpan(0).millis()),
                                           sink());
        case "/timed" -> Disposition.stream(MIB,
                                            BodyBounds.bodyBounds(timeSpan(300).millis(),
                                                                  BodyRateLimit.bodyRateLimit(1000,
                                                                                              timeSpan(300).millis(),
                                                                                              timeSpan(300).millis()),
                                                                  timeSpan(0).millis()),
                                            sink());
        default -> new Disposition.Stream(1024L * MIB, Option.none(), sink());
    };

    private RecordingSink sink() {
        var sink = nextSink.get();

        sinks.add(sink);

        return sink;
    }

    private void start() {
        var config = HttpServerConfig.httpServerConfig("h3-stream-gate", 0).withClassifier(classifier);

        server = Http3Server.create(config,
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    (_, writer) -> writer.okText("aggregated"))
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

    /// 64 MiB of non-repeating bytes into a sink that takes ~1 ms per 16 KiB: never more than one accept outstanding, no
    /// accept given more than [#CHUNK_BOUND], the direct memory grows by at most [#HELD_BOUND], and the sink's SHA-256 is
    /// the sender's.
    @Test
    void slowSink_receives64MiB_oneAcceptAtATime_withBoundedBuffering_andTheSendersHash() throws Exception {
        nextSink = () -> new RecordingSink().slow(61).sampling(Http3StreamWireTest::usedDirectMemory);
        start();
        var baseline = usedDirectMemory();
        var size = 64L * MIB;
        var sent = new AtomicReference<String>();

        var answer = Http3TestClient.exchange(port, stream -> sent.set(sendGenerated(stream, "/stream", size)));
        var sink = sinks.getFirst();

        assertThat(answer.statusText()).isEqualTo("200");
        assertThat(sink.terminals()).containsExactly("complete");
        assertThat(sink.bytes()).isEqualTo(size);
        assertThat(sink.sha()).isEqualTo(sent.get());
        assertThat(answer.body()).isEqualTo("received " + size + " sha " + sent.get());
        System.out.printf("BOUNDS %s: accepts=%d maxChunk=%d directGained=%d%n",
                          getClass().getSimpleName(),
                          sink.accepts(),
                          sink.maxChunk(),
                          sink.maxSample() - baseline);
        assertThat(sink.maxOutstanding()).as("at most one accept outstanding").isEqualTo(1);
        assertThat(sink.maxChunk()).as("largest chunk one accept was given").isLessThanOrEqualTo(CHUNK_BOUND);
        assertThat(sink.maxSample() - baseline).as("direct memory gained (baseline %d, %d accepts, largest chunk %d)",
                                                   baseline,
                                                   sink.accepts(),
                                                   sink.maxChunk())
                                               .isLessThanOrEqualTo(HELD_BOUND);
        assertThat(sink.violations()).isEmpty();
    }

    @Test
    void declaredLengthOverTheLimit_isAnswered413_andTheSinkIsAbortedTooLarge() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> stream.writeAndFlush(headers("/small", 2L * MIB)));

        assertThat(answer.statusText()).isEqualTo("413");
        assertThat(sinks.getFirst().awaitTerminal(5)).isEqualTo("abort:TOO_LARGE");
        assertThat(sinks.getFirst().terminals()).containsExactly("abort:TOO_LARGE");
    }

    @Test
    void undeclaredBodyCrossingTheLimit_isAnswered413_andTheSinkIsAbortedTooLarge() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> body(stream, "/small", 2 * MIB, -1));
        var sink = sinks.getFirst();

        assertThat(answer.statusText()).isEqualTo("413");
        assertThat(sink.awaitTerminal(5)).isEqualTo("abort:TOO_LARGE");
        assertThat(sink.bytes()).as("no more than the limit reached the sink").isLessThanOrEqualTo(MIB);
        settle();
        assertThat(sink.terminals()).containsExactly("abort:TOO_LARGE");
        assertThat(sink.violations()).isEmpty();
    }

    @Test
    void idleBody_isAnswered408_andTheSinkIsAbortedTimedOut() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(headers("/idle", 1000));
            stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[10])));
        });

        assertThat(answer.statusText()).isEqualTo("408");
        assertThat(answer.body()).contains("idle");
        assertThat(sinks.getFirst().awaitTerminal(5)).isEqualTo("abort:TIMED_OUT");
        assertThat(sinks.getFirst().terminals()).containsExactly("abort:TIMED_OUT");
    }

    @Test
    void bodyBelowTheMinimumRate_isAnswered408_andTheSinkIsAbortedTimedOut() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> pace(stream, "/timed", 1, 100, 60));

        assertThat(answer.statusText()).isEqualTo("408");
        assertThat(answer.body()).contains("minimum rate");
        assertThat(sinks.getFirst().awaitTerminal(5)).isEqualTo("abort:TIMED_OUT");
        assertThat(sinks.getFirst().terminals()).containsExactly("abort:TIMED_OUT");
    }

    /// The client's connection goes away while an accept is outstanding: the sink is aborted DISCONNECTED once, and only
    /// after that accept resolved.
    @Test
    void clientDisconnectMidBody_abortsTheSinkDisconnectedOnce_afterTheOutstandingAccept() throws Exception {
        var held = new AtomicReference<CompletableFuture<Void>>();

        nextSink = () -> {
            var sink = new RecordingSink();

            held.set(sink.hold());

            return sink;
        };
        start();

        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(headers("/stream", 10L * MIB));
            awaitWrite(stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[256 * KIB]))));
            awaitAccept();
            stream.parent().close().sync();
        });
        var sink = sinks.getFirst();

        assertThat(answer.status()).as("control: no response, the connection went first").isEqualTo("closed");
        settle();
        assertThat(sink.terminals()).as("no terminal call while the accept is outstanding").isEmpty();
        held.get().complete(null);
        assertThat(sink.awaitTerminal(5)).isEqualTo("abort:DISCONNECTED");
        settle();
        assertThat(sink.terminals()).containsExactly("abort:DISCONNECTED");
        assertThat(sink.violations()).isEmpty();
    }

    /// A failed accept: the sink's own response reaches the client, and the server stops reading the stream, so the
    /// client cannot send the rest.
    @Test
    void acceptFailure_sendsTheSinksResponse_thenStopsReadingTheStream() throws Exception {
        nextSink = () -> new RecordingSink().failAfter(64 * KIB);
        start();
        var sent = new AtomicLong();

        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(headers("/stream", 50L * MIB));
            sent.set(sendUntilStalled(stream, 50 * MIB));
        });

        assertThat(answer.statusText()).isEqualTo("503");
        assertThat(answer.body()).contains("sink refused the body");
        assertThat(sent.get()).as("the server stopped reading: the client stalled or was stopped far short of 50 MiB")
                              .isLessThan(20L * MIB);
        assertThat(sinks.getFirst().terminals()).containsExactly("fail:the sink refused a chunk");
    }

    @Test
    void trailers_reachComplete() throws Exception {
        start();

        var answer = Http3TestClient.exchange(port, stream -> {
            stream.write(headers("/stream", -1));
            stream.write(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[3000])));
            var trailers = new DefaultHttp3HeadersFrame();

            trailers.headers().add("x-checksum", "abc123");
            stream.writeAndFlush(trailers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        });
        var request = sinks.getFirst().awaitComplete(5);

        assertThat(answer.statusText()).isEqualTo("200");
        assertThat(answer.body()).startsWith("received 3000 ");
        assertThat(request.trailers().get("x-checksum").or("")).isEqualTo("abc123");
        assertThat(request.body()).isEmpty();
        assertThat(request.path()).isEqualTo("/stream");
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

    /// Sends `size` generated bytes (see [RequestGateStreamWireTest#piece]) in 64 KiB frames, waiting for each write, and
    /// ends the stream; returns their SHA-256.
    private String sendGenerated(QuicStreamChannel stream, String path, long size) {
        var digest = RecordingSink.sha256();

        stream.write(headers(path, size));
        for (long index = 0, done = 0; done < size; index++) {
            var piece = RequestGateStreamWireTest.piece(index, (int) Math.min(MIB, size - done));

            digest.update(piece);
            for (int at = 0; at < piece.length; at += 64 * KIB) {
                var frame = Unpooled.wrappedBuffer(piece, at, Math.min(64 * KIB, piece.length - at));

                assertThat(awaitWrite(stream.writeAndFlush(new DefaultHttp3DataFrame(frame)))).as("frame write at %d", done + at)
                                                                                            .isTrue();
            }

            done += piece.length;
        }

        stream.shutdownOutput();

        return RecordingSink.hex(digest.digest());
    }

    private void body(QuicStreamChannel stream, String path, int size, long declared) {
        stream.write(headers(path, declared));
        var remaining = size;

        while (remaining > 0 && stream.isActive()) {
            var frame = Math.min(64 * KIB, remaining);

            if (!awaitWrite(stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[frame]))))) {
                return;
            }

            remaining -= frame;
        }

        if (stream.isActive()) {
            stream.shutdownOutput();
        }
    }

    private static long sendUntilStalled(QuicStreamChannel stream, int limit) {
        var sent = 0L;

        while (sent < limit && stream.isActive()) {
            if (!awaitWrite(stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[64 * KIB]))))) {
                return sent;
            }

            sent += 64 * KIB;
        }

        return sent;
    }

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

    private static boolean awaitWrite(ChannelFuture written) {
        try {
            return written.await(10, TimeUnit.SECONDS) && written.isSuccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private void awaitAccept() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while ((sinks.isEmpty() || sinks.getFirst().accepts() == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(sinks.getFirst().accepts()).as("control: an accept is outstanding").isPositive();
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    private static long usedDirectMemory() {
        return ((ByteBufAllocatorMetricProvider) ByteBufAllocator.DEFAULT).metric().usedDirectMemory();
    }
}
