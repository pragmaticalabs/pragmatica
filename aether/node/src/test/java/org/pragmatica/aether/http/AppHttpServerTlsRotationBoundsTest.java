// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// F6: the HTTP/1.1 listener a certificate rotation rebuilds carries the same `[app-http]` request bounds as the one it
/// replaces. A body over `max_request_size` (1 KiB here, far below the server's 64 KiB default) is refused 413 before
/// the rotation and after it, over a real TLS connection.
class AppHttpServerTlsRotationBoundsTest {
    private static final int LIMIT = 1024;
    private static final int OVER_LIMIT = 4096;

    @Test
    @Timeout(120)
    void rotatedListener_keepsTheConfiguredBodyLimit() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var server = appHttpServer(port);

        try {
            assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: TLS server started").isTrue();
            assertThat(TlsProbe.httpsPostStatus(port, OVER_LIMIT)).as("control: the limit applies before the rotation")
                                                                 .isEqualTo(413);
            var original = TlsProbe.presentedCertificate(port);

            assertThat(server.rotateCertificate(TlsProbe.validBundle("bounds-rotation-node"))
                             .await(timeSpan(60).seconds())
                             .isSuccess()).as("control: a valid bundle rotates").isTrue();
            assertThat(TlsProbe.sameCertificate(original, TlsProbe.presentedCertificate(port)))
                .as("control: the listener was rebuilt").isFalse();

            assertThat(TlsProbe.httpsPostStatus(port, OVER_LIMIT)).as("the rebuilt listener keeps max_request_size")
                                                                 .isEqualTo(413);
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private static AppHttpServer appHttpServer(int port) {
        var config = AppHttpConfig.appHttpConfig(true, port, Map.of(), LIMIT, SecurityMode.NONE, Option.none(), HttpProtocol.H1)
                                  .unwrap();

        return AppHttpServer.appHttpServer(config,
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("tls-rotation-bounds-node").unwrap(),
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
