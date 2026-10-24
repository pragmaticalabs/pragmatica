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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The ingest gate on HTTP/1.1 over a real socket, through the real pipeline: a request is classified on its head, before
/// any body byte is read. The classifier answers by path, as a route table would:
/// - `/denied`: refused (401), the way an unauthenticated request to a protected route is;
/// - `/small`: 1 MiB limit; `/big`: 100 MiB limit;
/// - `/timed`: 1 MiB limit with upload bounds (idle 300 ms, 1000 B/s minimum after 300 ms, no duration bound);
/// - `/idle`: the same idle bound with the rate bound off, so only the idle bound can answer;
/// - `/duration`: upload bounds with a 700 ms duration and no idle or rate bound that a steady sender would trip.
class RequestGateWireTest {
    private static final int MIB = 1024 * 1024;
    private static final long TIMED_BOUND_MS = 300;

    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicLong bodyBytes = new AtomicLong();
    private NettyHttpServer server;
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

    private final BiConsumer<HttpRequest, ResponseWriter> handler = (request, response) -> {
        invocations.incrementAndGet();
        bodyBytes.set(request.body().length);
        response.okText("handled " + request.body().length);
    };

    private void start() {
        var config = HttpServerConfig.httpServerConfig("ingest-gate", freeTcpPort()).withClassifier(classifier);

        port = config.port();
        server = (NettyHttpServer) NettyHttpServer.create(config, handler).await(timeSpan(10).seconds()).unwrap();
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    /// Auth before body: the request is refused on its head. The client waits for `100 Continue` and never sends the
    /// body; the server answers 401 at once, never says `100`, and closes.
    @Test
    void deniedRequestWithExpectContinue_isAnswered401WithoutContinue_andNeverDispatched() throws IOException {
        start();

        try (var socket = open()) {
            send(socket, "POST /denied HTTP/1.1\r\nHost: x\r\nContent-Length: 52428800\r\nExpect: 100-continue\r\n\r\n");
            var answer = readUntilClosed(socket);

            assertThat(answer).startsWith("HTTP/1.1 401");
            assertThat(answer).doesNotContain("100 Continue");
            assertThat(answer).containsIgnoringCase("connection: close");
        }

        assertThat(invocations.get()).isZero();
    }

    /// The same refusal for a client that does not wait: it streams 50 MiB, the server never reads it, so the client's
    /// sending stalls in the kernel's buffers and fails when the server closes. A server that drained the body would let
    /// all 50 MiB through.
    @Test
    void deniedRequest_isNotDrained_theClientCannotFinishSendingItsBody() throws IOException {
        start();
        var fiftyMiB = 50L * MIB;
        var sent = 0L;
        var failed = false;
        var answer = new StringBuilder();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /denied HTTP/1.1\r\nHost: x\r\nContent-Length: " + fiftyMiB + "\r\n\r\n");
            var reader = new Thread(() -> answer.append(readQuietly(socket)));

            reader.start();
            var chunk = new byte[64 * 1024];

            try {
                while (sent < fiftyMiB) {
                    socket.getOutputStream().write(chunk);
                    sent += chunk.length;
                }
            } catch (IOException e) {
                failed = true;
            }

            join(reader);
        }

        assertThat(failed).as("the client's body write failed once the server closed, after only " + sent + " bytes").isTrue();
        assertThat(sent).as("far less than the declared 50 MiB reached the kernel's buffers").isLessThan(fiftyMiB);
        assertThat(answer.toString()).startsWith("HTTP/1.1 401");
        assertThat(invocations.get()).isZero();
    }

    /// Per-route limits: the same 2 MiB body passes on the 100 MiB route and is refused 413 on the 1 MiB route, with a
    /// declared length and chunked alike.
    @Test
    void sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute_withContentLengthAndChunked() throws IOException {
        start();
        var twoMiB = 2 * MIB;

        var big = exchange("/big", twoMiB, false);
        var bigChunked = exchange("/big", twoMiB, true);

        assertThat(big.status()).isEqualTo("200");
        assertThat(big.text()).endsWith("handled " + twoMiB);
        assertThat(bigChunked.status()).isEqualTo("200");
        assertThat(bigChunked.text()).endsWith("handled " + twoMiB);
        assertThat(invocations.get()).as("control: both reached the handler").isEqualTo(2);

        var small = exchange("/small", twoMiB, false);
        var smallChunked = exchange("/small", twoMiB, true);

        assertThat(small.status()).isEqualTo("413");
        assertThat(smallChunked.status()).isEqualTo("413");
        assertThat(invocations.get()).as("neither refused request reached the handler").isEqualTo(2);
    }

    /// A body exactly at the limit passes and one byte over is refused, chunked: the counter refuses at byte limit + 1.
    @Test
    void chunkedBody_atTheLimitPasses_oneByteOverIsRefused() throws IOException {
        start();

        assertThat(exchange("/small", MIB, true).status()).isEqualTo("200");
        assertThat(exchange("/small", MIB + 1, true).status()).isEqualTo("413");
    }

