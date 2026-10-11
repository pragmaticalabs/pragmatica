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
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A certificate rotation that cannot build its TLS material is refused BEFORE the running listener is touched: the app
/// listener keeps serving its current certificate over TLS (never plain HTTP, never nothing), the refusal is typed, and the
/// operator event is raised once and recovered when a later rotation applies. The client side is a real TLS handshake and a
/// real HTTPS request.
class AppHttpServerTlsRotationTest {
    private static final long EVENT_BUDGET_MS = 5_000;
    private static final long QUIET_MS = 300;

    @Test
    @Timeout(120)
    void rotate_unbuildableBundle_keepsServingCurrentCertificateAndRaisesOnceThenRecovers() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var events = TlsProbe.newEventList();
        var server = appHttpServer(port);

        server.setOperatorWarningSink(TlsProbe.recordingSink(events));
        try {
            assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: TLS server started").isTrue();
            var original = TlsProbe.presentedCertificate(port);

            var refused = server.rotateCertificate(TlsProbe.garbageBundle()).await(timeSpan(30).seconds());

            assertThat(refused.isFailure()).as("the rotation is refused").isTrue();
            refused.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsRotationRefused.class));
            assertThat(TlsProbe.sameCertificate(original, TlsProbe.presentedCertificate(port)))
                .as("the listener still presents its CURRENT certificate").isTrue();
            assertThat(TlsProbe.httpsStatus(port)).as("a real HTTPS request is still served").isPositive();
            assertThat(TlsProbe.plainHttpAnswered(port)).as("no plain-HTTP fallback").isFalse();
            assertThat(TlsProbe.eventually(() -> TlsProbe.countOf(events, "http-tls-rotation-refused") == 1, EVENT_BUDGET_MS))
                .as("the refusal is raised as an operator event").isTrue();

            server.rotateCertificate(TlsProbe.garbageBundle()).await(timeSpan(30).seconds());
            Thread.sleep(QUIET_MS);
            assertThat(TlsProbe.countOf(events, "http-tls-rotation-refused")).as("a repeated refusal is not re-raised").isEqualTo(1);
            assertThat(TlsProbe.countOf(events, "http-tls-rotation-restored")).as("no recovery while still refused").isZero();

            var mismatched = server.rotateCertificate(TlsProbe.mismatchedBundle()).await(timeSpan(60).seconds());

            assertThat(mismatched.isFailure()).as("a key that does not match the certificate is refused").isTrue();
            mismatched.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsRotationRefused.class));
            assertThat(TlsProbe.sameCertificate(original, TlsProbe.presentedCertificate(port)))
                .as("after a mismatched bundle the listener still presents its CURRENT certificate").isTrue();
            assertThat(TlsProbe.httpsStatus(port)).as("and still answers HTTPS").isPositive();
            assertThat(TlsProbe.countOf(events, "http-tls-rotation-refused")).as("the mismatch is not re-raised").isEqualTo(1);

            var good = TlsProbe.validBundle("app-rotation-node");
            var applied = server.rotateCertificate(good).await(timeSpan(60).seconds());

            assertThat(applied.isSuccess()).as("a valid bundle rotates").isTrue();
            assertThat(TlsProbe.sameCertificate(original, TlsProbe.presentedCertificate(port)))
                .as("after the valid rotation the certificate changed").isFalse();
            assertThat(TlsProbe.httpsStatus(port)).isPositive();
            assertThat(TlsProbe.eventually(() -> TlsProbe.countOf(events, "http-tls-rotation-restored") == 1, EVENT_BUDGET_MS))
                .as("the recovery event follows the later successful rotation").isTrue();

            server.rotateCertificate(TlsProbe.validBundle("app-rotation-node-2")).await(timeSpan(60).seconds());
            Thread.sleep(QUIET_MS);
            assertThat(TlsProbe.countOf(events, "http-tls-rotation-restored")).as("recovery only once per refusal").isEqualTo(1);
        } finally {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private static AppHttpServer appHttpServer(int port) {
        return AppHttpServer.appHttpServer(AppHttpConfig.insecureAppHttpConfig(port),
                                           ForwardingTimeouts.forwardingTimeouts(),
                                           NodeId.nodeId("tls-rotation-node").unwrap(),
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
