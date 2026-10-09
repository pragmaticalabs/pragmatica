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


package org.pragmatica.net.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.ServerConfig.serverConfig;

/// A server whose TLS configuration cannot be built refuses to start instead of silently serving (or dialling) in plain
/// text. Ports come from the OS so the tests do not collide with other modules running concurrently (#939).
class ServerTlsFailClosedTest {
    private static TlsConfig missingCertificate() {
        return TlsConfig.server(Path.of("/missing/server-cert.pem"), Path.of("/missing/server-key.pem"));
    }

    @Test
    void start_unbuildableServerTls_failsTypedAndOpensNoListener() {
        var port = freeTcpPort();
        var config = serverConfig("tls-server", port, missingCertificate());
        var outcome = Server.server(config, List::of).await(timeSpan(10).seconds());

        outcome.onSuccess(server -> server.stop(() -> org.pragmatica.lang.Promise.success(org.pragmatica.lang.Unit.unit())));
        assertThat(outcome.isFailure()).as("startup must fail, not run in plain text").isTrue();
        outcome.onFailure(cause -> {
            assertThat(cause).isInstanceOf(TlsError.ServerTlsRefused.class);
            assertThat(cause.message()).contains("TLS").contains("tls-server").contains("server");
        });
        assertThat(connects(port)).as("nothing listens on the TLS port after the refusal").isFalse();
    }

    @Test
    void start_unbuildableClientTls_failsTyped() {
        var port = freeTcpPort();
        var broken = serverConfig("tls-client", port).withClientTls(TlsConfig.clientWithCa(Path.of("/missing/ca.pem")));
        var outcome = Server.server(broken, List::of).await(timeSpan(10).seconds());

        outcome.onSuccess(server -> server.stop(() -> org.pragmatica.lang.Promise.success(org.pragmatica.lang.Unit.unit())));
        assertThat(outcome.isFailure()).as("outgoing TLS that cannot be built must not fall back to plain text").isTrue();
        outcome.onFailure(cause -> {
            assertThat(cause).isInstanceOf(TlsError.ServerTlsRefused.class);
            assertThat(cause.message()).contains("client");
        });
        assertThat(connects(port)).as("nothing listens after the refusal").isFalse();
    }

    /// Control, so the refusal is not "TLS never works": a buildable TLS configuration starts and listens.
    @Test
    void start_selfSignedServerTls_starts() {
        var port = freeTcpPort();
        var outcome = Server.server(serverConfig("tls-ok", port, TlsConfig.selfSignedServer()), List::of)
                            .await(timeSpan(10).seconds());

        assertThat(outcome.isSuccess()).isTrue();
        outcome.onSuccess(server -> {
            assertThat(connects(port)).as("control: the listener is up").isTrue();
            server.stop(() -> org.pragmatica.lang.Promise.success(org.pragmatica.lang.Unit.unit()))
                  .await(timeSpan(15).seconds());
        });
    }

    private static boolean connects(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static int freeTcpPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
