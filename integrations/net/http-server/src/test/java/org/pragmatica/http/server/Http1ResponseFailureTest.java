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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2310: the HTTP/1.1 twin of `Http3ResponseFailureTest`. A response that fails before it is handed to the channel
/// (here a content type that throws) is answered `500` promptly; before the fix the writer had already marked itself
/// written, the error path wrote nothing, and the client waited for its own read timeout.
class Http1ResponseFailureTest {
    /// The client's read timeout; a hung response surfaces as a `SocketTimeoutException` well after `PROMPT_MS`.
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final long PROMPT_MS = 2_000;
    private static final byte[] BODY = "hello".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    /// Control: the same request with a content type that renders gets its 200.
    @Test
    void response_withoutFailure_is200() throws IOException {
        start((_, writer) -> writer.write(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN));

        assertThat(exchange().status()).isEqualTo(200);
    }

    @Test
    void failureBeforeTheResponseIsSent_isAnswered500_promptly() throws IOException {
        start((_, writer) -> writer.write(HttpStatus.OK, BODY, THROWING_CONTENT_TYPE));

        var outcome = exchange();

        assertThat(outcome.status()).as("the unsent response is replaced by a 500").isEqualTo(500);
        assertThat(outcome.elapsedMs()).as("answered promptly, not at the client's timeout").isLessThan(PROMPT_MS);
    }

    /// The promise `writeAsync` returns tells the handler what the client saw: a response replaced by the 500 fails it
    /// with the build failure.
    @Test
    void writeAsyncPromise_failsWhenTheResponseIsAnswered500() throws Exception {
        var failed = new CompletableFuture<Boolean>();

        start((_, writer) -> writer.writeAsync(HttpStatus.OK, BODY, THROWING_CONTENT_TYPE)
                                   .onResult(result -> failed.complete(result.isFailure())));

        assertThat(exchange().status()).isEqualTo(500);
        assertThat(failed.get(5, TimeUnit.SECONDS)).as("the write promise failed").isTrue();
    }

    /// Control: a response that is sent resolves the promise successfully.
    @Test
    void writeAsyncPromise_succeeds_whenTheResponseIsSent() throws Exception {
        var succeeded = new CompletableFuture<Boolean>();

        start((_, writer) -> writer.writeAsync(HttpStatus.OK, BODY, CommonContentType.TEXT_PLAIN)
                                   .onResult(result -> succeeded.complete(result.isSuccess())));

        assertThat(exchange().status()).isEqualTo(200);
        assertThat(succeeded.get(5, TimeUnit.SECONDS)).as("the write promise succeeded").isTrue();
    }

    private static final ContentType THROWING_CONTENT_TYPE = new ContentType() {
        @Override
        public String headerText() {
            throw new IllegalStateException("content type cannot be rendered");
        }

        @Override
        public ContentCategory category() {
            return ContentCategory.TEXT;
        }
    };

    private void start(BiConsumer<HttpRequest, ResponseWriter> handler) {
        server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("h1-failure", 0), handler)
                           .await(timeSpan(10).seconds())
                           .unwrap();
    }

    private record Outcome(int status, long elapsedMs) {}

    private Outcome exchange() throws IOException {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(READ_TIMEOUT_MS);

            var sent = System.nanoTime();

            socket.getOutputStream()
                  .write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            var elapsedMs = (System.nanoTime() - sent) / 1_000_000;

            return new Outcome(Integer.parseInt(response.substring(9, 12)), elapsedMs);
        }
    }
}
