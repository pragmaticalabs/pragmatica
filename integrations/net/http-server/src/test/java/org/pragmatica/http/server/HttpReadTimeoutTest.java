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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// I3 on HTTP/1.1 over a real socket: a connection whose body stalls is closed after the read timeout and its
/// request never reaches the handler; a connection whose response is pending is not idle, however long the
/// handler takes.
class HttpReadTimeoutTest {
    private static final long READ_TIMEOUT_MS = 300;

    private final AtomicInteger invocations = new AtomicInteger();
    private NettyHttpServer server;
    private int port;

    private void start(BiConsumer<HttpRequest, ResponseWriter> handler) {
        port = freeTcpPort();
        var config = HttpServerConfig.httpServerConfig("read-timeout", port)
                                     .withReadTimeout(timeSpan(READ_TIMEOUT_MS).millis());

        server = (NettyHttpServer) NettyHttpServer.create(config, handler)
                                                  .await(timeSpan(10).seconds())
                                                  .unwrap();
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    @Test
    void stalledBody_connectionClosedAfterReadTimeout_handlerNeverInvoked() throws IOException {
        start((request, response) -> {
            invocations.incrementAndGet();
            response.okText("handled");
        });

        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            var startedAt = System.nanoTime();

            socket.getOutputStream()
                  .write("POST /up HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n0123456789".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            var received = readUntilClosed(socket.getInputStream());
            var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(received).as("closed without a response").isEmpty();
            assertThat(elapsedMs).as("closed by the read timeout, not left open").isLessThan(4_000);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("the stalled connection was still open after 5 s", e);
        }

        assertThat(invocations.get()).isZero();
    }

    @Test
    void slowHandler_connectionNotClosedWhileResponsePending() throws IOException {
        start((request, response) -> CompletableFuture.delayedExecutor(3 * READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                                      .execute(() -> response.okText("late")));

        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream()
                  .write("GET /slow HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            assertThat(readUntilClosed(socket.getInputStream())).startsWith("HTTP/1.1 200");
        }
    }

    private static String readUntilClosed(InputStream in) throws IOException {
        var out = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
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
