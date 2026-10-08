// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.config.ConfigService;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// A node whose HTTP-listener TLS cannot be built refuses to START instead of serving HTTP, through the real assembly
/// (`AetherNode.aetherNode` then `start()`): the management listener (cluster-level `[tls]`) and the app listener
/// (`[app-http.tls]`) each fail the node's start with the typed cause, and nothing listens on the configured port.
class AetherNodeBrokenTlsBootTest {
    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(15).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenAppHttpTlsCannotBeBuilt() throws Exception {
        var appPort = freePort();
        var base = AppHttpConfig.insecureAppHttpConfig(appPort);
        var appHttp = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                        base.jwtConfig(), base.httpProtocol(), base.apiVersioningDetection(),
                                        base.apiVersionHeaderName(),
                                        Option.some(new AppHttpConfig.AppTls("/missing/app-cert.pem", "/missing/app-key.pem")));

        assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, Option.none(), appPort);
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenManagementTlsCannotBeBuilt() throws Exception {
        var managementPort = freePort();
        var brokenTls = Option.some(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));

        assertRefused(AppHttpConfig.insecureAppHttpConfig(freePort()), managementPort, brokenTls, managementPort);
    }

    private void assertRefused(AppHttpConfig appHttp, int managementPort, Option<TlsConfig> tls, int listenerPort) throws Exception {
        node = AetherNode.aetherNode(minimalConfig(appHttp, managementPort, tls), () -> {})
                         .fold(cause -> {
                                   throw new AssertionError("assembly failed before start: " + cause.message());
                               },
                               booted -> booted);

        var started = node.start().await(timeSpan(45).seconds());
        var listening = connects(listenerPort);

        assertThat(listening).as("nothing listens on the configured port, TLS or plain").isFalse();
        assertThat(started.isFailure()).as("the node refuses to start").isTrue();
        started.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class));
    }

    private static AetherNodeConfig minimalConfig(AppHttpConfig appHttp, int managementPort, Option<TlsConfig> tls) {
        var self = NodeId.nodeId("broken-tls-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self)
                                .coreNodes(List.of(selfInfo))
                                .managementPort(managementPort)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(appHttp)
                                .tls(tls)
                                .quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none())
                                .configProvider(Option.none())
                                .environment(Option.none())
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(Map.of())
                                .build();
    }

    private static boolean connects(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
