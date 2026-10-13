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

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2184: WebSocket endpoints driven through a real socket by the JDK client against a real [NettyHttpServer]
/// with three endpoints. Before #2184 the first endpoint handshook but its handler saw nothing (Netty's
/// handshake handler consumed the upgrade before the per-channel state was stored), and the second and later
/// endpoints never completed the handshake (Netty binds its single handshake handler to the first endpoint's
/// path). The plain-HTTP control shows the same server answering ordinary requests, so a red WebSocket
/// assertion is not a dead server. The port is assigned by the OS at bind (port 0), so nothing collides when
/// modules run in parallel (#939).
@Timeout(60)
class WebSocketEndpointTest {
    private static final List<String> PATHS = List.of("/ws/a", "/ws/b", "/ws/c");
    private static final long WAIT_SECONDS = 5;
    private static final String UPGRADE_TO_UNKNOWN = "GET /ws/unknown HTTP/1.1\r\n" + "Host: 127.0.0.1\r\n"
                                                     + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n"
                                                     + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                                                     + "Sec-WebSocket-Version: 13\r\n\r\n";

    private final Map<String, BlockingQueue<String>> seen = new ConcurrentHashMap<>();
    private HttpServer server;
    private HttpClient client;

    @BeforeEach
    void start() {
        var config = HttpServerConfig.httpServerConfig("ws-test", 0);

        for (var path : PATHS) {
            seen.put(path, new LinkedBlockingQueue<>());
            config = config.withWebSocket(webSocketEndpoint(path, () -> recording(path)));
        }

        server = HttpServer.httpServer(config, (_, writer) -> writer.okText("http"))
                           .await(timeSpan(10).seconds())
                           .unwrap();
        client = HttpClient.newBuilder()
                           .connectTimeout(Duration.ofSeconds(WAIT_SECONDS))
                           .build();
    }

    @AfterEach
    void stop() {
        client.shutdownNow();
        server.stop()
              .await(timeSpan(10).seconds());
    }

    @Test
    void firstEndpoint_handshakes_deliversOpen_echoesText_andDeliversClose() throws Exception {
        assertFullLifecycle("/ws/a");
    }

    @Test
    void secondEndpoint_handshakes_deliversOpen_echoesText_andDeliversClose() throws Exception {
        assertFullLifecycle("/ws/b");
    }

    @Test
    void thirdEndpoint_handshakes_deliversOpen_echoesText_andDeliversClose() throws Exception {
        assertFullLifecycle("/ws/c");
    }

    /// The endpoint is selected by path; a query string (`?token=...`, as dashboards send) must not defeat the
    /// handshake's own path check.
    @Test
    void endpointWithQueryString_handshakes_andReachesTheEndpointHandler() throws Exception {
        var listener = new ClientListener();
        var ws = connect("/ws/b?token=abc", listener);

        assertThat(next("/ws/b")).isEqualTo("open");
        ws.sendText("q", true);
        assertThat(listener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/ws/b:q");
    }

    /// A server-side `session.close()` ends the session: the client sees the close, the handler sees `Close`.
    @Test
    void serverSideClose_closesTheClient_andDeliversClose() throws Exception {
        var listener = new ClientListener();
        var ws = connect("/ws/c", listener);

        assertThat(next("/ws/c")).isEqualTo("open");
        ws.sendText("bye", true);
        assertThat(next("/ws/c")).isEqualTo("text:bye");
        assertThat(listener.closed.get(WAIT_SECONDS, TimeUnit.SECONDS)).as("client saw the server close").isNotNull();
        assertThat(next("/ws/c")).isEqualTo("close");
    }

    /// A connection dropped without a close frame still delivers `Close`, so handlers that track sessions
    /// do not keep a dead one.
    @Test
    void droppedConnection_deliversClose() throws Exception {
        var ws = connect("/ws/a", new ClientListener());

        assertThat(next("/ws/a")).isEqualTo("open");
        ws.abort();
        assertThat(next("/ws/a")).isEqualTo("close");
    }

    /// An upgrade to a path with no endpoint is refused with 404; no endpoint handler is created or called.
    @Test
    void unknownPath_isRefusedWith404() throws Exception {
        var outcome = client.newWebSocketBuilder()
                            .buildAsync(uri("ws", "/ws/unknown"), new ClientListener());

        assertThat(outcome).failsWithin(WAIT_SECONDS, TimeUnit.SECONDS)
                           .withThrowableOfType(ExecutionException.class)
                           .havingCause()
                           .isInstanceOfSatisfying(WebSocketHandshakeException.class,
                                                   e -> assertThat(e.getResponse().statusCode()).isEqualTo(404));
        PATHS.forEach(path -> assertThat(seen.get(path)).as("handler of %s", path).isEmpty());
    }

    /// The refusal on the wire: a 404 with an empty body, announced and carried out as a closed connection.
    /// `readAllBytes` returns only at end of stream, so a connection left open fails on the read timeout.
    @Test
    void unknownPath_rawUpgrade_gets404_andTheConnectionIsClosed() throws Exception {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            socket.getOutputStream()
                  .write(UPGRADE_TO_UNKNOWN.getBytes(US_ASCII));

            var response = new String(socket.getInputStream().readAllBytes(), US_ASCII);

            assertThat(response).startsWith("HTTP/1.1 404")
                                .containsIgnoringCase("connection: close")
                                .containsIgnoringCase("content-length: 0");
        }
    }