    /// Over the limit by declared length: 413, and the connection is closed within a second, while the client is still
    /// willing to send. The close is the server's: the client sees its writes fail.
    @Test
    void overLimitContentLength_isRefused413_andTheConnectionIsClosedWithinASecond() throws IOException, InterruptedException {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /small HTTP/1.1\r\nHost: x\r\nContent-Length: " + 2 * MIB + "\r\n\r\n");
            var answer = readUntilClosed(socket);
            var answeredAt = System.nanoTime();

            assertThat(answer).startsWith("HTTP/1.1 413");

            var closedAfterMs = writeUntilFailure(socket, answeredAt);

            assertThat(closedAfterMs).as("the server closed within the bound (typically ~250 ms)").isBetween(0L, 1_000L);
        }

        assertThat(invocations.get()).isZero();
    }

    /// Upload bounds answer 408 and close: a body trickling at 10 B/s is far below the 1000 B/s minimum.
    @Test
    void uploadBodyBelowTheMinimumRate_isAnswered408_andNeverDispatched() throws IOException, InterruptedException {
        start();

        var answer = trickle("/timed", 100);

        assertThat(answer).startsWith("HTTP/1.1 408");
        assertThat(answer).contains("minimum rate");
        assertThat(invocations.get()).isZero();
        assertThat(releases.get()).as("the permit of the refused upload was released").isEqualTo(1);
    }

    @Test
    void uploadBodyIdleForTheUploadIdleBound_isAnswered408() throws IOException {
        start();

        try (var socket = open()) {
            socket.setSoTimeout(5_000);
            send(socket, "POST /idle HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n0123456789");
            var answer = readUntilClosed(socket);

            assertThat(answer).startsWith("HTTP/1.1 408");
            assertThat(answer).contains("idle");
        }

        assertThat(invocations.get()).isZero();
    }

    /// A sender above the minimum rate for longer than the duration bound is still cut at the bound.
    @Test
    void uploadBodyOverTheRouteDuration_isAnswered408_evenAtASteadyRate() throws IOException, InterruptedException {
        start();

        var answer = trickle("/duration", 5);

        assertThat(answer).startsWith("HTTP/1.1 408");
        assertThat(answer).contains("max_duration");
        assertThat(invocations.get()).isZero();
    }

    /// Control for the three 408s: a body that arrives promptly is dispatched, and its permit is released.
    @Test
    void uploadBodyArrivingPromptly_isDispatched_andItsPermitReleased() throws IOException {
        start();

        var answer = exchange("/timed", 4096, false);

        assertThat(answer.status()).isEqualTo("200");
        assertThat(bodyBytes.get()).isEqualTo(4096);
        assertThat(releases.get()).isEqualTo(1);
    }

    // ---- client helpers

    private record Answer(String status, String text) {}

    /// Sends head and body (from a second thread, which stops at the first write failure) and reads the answer.
    private Answer exchange(String path, int bodySize, boolean chunked) throws IOException {
        try (var socket = open()) {
            socket.setSoTimeout(10_000);
            var head = "POST " + path + " HTTP/1.1\r\nHost: x\r\n"
                       + (chunked
                          ? "Transfer-Encoding: chunked\r\n"
                          : "Content-Length: " + bodySize + "\r\n") + "Connection: close\r\n\r\n";
            var writer = new Thread(() -> writeBody(socket, head, bodySize, chunked));

            writer.start();
            var text = readQuietly(socket);

            join(writer);

            return new Answer(statusCode(text), text);
        }
    }

    /// The status code of the first response line (`HTTP/1.1 200 OK` is `200`), or empty when nothing was answered.
    private static String statusCode(String text) {
        var line = text.lines().findFirst().orElse("");
        var parts = line.split(" ");

        return parts.length > 1
               ? parts[1]
               : "";
    }

    private static void writeBody(Socket socket, String head, int bodySize, boolean chunked) {
        try {
            var out = socket.getOutputStream();
            var chunk = new byte[16 * 1024];

            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            for (int sent = 0; sent < bodySize; sent += chunk.length) {
                var size = Math.min(chunk.length, bodySize - sent);

                if (chunked) {
                    out.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }

                out.write(chunk, 0, size);
                if (chunked) {
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
            }

            if (chunked) {
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }

            out.flush();
        } catch (IOException e) {
            // the server refused the body and closed: expected for the refused requests
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

    /// Keeps writing until a write fails; returns the milliseconds from `since` to the failure, or 10 s when none did.
    private static long writeUntilFailure(Socket socket, long since) throws InterruptedException {
        var chunk = new byte[1024];

        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since) < 10_000) {
            try {
                socket.getOutputStream().write(chunk);
                socket.getOutputStream().flush();
                Thread.sleep(25);
            } catch (IOException e) {
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
            }
        }

        return 10_000;
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
        InputStream in = socket.getInputStream();
        int read;

        try {
            while ((read = in.read(buffer)) != -1) {
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

    private static void join(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
