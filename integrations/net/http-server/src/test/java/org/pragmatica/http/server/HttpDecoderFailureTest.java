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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// I2: a request Netty's HTTP/1 decoder could not decode is answered (431 oversized headers, 414 oversized
/// request line, 400 otherwise), the connection is closed, and the handler is never invoked. Before the fix the
/// placeholder request reached the handler and got 200. Over a real socket against the real pipeline.
class HttpDecoderFailureTest {
    private final AtomicInteger invocations = new AtomicInteger();
    private NettyHttpServer server;
    private int port;

    @BeforeEach
    void start() {
        port = freeTcpPort();
        server = (NettyHttpServer) NettyHttpServer.create(HttpServerConfig.httpServerConfig("decoder-failure", port),
                                                          (request, response) -> {
                                                              invocations.incrementAndGet();
                                                              response.okText("handled");
                                                          })
                                                  .await(timeSpan(10).seconds())
                                                  .unwrap();
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    @Test
    void oversizedHeader_gets431_connectionClosed_handlerNeverInvoked() throws IOException {
        var request = "POST /up HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(10 * 1024)
                      + "\r\nContent-Length: 0\r\n\r\n";

        var response = exchange(request);

        assertThat(response).startsWith("HTTP/1.1 431");
        assertThat(invocations.get()).as("an undecodable request must never reach the handler").isZero();
    }

    @Test
    void oversizedRequestLine_gets414_connectionClosed_handlerNeverInvoked() throws IOException {
        var request = "GET /" + "a".repeat(5 * 1024) + " HTTP/1.1\r\nHost: x\r\n\r\n";

        var response = exchange(request);

        assertThat(response).startsWith("HTTP/1.1 414");
        assertThat(invocations.get()).isZero();
    }

    @Test
    void wellFormedRequest_stillReachesTheHandler() throws IOException {
        var response = exchange("GET /ok HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(invocations.get()).isEqualTo(1);
    }

    /// Writes the request and reads until the server closes the connection; a read that times out instead fails the
    /// test, which is how "the connection is closed" is asserted.
    private String exchange(String request) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            return readUntilClosed(socket.getInputStream());
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
