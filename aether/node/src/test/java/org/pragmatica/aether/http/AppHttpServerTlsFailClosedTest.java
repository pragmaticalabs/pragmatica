// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// An app-HTTP listener whose operator-supplied `[app-http.tls]` cannot be built refuses to start. Before this
/// the HTTP/1.1 server swallowed the TLS failure and served the app port in PLAIN TEXT, so a mistyped
/// certificate path turned a TLS port into an open one without any failed start to notice.
class AppHttpServerTlsFailClosedTest {
    private static final NodeId SELF_NODE = NodeId.nodeId("tls-fail-closed-node").unwrap();

    @Test
    @Timeout(60)
    void start_unbuildableAppTls_failsAndOpensNoListener() throws Exception {
        var port = freeTcpPort();
        var server = appHttpServerWithMissingCertificate(port, HttpProtocol.H1);

        var outcome = server.start().await(timeSpan(30).seconds());

        var listening = connects(port);

        server.stop().await(timeSpan(30).seconds());
        assertThat(listening).as("nothing listens on the app port after the refusal").isFalse();
        assertThat(outcome.isFailure()).as("start must fail, not serve plain HTTP").isTrue();
        outcome.onFailure(cause -> {
            assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class);
            assertThat(cause.message()).contains("TLS").contains("app-http");
        });
    }

    @Test
    @Timeout(60)
    void start_unbuildableTlsOverH3Only_failsTyped() throws Exception {
        var server = appHttpServerWithMissingCertificate(freeTcpPort(), HttpProtocol.H3);

        var outcome = server.start().await(timeSpan(30).seconds());

        server.stop().await(timeSpan(30).seconds());
        assertThat(outcome.isFailure()).as("an HTTP/3-only start with unbuildable TLS must fail, not report success").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class));
    }

    private static AppHttpServer appHttpServerWithMissingCertificate(int port, HttpProtocol protocol) {
        var base = AppHttpConfig.insecureAppHttpConfig(port);
        var config = new AppHttpConfig(base.enabled(),
                                       base.port(),
                                       base.apiKeys(),
                                       base.maxRequestSize(),
                                       base.securityMode(),
                                       base.jwtConfig(),
                                       protocol,
                                       base.apiVersioningDetection(),
                                       base.apiVersionHeaderName(),
                                       Option.some(new AppHttpConfig.AppTls("/missing/app-cert.pem", "/missing/app-key.pem")));

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           SELF_NODE,
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.some(TlsConfig.server(java.nio.file.Path.of("/missing/cluster-cert.pem"),
                                                                        java.nio.file.Path.of("/missing/cluster-key.pem"))),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }

    private static boolean connects(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static int freeTcpPort() throws IOException {
        return org.pragmatica.aether.node.ClusterTestPorts.freeTcpAndUdpPort();
    }
}
