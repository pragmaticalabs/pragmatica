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

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2407: a response that ends the HTTP/1.1 connection says `Connection: close`, so the client does not have to infer
/// it from the end of stream. Covers the two ending paths the dispatch fix (#2482) left without the header: the 400 for
/// a duplicate Host and the 200 answering a request that asked for `Connection: close`. The keep-alive control proves the
/// header is not simply sent on every response.
class Http1ConnectionCloseHeaderTest {
    private static final String DUPLICATE_HOST = "GET /first HTTP/1.1\r\nHost: localhost\r\nHost: evil.example\r\n\r\n";
    private static final String CLOSE_REQUEST = "GET /first HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    private static final String KEEP_ALIVE_REQUEST = "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String HTTP10_REQUEST = "GET /first HTTP/1.0\r\nHost: localhost\r\n\r\n";

    @Test
    void duplicateHost400_carriesConnectionClose() throws IOException {
        var head = exchange(DUPLICATE_HOST, false);

        assertThat(head).startsWith("HTTP/1.1 400");
        assertThat(head.toLowerCase()).contains("connection: close");
    }

    @Test
    void ok200_carriesConnectionClose_whenRequestAskedForClose() throws IOException {
        var head = exchange(CLOSE_REQUEST, false);

        assertThat(head).startsWith("HTTP/1.1 200");
        assertThat(head.toLowerCase()).contains("connection: close");
    }

    @Test
    void ok200_doesNotSayClose_whenConnectionStaysOpen() throws IOException {
        var head = exchange(KEEP_ALIVE_REQUEST, true);

        assertThat(head).startsWith("HTTP/1.1 200");
        assertThat(head.toLowerCase()).doesNotContain("connection: close");
    }

    @Test
    void ok200_carriesConnectionHeaderOnce_whenHandlerSetsKeepAliveOnClosingResponse() throws IOException {
        var head = exchange(CLOSE_REQUEST, false, (request, writer) -> writer.header("Connection", "keep-alive").ok("{}"));

        assertThat(head).startsWith("HTTP/1.1 200");
        assertThat(head.toLowerCase().split("connection:", -1)).hasSize(2);
        assertThat(head.toLowerCase()).contains("connection: close").doesNotContain("keep-alive");
    }

    @Test
    void ok200_carriesConnectionClose_whenRequestIsHttp10WithoutKeepAlive() throws IOException {
        var head = exchange(HTTP10_REQUEST, false);

        assertThat(head).startsWith("HTTP/1.1 200");
        assertThat(head.toLowerCase()).contains("connection: close");
    }

    /// Returns the response head; a keep-alive exchange is read up to the blank line, as the server leaves the socket open.
    private static String exchange(String raw, boolean keepOpen) throws IOException {
        return exchange(raw, keepOpen, (request, writer) -> writer.ok("{}"));
    }

    private static String exchange(String raw,
                                   boolean keepOpen,
                                   BiConsumer<HttpRequest, ResponseWriter> handler) throws IOException {
        var config = HttpServerConfig.httpServerConfig("close-header", 0);
        var server = HttpServer.httpServer(config, handler).await(timeSpan(10).seconds()).unwrap();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            return keepOpen
                   ? readHead(socket)
                   : new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    private static String readHead(Socket socket) throws IOException {
        var head = new StringBuilder();
        var in = socket.getInputStream();

        while (!head.toString().endsWith("\r\n\r\n")) {
            var next = in.read();

            if (next < 0) {
                break;
            }
            head.append((char) next);
        }
        return head.toString();
    }
}