    /// Control: the same server answers plain HTTP, including a plain GET on an endpoint path.
    @Test
    void plainHttp_onTheSameServer_reachesTheHttpHandler() throws Exception {
        for (var path : List.of("/plain", "/ws/b")) {
            var request = HttpRequest.newBuilder(uri("http", path))
                                     .timeout(Duration.ofSeconds(WAIT_SECONDS))
                                     .build();
            var response = client.send(request,
                                       HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("http");
        }
    }

    private void assertFullLifecycle(String path) throws Exception {
        var listener = new ClientListener();
        var ws = connect(path, listener);

        assertThat(next(path)).as("Open delivered on %s", path).isEqualTo("open");

        ws.sendText("hello", true);
        assertThat(listener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).as("echo on %s", path).isEqualTo(path + ":hello");
        assertThat(next(path)).isEqualTo("text:hello");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        assertThat(next(path)).as("Close delivered on %s", path).isEqualTo("close");
        assertThat(listener.closed.get(WAIT_SECONDS, TimeUnit.SECONDS)).as("close acknowledged on %s", path)
                                                                       .isEqualTo(WebSocket.NORMAL_CLOSURE);
        PATHS.stream()
             .filter(other -> !other.equals(path))
             .forEach(other -> assertThat(seen.get(other)).as("handler of %s", other).isEmpty());
    }

    private WebSocket connect(String path, ClientListener listener) throws Exception {
        return client.newWebSocketBuilder()
                     .buildAsync(uri("ws", path), listener)
                     .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private String next(String path) throws InterruptedException {
        return seen.get(path)
                   .poll(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private URI uri(String scheme, String path) {
        return URI.create(scheme + "://127.0.0.1:" + server.port() + path);
    }

    /// Records every message by kind, echoes text prefixed with the endpoint path, closes on "bye".
    private WebSocketHandler recording(String path) {
        return (session, message) -> record(path, seen.get(path), session, message);
    }

    private static void record(String path, BlockingQueue<String> log, WebSocketSession session, WebSocketMessage message) {
        switch (message) {
            case WebSocketMessage.Open _ -> log.add("open");
            case WebSocketMessage.Text text -> onText(path, log, session, text.content());
            case WebSocketMessage.Binary _ -> log.add("binary");
            case WebSocketMessage.Close _ -> log.add("close");
        }
    }

    private static void onText(String path, BlockingQueue<String> log, WebSocketSession session, String text) {
        log.add("text:" + text);
        if ("bye".equals(text)) {
            session.close();
        } else {
            session.send(path + ":" + text);
        }
    }

    private static final class ClientListener implements WebSocket.Listener {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final List<CharSequence> parts = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            parts.add(data);
            if (last) {
                texts.add(String.join("", parts));
                parts.clear();
            }
            webSocket.request(1);

            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);

            return null;
        }
    }
}
