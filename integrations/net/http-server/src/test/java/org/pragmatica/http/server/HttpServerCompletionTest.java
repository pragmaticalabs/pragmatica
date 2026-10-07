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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class HttpServerCompletionTest {
    @Test void start_ephemeralPort_reportsBoundPortAndFlushesResponse() throws Exception {
        var completion = new AtomicReference<Promise<Unit>>();
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("test", 0), (_, writer) ->
            completion.set(writer.writeAsync(HttpStatus.OK, "hello".getBytes(), CommonContentType.TEXT_PLAIN)))
            .await(timeSpan(5).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(server.port()).isPositive();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port())).build(),
                                       HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).isEqualTo("hello");
            assertThat(completion.get().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        } finally {
            assertThat(server.stop().await(timeSpan(10).seconds()).isSuccess()).isTrue();
        }
    }

    @Test void writeAsync_closedTransport_reportsFailureAndRepeatedWriteSharesCompletion() throws Exception {
        var writer = new java.util.concurrent.CompletableFuture<ResponseWriter>();
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("test", 0), (_, response) -> writer.complete(response))
            .await(timeSpan(5).seconds()).unwrap();
        try (var client = HttpClient.newHttpClient()) {
            var request = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port())).build(),
                                           HttpResponse.BodyHandlers.ofString());
            var response = writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            server.stop().await(timeSpan(10).seconds()).unwrap();
            var completion = response.writeAsync(HttpStatus.OK, "late".getBytes(), CommonContentType.TEXT_PLAIN);
            assertThat(completion.await(timeSpan(5).seconds()).isFailure()).isTrue();
            assertThat(response.writeAsync(HttpStatus.OK, "again".getBytes(), CommonContentType.TEXT_PLAIN)).isSameAs(completion);
            request.cancel(true);
        } finally {
            server.stop().await(timeSpan(10).seconds());
        }
    }

    @Test void start_invalidTls_failsInsteadOfOpeningPlaintextListener() {
        var config = HttpServerConfig.httpServerConfig("tls-test", 0)
            .withTls(TlsConfig.server(Path.of("/missing/terra-cert.pem"), Path.of("/missing/terra-key.pem")));
        var server = HttpServer.httpServer(config, (_, writer) -> writer.okText("must not serve"))
            .await(timeSpan(10).seconds());
        server.onSuccess(value -> value.stop());
        assertThat(server.isFailure()).isTrue();
    }
}
