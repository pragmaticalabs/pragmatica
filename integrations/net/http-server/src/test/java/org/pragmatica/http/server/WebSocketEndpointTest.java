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
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final long QUIET_MILLIS = 300;
    /// The server's configured content length, the bound on an aggregated message.
    private static final int CONTENT_LENGTH = HttpServerConfig.httpServerConfig("ws-test", 0).maxContentLength();
    private static final String UPGRADE_TO_UNKNOWN = upgrade("/ws/unknown", "websocket", "13");

    private final Map<String, BlockingQueue<String>> seen = new ConcurrentHashMap<>();
    /// The session id of every message, per endpoint, in delivery order; added before the message is recorded.
    private final Map<String, BlockingQueue<String>> sessionIds = new ConcurrentHashMap<>();
    private HttpServer server;
    private HttpClient client;

    @BeforeEach
    void start() {
        var config = HttpServerConfig.httpServerConfig("ws-test", 0);

        for (var path : PATHS) {
            seen.put(path, new LinkedBlockingQueue<>());
            sessionIds.put(path, new LinkedBlockingQueue<>());
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
        try (var socket = rawSocket()) {
            socket.getOutputStream()
                  .write(UPGRADE_TO_UNKNOWN.getBytes(US_ASCII));

            var response = new String(socket.getInputStream().readAllBytes(), US_ASCII);

            assertThat(response).startsWith("HTTP/1.1 404")
                                .containsIgnoringCase("connection: close")
                                .containsIgnoringCase("content-length: 0");
        }
    }

    /// RFC 6455 4.1: a message may arrive in fragments; the handler receives it whole, text and binary alike.
    @Test
    void fragmentedMessages_reachTheHandlerWhole() throws Exception {
        var listener = new ClientListener();
        var ws = connect("/ws/b", listener);

        assertThat(next("/ws/b")).isEqualTo("open");
        ws.sendText("hel", false).get(WAIT_SECONDS, TimeUnit.SECONDS);
        ws.sendText("lo", true).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(next("/ws/b")).isEqualTo("text:hello");
        assertThat(listener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/ws/b:hello");

        ws.sendBinary(ByteBuffer.wrap(new byte[3]), false).get(WAIT_SECONDS, TimeUnit.SECONDS);
        ws.sendBinary(ByteBuffer.wrap(new byte[2]), true).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(next("/ws/b")).isEqualTo("binary:5");
    }

    /// The aggregate bound is the server's content length (65536 by default), pinned from both sides: a
    /// fragmented message of exactly that length is delivered whole, so a smaller bound goes red here.
    @Test
    void fragmentedMessageAtTheContentLength_isDeliveredWhole() throws Exception {
        var listener = new ClientListener();
        var ws = connect("/ws/b", listener);

        assertThat(next("/ws/b")).isEqualTo("open");
        ws.sendText("x".repeat(CONTENT_LENGTH / 2), false).get(WAIT_SECONDS, TimeUnit.SECONDS);
        ws.sendText("x".repeat(CONTENT_LENGTH / 2), true).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(next("/ws/b")).isEqualTo("text:" + CONTENT_LENGTH + " chars");
        assertThat(listener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).hasSize("/ws/b:".length() + CONTENT_LENGTH);
    }

    /// ...and one byte more is never delivered: the handler gets only `Close` and the connection ends, so a
    /// larger or missing bound goes red here.
    @Test
    void fragmentedMessageOneOverTheContentLength_isNeverDelivered_andClosesTheConnection() throws Exception {
        var listener = new ClientListener();
        var ws = connect("/ws/c", listener);

        assertThat(next("/ws/c")).isEqualTo("open");
        ws.sendText("x".repeat(CONTENT_LENGTH / 2), false);
        ws.sendText("x".repeat(CONTENT_LENGTH / 2 + 1), true);
        assertThat(next("/ws/c")).as("no message, only Close").isEqualTo("close");
        assertThat(listener.ended.get(WAIT_SECONDS, TimeUnit.SECONDS)).as("the client sees the connection end")
                                                                     .isNotNull();
    }

    /// Two connections to one endpoint each keep their own session: every message goes to the session that
    /// opened on that connection, and the echo goes back on it.
    @Test
    void concurrentConnections_onOneEndpoint_eachKeepTheirOwnSession() throws Exception {
        var firstListener = new ClientListener();
        var secondListener = new ClientListener();
        var first = connect("/ws/a", firstListener);

        assertThat(next("/ws/a")).isEqualTo("open");

        var second = connect("/ws/a", secondListener);

        assertThat(next("/ws/a")).isEqualTo("open");
        first.sendText("one", true);
        assertThat(next("/ws/a")).isEqualTo("text:one");
        second.sendText("two", true);
        assertThat(next("/ws/a")).isEqualTo("text:two");
        assertThat(firstListener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).as("echo on the first connection")
                                                                           .isEqualTo("/ws/a:one");
        assertThat(secondListener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).as("echo on the second connection")
                                                                            .isEqualTo("/ws/a:two");

        var ids = List.copyOf(sessionIds.get("/ws/a"));

        assertThat(ids).hasSize(4);
        assertThat(ids.get(0)).as("two connections, two sessions").isNotEqualTo(ids.get(1));
        assertThat(ids.get(2)).as("the first connection's text").isEqualTo(ids.get(0));
        assertThat(ids.get(3)).as("the second connection's text").isEqualTo(ids.get(1));
    }

    /// RFC 6455 4.2.1: the `Upgrade` value is matched case-insensitively.
    @Test
    void upgradeHeaderValue_isMatchedCaseInsensitively() throws Exception {
        try (var socket = rawSocket()) {
            socket.getOutputStream()
                  .write(upgrade("/ws/a", "WebSocket", "13").getBytes(US_ASCII));

            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 101");
            assertThat(next("/ws/a")).isEqualTo("open");
        }
    }

    /// Endpoints match the raw path, as Netty's handshake does: a percent-encoded spelling of a registered
    /// path is a different path, refused with 404, never handed to the plain HTTP handler.
    @Test
    void percentEncodedEndpointPath_isRefusedWith404() throws Exception {
        try (var socket = rawSocket()) {
            socket.getOutputStream()
                  .write(upgrade("/ws/%61", "websocket", "13").getBytes(US_ASCII));

            assertThat(new String(socket.getInputStream().readAllBytes(), US_ASCII)).startsWith("HTTP/1.1 404");
        }
        assertThat(seen.get("/ws/a")).isEmpty();
    }

    /// RFC 6455 4.4: an unsupported version gets 426 with the connection kept, and the client may retry on
    /// it, here to a different endpoint.
    @Test
    void unsupportedVersion_gets426_andARetryOnTheSameConnectionHandshakes() throws Exception {
        try (var socket = rawSocket()) {
            socket.getOutputStream()
                  .write(upgrade("/ws/a", "websocket", "99").getBytes(US_ASCII));

            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 426")
                                                         .containsIgnoringCase("sec-websocket-version: 13");

            socket.getOutputStream()
                  .write(upgrade("/ws/b", "websocket", "13").getBytes(US_ASCII));

            assertThat(readHead(socket.getInputStream())).startsWith("HTTP/1.1 101");
            assertThat(next("/ws/b")).isEqualTo("open");
        }
        assertThat(seen.get("/ws/a")).isEmpty();
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
        assertThat(seen.get(path).poll(QUIET_MILLIS, TimeUnit.MILLISECONDS)).as("Close delivered exactly once on %s", path)
                                                                           .isNull();
        assertThat(Set.copyOf(sessionIds.get(path))).as("Open, Text and Close share one session on %s", path)
                                                     .hasSize(1);
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

    private Socket rawSocket() throws IOException {
        var socket = new Socket("127.0.0.1", server.port());

        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        return socket;
    }

    private static String upgrade(String path, String upgradeValue, String version) {
        return "GET " + path + " HTTP/1.1\r\n" + "Host: 127.0.0.1\r\n" + "Upgrade: " + upgradeValue + "\r\n"
               + "Connection: Upgrade\r\n" + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
               + "Sec-WebSocket-Version: " + version + "\r\n\r\n";
    }

    /// Reads one response head, up to the blank line, leaving the connection open.
    private static String readHead(InputStream in) throws IOException {
        var head = new StringBuilder();

        while (!head.toString().endsWith("\r\n\r\n")) {
            var next = in.read();

            if (next < 0) {
                break;
            }
            head.append((char) next);
        }

        return head.toString();
    }

    private URI uri(String scheme, String path) {
        return URI.create(scheme + "://127.0.0.1:" + server.port() + path);
    }

    /// Records every message by kind, echoes text prefixed with the endpoint path, closes on "bye".
    private WebSocketHandler recording(String path) {
        return (session, message) -> record(path, session, message);
    }

    private void record(String path, WebSocketSession session, WebSocketMessage message) {
        var log = seen.get(path);

        sessionIds.get(path).add(session.id());
        switch (message) {
            case WebSocketMessage.Open _ -> log.add("open");
            case WebSocketMessage.Text text -> onText(path, log, session, text.content());
            case WebSocketMessage.Binary binary -> log.add("binary:" + binary.content().length);
            case WebSocketMessage.Close _ -> log.add("close");
        }
    }

    /// Long texts are recorded by length, so a failure message stays readable.
    private static void onText(String path, BlockingQueue<String> log, WebSocketSession session, String text) {
        log.add("text:" + (text.length() > 100 ? text.length() + " chars" : text));
        if ("bye".equals(text)) {
            session.close();
        } else {
            session.send(path + ":" + text);
        }
    }

    private static final class ClientListener implements WebSocket.Listener {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        /// How the connection ended, by close frame or by error.
        private final CompletableFuture<String> ended = new CompletableFuture<>();
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
            ended.complete("close:" + statusCode);

            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ended.complete("error:" + error.getClass().getSimpleName());
        }
    }
}
