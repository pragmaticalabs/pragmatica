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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Streamed request bodies ([Disposition.Stream]) on HTTP/1.1 over a real socket, through the real pipeline. The
/// classifier answers by path:
/// - `/stream`: 1 GiB limit, the listener's bounds;
/// - `/small`: 1 MiB limit;
/// - `/idle`: 1 MiB, idle bound 300 ms, no rate bound;
/// - `/timed`: 1 MiB, idle bound 300 ms and a 1000 B/s minimum after 300 ms.
///
/// Each request gets a fresh [RecordingSink] from [#nextSink]; [Http3StreamWireTest] holds the HTTP/3 twin of each test.
class RequestGateStreamWireTest {
    private static final int KIB = 1024;
    private static final int MIB = 1024 * KIB;
    /// Netty's `HttpServerCodec` hands the body on in pieces of at most its `maxChunkSize`, 8 KiB by default: no accept
    /// is ever given more.
    private static final long CHUNK_BOUND = 8 * KIB;
    /// The direct memory the server may gain while a slow sink takes 64 MiB: reading stops while a chunk is pending, so
    /// it holds about one socket read (at most 64 KiB) plus the allocator's chunk granularity. A server that kept
    /// reading would hold most of the 64 MiB.
    private static final long HELD_BOUND = 16L * MIB;

    private final List<RecordingSink> sinks = new CopyOnWriteArrayList<>();
    private volatile Supplier<RecordingSink> nextSink = RecordingSink::new;
    private NettyHttpServer server;
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
        var config = HttpServerConfig.httpServerConfig("stream-gate", freeTcpPort()).withClassifier(classifier);

        port = config.port();
        server = (NettyHttpServer) NettyHttpServer.create(config,
                                                          (_, response) -> response.okText("aggregated"))
                                                  .await(timeSpan(10).seconds())
                                                  .unwrap();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// 64 MiB of non-repeating bytes into a sink that takes ~1 ms per 16 KiB (16 MB/s, far below what loopback delivers):
    /// never more than one accept outstanding, no accept given more than [#CHUNK_BOUND], the server's direct memory grows by
    /// at most [#HELD_BOUND], and the sink's SHA-256 is the sender's.
    @Test
    void slowSink_receives64MiB_oneAcceptAtATime_withBoundedBuffering_andTheSendersHash() throws Exception {
        nextSink = () -> new RecordingSink().slow(61).sampling(RequestGateStreamWireTest::usedDirectMemory);
        start();
        var baseline = usedDirectMemory();
        var size = 64L * MIB;

        try (var socket = open()) {
            socket.setSoTimeout(60_000);
            var sent = sendGenerated(socket, "POST /stream HTTP/1.1\r\nHost: x\r\nContent-Length: " + size + "\r\n\r\n", size);
            var answer = readResponse(socket.getInputStream());
            var sink = sinks.getFirst();

            assertThat(answer).startsWith("HTTP/1.1 200");
            assertThat(sink.terminals()).containsExactly("complete");
            assertThat(sink.bytes()).isEqualTo(size);
            assertThat(sink.sha()).isEqualTo(sent);
            assertThat(answer).endsWith("received " + size + " sha " + sent);
            assertThat(sink.maxOutstanding()).as("at most one accept outstanding").isEqualTo(1);
            assertThat(sink.maxChunk()).as("largest chunk one accept was given").isLessThanOrEqualTo(CHUNK_BOUND);
            assertThat(sink.maxSample() - baseline).as("direct memory the server gained (baseline %d, %d accepts)", baseline, sink.accepts())
                                                   .isLessThanOrEqualTo(HELD_BOUND);
            assertThat(sink.violations()).isEmpty();
        }
    }

    @Test
    void declaredLengthOverTheLimit_isAnswered413_andTheSinkIsAbortedTooLarge() throws Exception {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /small HTTP/1.1\r\nHost: x\r\nContent-Length: " + 2 * MIB + "\r\n\r\n");

            assertThat(readUntilClosed(socket)).startsWith("HTTP/1.1 413");
        }

