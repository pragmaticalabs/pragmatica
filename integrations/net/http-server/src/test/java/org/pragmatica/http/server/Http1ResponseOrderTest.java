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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2484, #2485: responses on one HTTP/1.1 connection are written in the order of their requests (RFC 9112 section 9.3.2),
/// whichever handler finishes first; a response that ends the connection (`Connection: close`, or a refusal that closes
/// it) is written only after every earlier response. `/slow` answers from another thread after a delay, every other path
/// at once.
class Http1ResponseOrderTest {
    private static final Pattern BODY_PATH = Pattern.compile("\"path\":\"([^\"]*)\"");
    private static final Pattern STATUS = Pattern.compile("HTTP/1\\.1 (\\d{3})");
    private static final int TRIALS = 300;
    private final List<String> dispatched = new CopyOnWriteArrayList<>();

    @Test
    void slowEarlierRequest_isAnsweredBeforeTheFastOnesBehindIt() throws IOException {
        var response = exchange("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n" + "GET /f1 HTTP/1.1\r\nHost: h\r\n\r\n" + "GET /f2 HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");

        assertThat(bodies(response)).containsExactly("slow", "f1", "f2");
    }

    @Test
    void earlierAsyncResponse_isDeliveredBeforeALaterConnectionClose() throws IOException {
        var response = exchange("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n" + "GET /c HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n" + "GET /after HTTP/1.1\r\nHost: h\r\n\r\n");

        assertThat(bodies(response)).containsExactly("slow", "c");
        assertThat(dispatched).as("nothing behind the close request is dispatched").containsExactly("/slow", "/c");
    }

    @Test
    void earlierAsyncResponse_isDeliveredBeforeARefusalThatClosesTheConnection() throws IOException {
        var response = exchange("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n" + "POST /big HTTP/1.1\r\nHost: h\r\nContent-Length: 2000000000\r\n\r\n");

        assertThat(statuses(response)).containsExactly("200", "413");
    }

    @Test
    void earlierAsyncResponse_isDeliveredBeforeAWebSocketUpgradeToAPathWithNoRoute() throws IOException {
        var response = exchange("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n" + upgrade("/nowhere", "13"));

        assertThat(statuses(response)).containsExactly("200", "404");
    }

    @Test
    void earlierAsyncResponse_isDeliveredBeforeAnUnsupportedWebSocketVersionAnswer() throws IOException {
        var response = exchange("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n" + upgrade("/ws", "99") + "GET /f1 HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");

        assertThat(statuses(response)).containsExactly("200", "426", "200");
    }

    private static String upgrade(String path, String version) {
        return "GET " + path + " HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: " + version + "\r\n\r\n";
    }

    /// B1: an earlier response completed on another thread with no delay and a later one completed synchronously on the
    /// event loop must still leave in request order. Many fresh connections, because the inversion is a race.
    @Test
    void zeroDelayAsyncAnswer_followedBySynchronousOne_isAnsweredInRequestOrder() throws IOException {
        var server = start(config(timeSpan(60).seconds()));

        try {
            var inverted = 0;

            for (var i = 0; i < TRIALS; i++) {
                var response = send(server.port(), "GET /a" + i + " HTTP/1.1\r\nHost: h\r\n\r\n" + "GET /b" + i + " HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");

                if (!bodies(response).equals(List.of("a" + i, "b" + i))) {
                    inverted++;
                }
            }

            assertThat(inverted).as("connections whose responses left out of request order").isZero();
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// T1: a handler that never answers is answered 504 in its place once the response timeout passes: the earlier response
    /// is written first, then the 504, then the connection closes; nothing behind it is written.
    @Test
    void handlerThatNeverAnswers_isAnswered504InItsPlace_andTheConnectionCloses() throws IOException {
        var server = start(config(timeSpan(700).millis()));

        try {
            var response = send(server.port(), "GET /f1 HTTP/1.1\r\nHost: h\r\n\r\n" + "GET /hang HTTP/1.1\r\nHost: h\r\n\r\n" + "GET /f2 HTTP/1.1\r\nHost: h\r\n\r\n");

            assertThat(statuses(response)).containsExactly("200", "504");
            assertThat(bodies(response)).containsExactly("f1");
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// T2: behind a handler that never answers, the codec accepts 128 unanswered requests and closes the connection at the
    /// next; the answers already computed for the 127 behind the hung one are discarded with it, and nothing is written. The
    /// loss is the documented bound of the response order (see `ResponseSequence`).
    @Test
    void overTheCodecsPipelineCap_behindAHungHandler_closesTheConnectionAndDiscardsTheHeldAnswers() throws IOException {
        var server = start(config(timeSpan(0).millis()));

        try {
            var raw = new StringBuilder("GET /hang HTTP/1.1\r\nHost: h\r\n\r\n");

            for (var i = 0; i < 200; i++) {
                raw.append("GET /f").append(i).append(" HTTP/1.1\r\nHost: h\r\n\r\n");
            }

            assertThat(statuses(send(server.port(), raw.toString()))).isEmpty();
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    /// G1: an accepted WebSocket upgrade is a response too: its 101 is written after the earlier response.
    @Test
    void acceptedWebSocketUpgrade_isAnsweredAfterTheEarlierResponse() throws IOException {
        var server = start(config(timeSpan(60).seconds()));

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /slow HTTP/1.1\r\nHost: h\r\n\r\n" + upgrade("/ws", "13")).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var seen = new StringBuilder();
            var buffer = new byte[4096];

            while (statuses(seen.toString()).size() < 2) {
                var read = socket.getInputStream().read(buffer);

                if (read < 0) {
                    break;
                }
                seen.append(new String(buffer, 0, read, StandardCharsets.US_ASCII));
            }

            assertThat(statuses(seen.toString())).containsExactly("200", "101");
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    private HttpServerConfig config(org.pragmatica.lang.io.TimeSpan responseTimeout) {
        return HttpServerConfig.httpServerConfig("response-order", 0)
                               .withWebSocket(webSocketEndpoint("/ws", () -> (_, _) -> {}))
                               .withResponseTimeout(responseTimeout);
    }

    private HttpServer start(HttpServerConfig config) {
        return HttpServer.httpServer(config, this::handle).await(timeSpan(10).seconds()).unwrap();
    }

    private static String send(int port, String raw) throws IOException {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private String exchange(String raw) throws IOException {
        var config = HttpServerConfig.httpServerConfig("response-order", 0).withWebSocket(webSocketEndpoint("/ws", () -> (_, _) -> {}));
        var server = HttpServer.httpServer(config, this::handle).await(timeSpan(10).seconds()).unwrap();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    private void handle(HttpRequest request, ResponseWriter writer) {
        dispatched.add(request.path());

        var body = "{\"path\":\"" + request.path().substring(1) + "\"}";

        if (request.path().equals("/slow")) {
            CompletableFuture.runAsync(() -> writer.ok(body), CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS));
        } else if (request.path().equals("/hang")) {
            return;
        } else if (request.path().startsWith("/a")) {
            CompletableFuture.runAsync(() -> writer.ok(body));
        } else {
            writer.ok(body);
        }
    }

    private static List<String> bodies(String response) {
        return matches(BODY_PATH, response);
    }

    private static List<String> statuses(String response) {
        return matches(STATUS, response);
    }

    private static List<String> matches(Pattern pattern, String text) {
        var found = new ArrayList<String>();
        var matcher = pattern.matcher(text);

        while (matcher.find()) {
            found.add(matcher.group(1));
        }

        return found;
    }
}
