// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2418, through the real adapter: a certificate rotation that is overtaken by a quorum drop or by a stop must not leave
/// the listeners it bound alive. The rotation re-binds the CONFIGURED port, so a fixed port tells the new listener apart
/// from nothing: after stop() the port must be free again.
///
/// Ordering: the adapter dispatches the rotation request synchronously and then stops and re-binds on other threads, which
/// takes far longer than the next call here. Each test asserts the rotation is still unresolved when it interferes, so a
/// rotation that somehow finished first fails the test instead of passing it vacuously.
class AppHttpServerRotationRaceTest {
    private static final long CLOSE_BUDGET_MS = 10_000;

    @Test
    @Timeout(120)
    void rotate_overtakenByQuorumDrop_thenStop_closesTheRotatedListener() throws Exception {
        int port = TlsProbe.freeTcpPort();
        var server = appHttpServer(port);

        try {
            TlsProbe.requireStarted(server.start().await(timeSpan(30).seconds()), "app TLS listener");
            var rotation = server.rotateCertificate(TlsProbe.validBundle("rotation-race-node"));

            assertThat(rotation.isResolved()).as("the quorum drop must land mid-rotation").isFalse();
            server.onQuorumStateChange(ClusterStateNotification.passive());

            assertThat(rotation.await(timeSpan(60).seconds()).isSuccess()).as("the rotation itself succeeds").isTrue();
            assertThat(TlsProbe.httpsStatus(port)).as("the rotated listener serves on the configured port").isPositive();
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }

        assertThat(TlsProbe.eventually(() -> !isListening(port), CLOSE_BUDGET_MS))
            .as("after stop(), the listener the rotation bound is closed").isTrue();
    }

    @Test
    @Timeout(120)
    void rotate_overtakenByStop_closesTheRotatedListener() throws Exception {
        int port = TlsProbe.freeTcpPort();
        var server = appHttpServer(port);

        TlsProbe.requireStarted(server.start().await(timeSpan(30).seconds()), "app TLS listener");
        var rotation = server.rotateCertificate(TlsProbe.validBundle("rotation-race-node"));

        assertThat(rotation.isResolved()).as("the stop must land mid-rotation").isFalse();
        server.stop().await(timeSpan(30).seconds());
        var outcome = rotation.await(timeSpan(60).seconds());

        assertThat(outcome.isFailure()).as("a rotation whose new listeners were closed is not reported as applied").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isSameAs(AppHttpRotationError.General.ROTATION_DISCARDED));

        assertThat(TlsProbe.eventually(() -> !isListening(port), CLOSE_BUDGET_MS))
            .as("the listener the rotation bound after stop() is closed").isTrue();
    }

    private static boolean isListening(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException notListening) {
            return false;
        }
    }

    private static AppHttpServer appHttpServer(int port) {
        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(port),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("rotation-race-node").unwrap(),
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
