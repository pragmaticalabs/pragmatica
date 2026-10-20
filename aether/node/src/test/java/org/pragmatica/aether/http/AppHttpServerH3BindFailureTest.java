// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// An HTTP/3 BIND failure (the UDP port is taken) is fatal when HTTP/3 is the ONLY app protocol, because swallowing it
/// leaves a node that reports a successful start with no app listener. When HTTP/1.1 also serves (BOTH) it stays
/// non-fatal and the node keeps serving H1. Adapted from v-2025's probe.
class AppHttpServerH3BindFailureTest {
    @Test
    @Timeout(120)
    void start_h3OnlyAndUdpPortTaken_failsInsteadOfRunningWithNoListener() throws Exception {
        var port = TlsProbe.freeTcpPort();

        try (var blocker = udpBlocker(port)) {
            assertThat(blocker.isBound()).as("control: the UDP port is held").isTrue();
            var server = appHttpServer(port, HttpProtocol.H3);

            try {
                var outcome = server.start().await(timeSpan(30).seconds());

                assertThat(outcome.isFailure()).as("an H3-only start whose bind fails must fail").isTrue();
            } finally {
                server.stop().await(timeSpan(30).seconds());
            }
        }
    }

    @Test
    @Timeout(120)
    void start_bothAndUdpPortTaken_staysNonFatalAndServesH1() throws Exception {
        var port = TlsProbe.freeTcpPort();

        try (var blocker = udpBlocker(port)) {
            assertThat(blocker.isBound()).as("control: the UDP port is held").isTrue();
            var server = appHttpServer(port, HttpProtocol.BOTH);

            try {
                var outcome = server.start().await(timeSpan(30).seconds());

                assertThat(outcome.isSuccess()).as("BOTH keeps starting when only the H3 listener cannot bind").isTrue();
                assertThat(TlsProbe.httpsStatus(port)).as("and the H1 listener serves a real request").isPositive();
            } finally {
                server.stop().await(timeSpan(30).seconds());
            }
        }
    }

    private static DatagramSocket udpBlocker(int port) throws Exception {
        var socket = new DatagramSocket(null);

        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(port));

        return socket;
    }

    private static AppHttpServer appHttpServer(int port, HttpProtocol protocol) {
        var base = AppHttpConfig.insecureAppHttpConfig(port);
        var config = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                       base.jwtConfig(), protocol, base.apiVersioningDetection(), base.apiVersionHeaderName(),
                                       base.tls());

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("h3-bind-node").unwrap(),
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.some(TlsConfig.selfSignedServer()),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }
}
