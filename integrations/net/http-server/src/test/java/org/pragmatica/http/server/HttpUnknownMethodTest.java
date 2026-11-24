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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3679: a request whose method is not in [org.pragmatica.http.HttpMethod] is answered 501 (RFC 9110 §15.6.2) before the
/// classifier (routing and authorization by head) and before any handler, on HTTP/1.1 (the server has no HTTP/2 listener).
/// It used to be folded to `GET` and answered by the `GET` handler.
class HttpUnknownMethodTest {
    private final Recorder recorder = new Recorder();
    private final List<HttpRequest> classified = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void start() {
        RequestClassifier classifier = head -> {
            classified.add(head);

            return Disposition.aggregate(1024);
        };
        var config = HttpServerConfig.httpServerConfig("unknown-method", 0).withClassifier(classifier);

        server = HttpServer.httpServer(config, recorder)
                           .await(timeSpan(10).seconds())
                           .unwrap();
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    /// The control: a known method is classified and dispatched, so the assertions below can fail.
    @Test
    void knownMethod_isClassifiedAndDispatched() throws IOException {
        assertThat(status("GET")).isEqualTo(200);
        assertThat(classified).hasSize(1);
        assertThat(recorder.requests()).hasSize(1);
    }

    @Test
    void propfind_isRefused501_beforeClassifierAndHandler() throws IOException {
        assertThat(status("PROPFIND")).isEqualTo(501);
        assertThat(classified).as("an unknown method must not reach routing or authorization").isEmpty();
        assertThat(recorder.requests()).as("an unknown method must not reach a handler").isEmpty();
    }

    /// `QUERY` (#446) is not yet a method of the server: refused, not answered by the `GET` handler.
    @Test
    void query_isRefused501_notAnsweredAsGet() throws IOException {
        assertThat(status("QUERY")).isEqualTo(501);
        assertThat(recorder.requests()).isEmpty();
    }

    /// A body does not change the answer: the request is refused on its head.
    @Test
    void propfindWithBody_isRefused501_andNeverDispatched() throws IOException {
        assertThat(status("PROPFIND", "Content-Length: 4\r\n", "body")).isEqualTo(501);
        assertThat(classified).isEmpty();
        assertThat(recorder.requests()).isEmpty();
    }

    private int status(String method) throws IOException {
        return status(method, "", "");
    }

    private int status(String method, String extraHeaders, String body) throws IOException {
        var raw = method + " /resource HTTP/1.1\r\nHost: localhost\r\n" + extraHeaders + "Connection: close\r\n\r\n" + body;

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

            return Integer.parseInt(response.substring(9, 12));
        }
    }

    /// Keeps every request the server dispatched and answers 200.
    private static final class Recorder implements BiConsumer<HttpRequest, ResponseWriter> {
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public void accept(HttpRequest request, ResponseWriter writer) {
            requests.add(request);
            writer.ok("{}");
        }

        List<HttpRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
