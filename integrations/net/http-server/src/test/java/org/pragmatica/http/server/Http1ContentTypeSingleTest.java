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
import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2984: a handler that passes `Content-Type` as a response header AND as the typed `write` argument (the app server
/// does both) must put exactly one `Content-Type` on the wire. Counted case-insensitively over the raw header block,
/// never by taking the first match.
class Http1ContentTypeSingleTest {
    @Test
    void contentTypeSetAsHeaderAndAsTypedArgument_isOnTheWireOnce() throws IOException {
        var head = exchange((_, writer) -> writer.header("Content-Type", "application/json; charset=UTF-8").ok("{}"));

        assertThat(contentTypeLines(head)).hasSize(1);
    }

    @Test
    void contentTypeSetOnlyAsTypedArgument_isOnTheWireOnce() throws IOException {
        var head = exchange((_, writer) -> writer.ok("{}"));

        assertThat(contentTypeLines(head)).hasSize(1);
    }

    @Test
    void lowerCaseContentTypeHeader_isNotDuplicatedEither() throws IOException {
        var head = exchange((_, writer) -> writer.header("content-type", "text/plain").ok("{}"));

        assertThat(contentTypeLines(head)).hasSize(1);
    }

    /// Pins the other half of the filtered `add` in `NettyResponseWriter.response`: only `Content-Type` is dropped,
    /// every other handler header still reaches the wire, exactly once.
    @Test
    void otherHandlerHeaders_reachTheWireExactlyOnce() throws IOException {
        var head = exchange((_, writer) -> writer.header("X-Trace-Id", "abc").header("X-Other", "v").ok("{}"));

        assertThat(linesNamed(head, "x-trace-id:")).containsExactly("X-Trace-Id: abc");
        assertThat(linesNamed(head, "x-other:")).containsExactly("X-Other: v");
    }

    private static String[] linesNamed(String head, String prefix) {
        return Arrays.stream(head.split("\r\n"))
                     .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix))
                     .toArray(String[]::new);
    }

    private static String[] contentTypeLines(String head) {
        return Arrays.stream(head.split("\r\n"))
                     .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-type:"))
                     .toArray(String[]::new);
    }

    private static String exchange(java.util.function.BiConsumer<org.pragmatica.http.HttpRequest, ResponseWriter> handler) throws IOException {
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("ct-single", 0), handler)
                               .await(timeSpan(10).seconds())
                               .unwrap();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

            return response.substring(0, response.indexOf("\r\n\r\n"));
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }
}
