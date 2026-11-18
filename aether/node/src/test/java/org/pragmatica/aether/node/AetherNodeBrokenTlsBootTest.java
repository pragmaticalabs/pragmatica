// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.ServerSocket;
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
/// (`[app-http.tls]`) each fail the node's start with the typed cause, and the refusal precedes any bind (the refused port is held by the test, #3321).
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
        try (var held = new ServerSocket(0)) {
            var base = AppHttpConfig.insecureAppHttpConfig(held.getLocalPort());
            var appHttp = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                            base.jwtConfig(), base.httpProtocol(), base.apiVersioningDetection(),
                                            base.apiVersionHeaderName(),
                                            Option.some(new AppHttpConfig.AppTls("/missing/app-cert.pem", "/missing/app-key.pem")));

            assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, Option.none(), HttpProtocol.H1);
        }
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenManagementTlsCannotBeBuilt() throws Exception {
        var brokenTls = Option.some(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));

        try (var held = new ServerSocket(0)) {
            assertRefused(AppHttpConfig.insecureAppHttpConfig(freePort()), held.getLocalPort(), brokenTls, HttpProtocol.H1);
        }
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

        assertRefused(appOff, freePort(), brokenTls, HttpProtocol.H3);
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void start_refuses_whenAppHttpTlsCannotBeBuiltOverH3Only() throws Exception {
        var base = AppHttpConfig.insecureAppHttpConfig(freePort());
        var appHttp = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                        base.jwtConfig(), HttpProtocol.H3, base.apiVersioningDetection(),
                                        base.apiVersionHeaderName(), Option.none());
        var brokenTls = Option.some(TlsConfig.server(Path.of("/missing/node-cert.pem"), Path.of("/missing/node-key.pem")));

        assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, brokenTls, HttpProtocol.H1);
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

        try (var held = new ServerSocket(0)) {
            var base = AppHttpConfig.insecureAppHttpConfig(held.getLocalPort());
            var appHttp = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                            base.jwtConfig(), base.httpProtocol(), base.apiVersioningDetection(),
                                            base.apiVersionHeaderName(),
                                            Option.some(new AppHttpConfig.AppTls(dir.resolve("app.crt").toString(),
                                                                                 dir.resolve("app.key").toString())));

            assertRefused(appHttp, AetherNodeConfig.MANAGEMENT_DISABLED, Option.none(), HttpProtocol.H1);
        }
    }

    private void assertRefused(AppHttpConfig appHttp,
                               int managementPort,
                               Option<TlsConfig> tls,
                               HttpProtocol managementProtocol) {
        node = AetherNode.aetherNode(minimalConfig(appHttp, managementPort, tls, managementProtocol), () -> {})
                         .fold(cause -> {
                                   throw new AssertionError("assembly failed before start: " + cause.message());
                               },
                               booted -> booted);

        var started = node.start().await(timeSpan(45).seconds());

        assertThat(started.isFailure()).as("the node refuses to start").isTrue();
        started.onFailure(cause -> assertThat(cause).as("the refused port is held by the test: a bind attempt would surface as a bind error, not as the TLS refusal")
                                                    .isInstanceOf(HttpServerError.TlsFailed.class));
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

    private static int freePort() {
        return org.pragmatica.aether.node.ClusterTestPorts.freeTcpAndUdpPort();
    }
}
