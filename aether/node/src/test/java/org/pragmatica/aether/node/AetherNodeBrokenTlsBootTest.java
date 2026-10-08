// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.InetSocketAddress;
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

        assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, Option.none(), HttpProtocol.H1, appPort);
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenManagementTlsCannotBeBuilt() throws Exception {
        var managementPort = freePort();
        var brokenTls = Option.some(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));

        assertRefused(AppHttpConfig.insecureAppHttpConfig(freePort()), managementPort, brokenTls, HttpProtocol.H1, managementPort);
    }

    /// HTTP/3-only: the QUIC context cannot be built and the node used to report a successful start with no listener.
    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenManagementTlsCannotBeBuiltOverH3Only() throws Exception {
        var brokenTls = Option.some(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));

        // The app listener shares the node's `[tls]`, so it is switched off here: only the management HTTP/3 listener may refuse.
        var base = AppHttpConfig.insecureAppHttpConfig(freePort());
        var appOff = new AppHttpConfig(false, base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                       base.jwtConfig(), base.httpProtocol(), base.apiVersioningDetection(),
                                       base.apiVersionHeaderName(), Option.none());

        assertRefused(appOff, freePort(), brokenTls, HttpProtocol.H3, 0);
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenAppHttpTlsCannotBeBuiltOverH3Only() throws Exception {
        var base = AppHttpConfig.insecureAppHttpConfig(freePort());
        var appHttp = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                        base.jwtConfig(), HttpProtocol.H3, base.apiVersioningDetection(),
                                        base.apiVersionHeaderName(), Option.none());
        var brokenTls = Option.some(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));

        assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, brokenTls, HttpProtocol.H1, 0);
    }

    /// A certificate and key that are each valid but do not belong together build a TLS context and then complete no
    /// handshake. The boot refuses them.
    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenAppHttpCertificateAndKeyDoNotMatch(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        var certificateOwner = org.pragmatica.aether.http.TlsProbe.validBundle("boot-cert-node");
        var keyOwner = org.pragmatica.aether.http.TlsProbe.validBundle("boot-key-node");

        java.nio.file.Files.write(dir.resolve("app.crt"), certificateOwner.certificatePem());
        java.nio.file.Files.write(dir.resolve("app.key"), keyOwner.privateKeyPem());
        var appPort = freePort();
        var base = AppHttpConfig.insecureAppHttpConfig(appPort);
        var appHttp = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                        base.jwtConfig(), base.httpProtocol(), base.apiVersioningDetection(),
                                        base.apiVersionHeaderName(),
                                        Option.some(new AppHttpConfig.AppTls(dir.resolve("app.crt").toString(),
                                                                             dir.resolve("app.key").toString())));

        assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, Option.none(), HttpProtocol.H1, appPort);
    }

    private void assertRefused(AppHttpConfig appHttp,
                               int managementPort,
                               Option<TlsConfig> tls,
                               HttpProtocol managementProtocol,
                               int listenerPort) throws Exception {
        node = AetherNode.aetherNode(minimalConfig(appHttp, managementPort, tls, managementProtocol), () -> {})
                         .fold(cause -> {
                                   throw new AssertionError("assembly failed before start: " + cause.message());
                               },
                               booted -> booted);

        var started = node.start().await(timeSpan(45).seconds());
        var listening = managementProtocol == HttpProtocol.H1 && connects(listenerPort);

        assertThat(listening).as("nothing listens on the configured port, TLS or plain").isFalse();
        assertThat(started.isFailure()).as("the node refuses to start").isTrue();
        started.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class));
    }

    private static AetherNodeConfig minimalConfig(AppHttpConfig appHttp,
                                                   int managementPort,
                                                   Option<TlsConfig> tls,
                                                   HttpProtocol managementProtocol) {
        var self = NodeId.nodeId("broken-tls-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

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
                                .managementHttpProtocol(managementProtocol)
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
        return org.pragmatica.aether.node.ClusterTestPorts.freeTcpAndUdpPort();
    }
}
