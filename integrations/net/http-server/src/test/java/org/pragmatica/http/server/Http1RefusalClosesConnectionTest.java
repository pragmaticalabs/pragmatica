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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2433: after an exchange that ends the connection (a refusal the server answers and closes on, or a request with
/// `Connection: close`), nothing pipelined behind it on that connection reaches the handler. One table over every way
/// the HTTP/1.1 server ends an exchange: a new refusal path has to be added to [#endingExchanges] and so has to prove it.
/// Each case sends the ending request and a valid request behind it in one write.
class Http1RefusalClosesConnectionTest {
    private static final int PIPELINED = 30;
    private static final String SECOND = "GET /second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    /// name, the request that ends the exchange, and the paths the handler may have seen (the ending request itself is
    /// dispatched when it is a valid request that merely asked for `Connection: close`).
    static List<Case> endingExchanges() {
        return List.of(new Case("two Host lines",
                                      "GET /first HTTP/1.1\r\nHost: localhost\r\nHost: evil.example\r\n\r\n",
                                      List.of()),
                         new Case("WebSocket upgrade to a path with no route",
                                      "GET /nowhere HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                      + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
                                      List.of()),
                         new Case("Connection: close",
                                      "GET /first HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                                      List.of("/first")),
                         new Case("HTTP/1.0 without keep-alive",
                                      "GET /first HTTP/1.0\r\nHost: localhost\r\n\r\n",
                                      List.of("/first")),
                         new Case("undecodable percent-escape in the target",
                                      "GET /x%zz HTTP/1.1\r\nHost: localhost\r\n\r\n",
                                      List.of()),
                         new Case("undecodable request line",
                                      "GARBAGE\r\n\r\n",
                                      List.of()),
                         new Case("declared body over the limit",
                                      "POST /first HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2000000000\r\n\r\n",
                                      List.of()));
    }

    @TestFactory
    Stream<DynamicTest> requestPipelinedBehindAnEndedExchange_neverReachesTheHandler() {
        return endingExchanges().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> assertNotDispatched(c)));
    }

    /// A fresh server per case: the tail of a closed connection may still be draining on the server when the client has
    /// read its end of stream, and its dispatches must not be counted against the next case.
    private void assertNotDispatched(Case c) throws IOException {
        var recorder = new Recorder();
        var config = HttpServerConfig.httpServerConfig("refusal-closes", 0)
                                     .withWebSocket(webSocketEndpoint("/ws", () -> (_, _) -> {}));
        var server = HttpServer.httpServer(config, recorder).await(timeSpan(10).seconds()).unwrap();

        var response = send(server.port(), c.ending() + SECOND.repeat(PIPELINED));

        // stop first: it waits for the event loop, so a request still being decoded from the closed connection's buffer has
        // been dispatched (or not) by the time the recorder is read
        server.stop().await(timeSpan(10).seconds());

        assertThat(recorder.paths()).as("%s: the handler saw only %s", c.name(), c.allowed()).isEqualTo(c.allowed());
        assertThat(response).as("%s: no response to a pipelined request", c.name()).doesNotContain("\"second\"");
    }

    record Case(String name, String ending, List<String> allowed) {}

    private static String send(int port, String raw) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    /// Records the path of every dispatched request and answers 200 with the path in the body.
    private static final class Recorder implements BiConsumer<HttpRequest, ResponseWriter> {
        private final List<String> paths = new CopyOnWriteArrayList<>();

        @Override
        public void accept(HttpRequest request, ResponseWriter writer) {
            paths.add(request.path());
            writer.ok("{\"path\":\"" + request.path().substring(1) + "\"}");
        }

        List<String> paths() {
            return List.copyOf(paths);
        }
    }
}
