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
