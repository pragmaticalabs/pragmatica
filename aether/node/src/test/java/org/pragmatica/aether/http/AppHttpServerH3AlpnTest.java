// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

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

/// #2310: the app HTTP/3 listener completes a request from a standard HTTP/3 client, which offers the `h3` ALPN token.
/// QUIC requires ALPN; before the fix every QUIC server context was built without a token, so no client ever completed
/// the handshake. Each production path that builds the context is covered: self-signed (no TLS configured), a
/// configured certificate, and the context rebuilt by certificate rotation.
class AppHttpServerH3AlpnTest {
    private static final String STATUS = "\\d{3}";

    @Test
    @Timeout(120)
    void h3Listener_selfSigned_servesAStandardHttp3Client() throws Exception {
        var port = TlsProbe.freeTcpPort();

        assertThat(statusAfterStart(port, Option.none())).as("an HTTP/3 client gets an HTTP status").matches(STATUS);
    }

    @Test
    @Timeout(120)
    void h3Listener_configuredCertificate_servesAStandardHttp3Client() throws Exception {
        var port = TlsProbe.freeTcpPort();

        assertThat(statusAfterStart(port, Option.some(TlsConfig.selfSignedServer()))).matches(STATUS);
    }

    @Test
    @Timeout(180)
    void h3Listener_afterCertificateRotation_stillServesAStandardHttp3Client() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var server = appHttpServer(port, Option.some(TlsConfig.selfSignedServer()));

        try {
            assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: the server starts").isTrue();
            assertThat(server.rotateCertificate(TlsProbe.validBundle("h3-alpn-node")).await(timeSpan(60).seconds())
                             .isSuccess()).as("control: the rotation applies").isTrue();
            assertThat(H3Probe.getStatus(port)).as("the rotated HTTP/3 listener serves").matches(STATUS);
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private static String statusAfterStart(int port, Option<TlsConfig> tls) throws Exception {
        var server = appHttpServer(port, tls);

        try {
            assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: the server starts").isTrue();

            return H3Probe.getStatus(port);
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private static AppHttpServer appHttpServer(int port, Option<TlsConfig> tls) {
        var base = AppHttpConfig.insecureAppHttpConfig(port);
        var config = new AppHttpConfig(base.enabled(), base.port(), base.apiKeys(), base.maxRequestSize(), base.securityMode(),
                                       base.jwtConfig(), HttpProtocol.H3, base.apiVersioningDetection(),
                                       base.apiVersionHeaderName(), base.tls());

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("h3-alpn-node").unwrap(),
                                           HttpRouteRegistry.httpRouteRegistry(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           tls,
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           Option.<DeploymentManager> none());
    }
}
