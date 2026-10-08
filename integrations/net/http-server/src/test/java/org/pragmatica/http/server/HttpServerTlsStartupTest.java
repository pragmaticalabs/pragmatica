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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A TLS configuration that fails to build refuses startup instead of silently serving plain HTTP, and the
/// server reports the port it actually bound. Ports come from the OS so the tests do not collide with other
/// modules running concurrently (#939).
class HttpServerTlsStartupTest {
    private static final long WAIT_MS = 10_000L;

    private static HttpServerConfig brokenTls(String name, int port) {
        return HttpServerConfig.httpServerConfig(name, port)
                               .withTls(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));
    }

    /// RED at rc4: the failed TLS build was swallowed and the listener opened in plain text.
    @Test
    void start_unbuildableTls_failsWithTypedCauseNamingTlsAndConfig() {
        var port = freeTcpPort();
        var outcome = HttpServer.httpServer(brokenTls("mgmt-tls", port), (_, writer) -> writer.okText("must not serve"))
                                .await(timeSpan(WAIT_MS).millis());

        outcome.onSuccess(HttpServer::stop);
        assertThat(outcome.isFailure()).as("startup must fail, not fall back to plain HTTP").isTrue();
        outcome.onFailure(cause -> {
            assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class);
            assertThat(cause.message()).contains("TLS").contains("mgmt-tls").contains("port " + port);
        });
    }

    /// RED at rc4 (the plain-HTTP listener answers): nothing listens on the configured port after the refusal.
    @Test
    void start_unbuildableTls_opensNoListener() throws Exception {
        var port = freeTcpPort();
        var outcome = HttpServer.httpServer(brokenTls("no-listener", port), (_, writer) -> writer.okText("plain"))
                                .await(timeSpan(WAIT_MS).millis());

        outcome.onSuccess(HttpServer::stop);
        assertThat(outcome.isFailure()).as("control: startup refused").isTrue();
        try (var socket = new Socket()) {
            assertThat(connects(socket, port)).as("no plain-text listener on the TLS port").isFalse();
        }
    }

    /// Owned event loops are released before the TLS failure is reported, like a failed bind.
    @Test
    void createOwning_unbuildableTls_terminatesOwnedGroupsBeforeReporting() {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        var groups = Set.of(boss, worker);
        var terminatedAtFailure = new AtomicReference<Boolean>();
        var outcome = NettyHttpServer.createOwning(brokenTls("owning-tls", freeTcpPort()), (_, _) -> {}, boss, worker)
                                     .fold(result -> {
                                         result.onFailure(_ -> terminatedAtFailure.set(groups.stream()
                                                                                              .allMatch(EventLoopGroup::isTerminated)));
                                         return Promise.resolved(result);
                                     })
                                     .await(timeSpan(WAIT_MS).millis());

        assertThat(outcome.isFailure()).as("control: startup refused").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class));
        assertThat(terminatedAtFailure.get()).as("owned groups terminated when the failure was reported").isTrue();
    }

    /// Control, so the refusal is not "TLS never works": a buildable TLS config still starts.
    @Test
    void start_selfSignedTls_starts() {
        var outcome = HttpServer.httpServer(HttpServerConfig.httpServerConfig("tls-ok", 0)
                                                            .withTls(TlsConfig.selfSignedServer()),
                                            (_, writer) -> writer.okText("secure"))
                                .await(timeSpan(WAIT_MS).millis());

        assertThat(outcome.isSuccess()).isTrue();
        outcome.onSuccess(server -> assertThat(server.stop().await(timeSpan(WAIT_MS).millis()).isSuccess()).isTrue());
    }

    /// RED at rc4: port 0 was reported as 0.
    @Test
    void start_ephemeralPort_reportsBoundPort() throws Exception {
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("bound-port", 0),
                                           (_, writer) -> writer.write(HttpStatus.OK, "hello".getBytes(), CommonContentType.TEXT_PLAIN))
                               .await(timeSpan(WAIT_MS).millis())
                               .unwrap();

        try (var client = HttpClient.newHttpClient()) {
            assertThat(server.port()).isPositive();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port())).build(),
                                       HttpResponse.BodyHandlers.ofString());

            assertThat(response.body()).isEqualTo("hello");
        } finally {
            assertThat(server.stop().await(timeSpan(WAIT_MS).millis()).isSuccess()).isTrue();
        }
    }

    private static boolean connects(Socket socket, int port) {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