        assertThat(sinks.getFirst().awaitTerminal(5)).isEqualTo("abort:TOO_LARGE");
        assertThat(sinks.getFirst().terminals()).containsExactly("abort:TOO_LARGE");
    }

    @Test
    void chunkedBodyCrossingTheLimit_isAnswered413_andTheSinkIsAbortedTooLarge() throws Exception {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(10_000);
            var writer = Thread.ofVirtual().start(() -> writeChunked(socket, "/small", 2 * MIB, ""));
            var answer = readQuietly(socket);

            writer.join(10_000);
            assertThat(answer).startsWith("HTTP/1.1 413");
        }

        var sink = sinks.getFirst();

        assertThat(sink.awaitTerminal(5)).isEqualTo("abort:TOO_LARGE");
        assertThat(sink.bytes()).as("no more than the limit reached the sink").isLessThanOrEqualTo(MIB);
        settle();
        assertThat(sink.terminals()).containsExactly("abort:TOO_LARGE");
        assertThat(sink.violations()).isEmpty();
    }

    @Test
    void idleBody_isAnswered408_andTheSinkIsAbortedTimedOut() throws Exception {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /idle HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n0123456789");
            var answer = readUntilClosed(socket);

            assertThat(answer).startsWith("HTTP/1.1 408");
            assertThat(answer).contains("idle");
        }

        assertThat(sinks.getFirst().awaitTerminal(5)).isEqualTo("abort:TIMED_OUT");
        assertThat(sinks.getFirst().terminals()).containsExactly("abort:TIMED_OUT");
    }

    @Test
    void bodyBelowTheMinimumRate_isAnswered408_andTheSinkIsAbortedTimedOut() throws Exception {
        start();
        var answer = trickle("/timed", 100);

        assertThat(answer).startsWith("HTTP/1.1 408");
        assertThat(answer).contains("minimum rate");
        assertThat(sinks.getFirst().awaitTerminal(5)).isEqualTo("abort:TIMED_OUT");
        assertThat(sinks.getFirst().terminals()).containsExactly("abort:TIMED_OUT");
    }

    /// The client goes away while an accept is outstanding: the sink is aborted DISCONNECTED once, and only after that
    /// accept resolved.
    @Test
    void clientDisconnectMidBody_abortsTheSinkDisconnectedOnce_afterTheOutstandingAccept() throws Exception {
        var held = new AtomicReference<CompletableFuture<Void>>();

        nextSink = () -> {
            var sink = new RecordingSink();

            held.set(sink.hold());

            return sink;
        };
        start();

        try (var socket = open()) {
            send(socket, "POST /stream HTTP/1.1\r\nHost: x\r\nContent-Length: " + 10 * MIB + "\r\n\r\n");
            socket.getOutputStream().write(new byte[MIB]);
            socket.getOutputStream().flush();
            awaitAccept();
        }

        var sink = sinks.getFirst();

        settle();
        assertThat(sink.terminals()).as("no terminal call while the accept is outstanding").isEmpty();
        held.get().complete(null);
        assertThat(sink.awaitTerminal(5)).isEqualTo("abort:DISCONNECTED");
        settle();
        assertThat(sink.terminals()).containsExactly("abort:DISCONNECTED");
        assertThat(sink.violations()).isEmpty();
    }

    /// A failed accept: the sink's own response reaches the client, and then the server closes the connection.
    @Test
    void acceptFailure_sendsTheSinksResponse_thenClosesTheConnection() throws Exception {
        nextSink = () -> new RecordingSink().failAfter(64 * KIB);
        start();

        try (var socket = open()) {
            socket.setSoTimeout(10_000);
            var writer = Thread.ofVirtual().start(() -> writeSized(socket, "/stream", 8 * MIB));
            var answer = readQuietly(socket);

            writer.join(10_000);
            assertThat(answer).startsWith("HTTP/1.1 503");
            assertThat(answer).containsIgnoringCase("connection: close");
            assertThat(answer).contains("sink refused the body");
        }

        assertThat(sinks.getFirst().terminals()).containsExactly("fail:the sink refused a chunk");
    }

    /// Two streamed requests one after the other on one keep-alive connection both reach their sinks, and a third, sent
    /// together with the second (pipelined), waits until the second is answered and then completes too.
    @Test
    void streamedRequestsOnOneKeepAliveConnection_allComplete_includingAPipelinedOne() throws Exception {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(10_000);
            send(socket, "POST /stream HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nfirst");
            assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200").contains("received 5 ");
            send(socket,
                 "POST /stream HTTP/1.1\r\nHost: x\r\nContent-Length: 6\r\n\r\nsecond"
                 + "POST /stream HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nthird");
            assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200").contains("received 6 ");
            assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200").contains("received 5 ");
        }

        assertThat(sinks).hasSize(3);
        assertThat(sinks).allSatisfy(sink -> assertThat(sink.terminals()).containsExactly("complete"));
    }

    @Test
    void expectContinue_isAnswered100_thenTheBodyCompletes() throws Exception {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /stream HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n");
            var interim = readResponse(socket.getInputStream());

            assertThat(interim).startsWith("HTTP/1.1 100");
            send(socket, "body");
            assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200").contains("received 4 ");
        }

        var request = sinks.getFirst().awaitComplete(5);

        assertThat(request.headers().get("expect").isPresent()).as("the expectation was met here, as the aggregator does").isFalse();
    }

    @Test
    void trailers_reachComplete() throws Exception {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            writeChunked(socket, "/stream", 3000, "X-Checksum: abc123\r\n");

            assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200").contains("received 3000 ");
        }

        var request = sinks.getFirst().awaitComplete(5);

        assertThat(request.trailers().get("x-checksum").or("")).isEqualTo("abc123");
        assertThat(request.body()).isEmpty();
        assertThat(request.path()).isEqualTo("/stream");
    }

    // ---- client helpers

    /// Sends `head` and `size` bytes generated piece by piece (1 MiB pieces, each seeded by its index, so no two repeat);
    /// returns their SHA-256.
    static String sendGenerated(Socket socket, String head, long size) throws IOException {
        var digest = RecordingSink.sha256();
        var out = socket.getOutputStream();

        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        for (long index = 0, done = 0; done < size; index++) {
            var piece = piece(index, (int) Math.min(MIB, size - done));

            digest.update(piece);
            out.write(piece);
            done += piece.length;
        }

        out.flush();

        return RecordingSink.hex(digest.digest());
    }

    static byte[] piece(long index, int length) {
        var bytes = new byte[length];

        new SplittableRandom(0x5DEECE66DL + index).nextBytes(bytes);

        return bytes;
    }

    private void awaitAccept() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while ((sinks.isEmpty() || sinks.getFirst().accepts() == 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(sinks.getFirst().accepts()).as("control: an accept is outstanding").isPositive();
    }

    /// Lets a duplicate terminal call, if the server made one, arrive before the assertion.
    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    private static long usedDirectMemory() {
        return ((ByteBufAllocatorMetricProvider) ByteBufAllocator.DEFAULT).metric().usedDirectMemory();
    }

    private static void writeSized(Socket socket, String path, int size) {
        try {
            var out = socket.getOutputStream();

            out.write(("POST " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: " + size + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for (int sent = 0; sent < size; sent += 16 * KIB) {
                out.write(new byte[16 * KIB]);
            }

            out.flush();
        } catch (IOException e) {
            // the server closed: expected once it refused the body
        }
    }

    /// A chunked body of `size` bytes in 16 KiB chunks, then `trailers` (each line ending in CRLF) in the last chunk.
    private static void writeChunked(Socket socket, String path, int size, String trailers) {
        try {
            var out = socket.getOutputStream();

            out.write(("POST " + path + " HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            for (int sent = 0; sent < size; sent += 16 * KIB) {
                var chunk = Math.min(16 * KIB, size - sent);

                out.write((Integer.toHexString(chunk) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(new byte[chunk]);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }

            out.write(("0\r\n" + trailers + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException e) {
            // the server closed: expected once it refused the body
        }
    }

    /// One byte every `intervalMs` under a declared length of 1000, until the server answers or 10 s pass.
    private String trickle(String path, long intervalMs) throws IOException, InterruptedException {
        try (var socket = open()) {
            socket.setSoTimeout(50);
            send(socket, "POST " + path + " HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n");
            var startedAt = System.nanoTime();
            var received = new ByteArrayOutputStream();

            while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 10_000) {
                try {
                    socket.getOutputStream().write('x');
                    socket.getOutputStream().flush();
                    var buffer = new byte[4096];
                    var read = socket.getInputStream().read(buffer);

                    if (read == -1) {
                        break;
                    }

                    received.write(buffer, 0, read);
                } catch (SocketTimeoutException e) {
                    Thread.sleep(intervalMs);
                } catch (IOException e) {
                    break;
                }
            }

            return received.toString(StandardCharsets.ISO_8859_1) + readQuietly(socket);
        }
    }

    /// Reads one response: its head, then a body of its `Content-Length` (none for an interim response).
    static String readResponse(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();

        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            var next = in.read();

            if (next == -1) {
                break;
            }

            head.write(next);
        }

        var text = head.toString(StandardCharsets.ISO_8859_1);
        var length = text.lines()
                         .filter(line -> line.toLowerCase().startsWith("content-length:"))
                         .map(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).strip()))
                         .findFirst()
                         .orElse(0);

        return text + new String(in.readNBytes(length), StandardCharsets.ISO_8859_1);
    }

    private Socket open() throws IOException {
        return new Socket("127.0.0.1", port);
    }

    private static void send(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static String readUntilClosed(Socket socket) throws IOException {
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        int read;

        try {
            while ((read = socket.getInputStream().read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the connection was still open after the read timeout; read so far: " + out, e);
        }

        return out.toString(StandardCharsets.ISO_8859_1);
    }

    /// Reads to the end of the stream, or until the connection is reset; what was read is returned either way.
    private static String readQuietly(Socket socket) {
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];

        try {
            int read;

            while ((read = socket.getInputStream().read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            // reset after the answer: what arrived is what counts
        }

        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
