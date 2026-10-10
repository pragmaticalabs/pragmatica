// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.TlsProbe;
import org.pragmatica.aether.http.TlsRotation;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// The cluster renewal gate (`AetherNode.onCertificateRenewed`) builds the cluster transport's QUIC server and client
/// contexts before any rotation. A bundle that does not build is refused there, and that refusal is an operator event
/// (once per transition) with a recovery event when a later renewal is accepted. Before, it was a log line only.
@SuppressWarnings("unchecked")
class CertificateRenewalGateEventTest {
    private static final long EVENT_BUDGET_MS = 5_000;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void renewal_refusedBundles_raiseOncePerTransition_andAnAcceptedRenewalRecovers() throws Exception {
        var events = TlsProbe.newEventList();
        var alarm = TlsRotation.clusterRenewal();
        var clusterNode = mock(RabiaNode.class);
        var appHttpServer = mock(AppHttpServer.class);

        alarm.useSink(TlsProbe.recordingSink(events));
        when(appHttpServer.rotateCertificate(any())).thenReturn(Promise.success(Unit.unit()));

        AetherNode.onCertificateRenewed(TlsProbe.garbageBundle(), clusterNode, appHttpServer, Option::none, alarm);
        AetherNode.onCertificateRenewed(TlsProbe.mismatchedBundle(), clusterNode, appHttpServer, Option::none, alarm);

        assertThat(TlsProbe.eventually(() -> TlsProbe.countOf(events, "cluster-tls-renewal-refused") == 1, EVENT_BUDGET_MS))
            .as("a refused renewal is an operator event").isTrue();
        Thread.sleep(300);
        assertThat(TlsProbe.countOf(events, "cluster-tls-renewal-refused")).as("the second refusal (key mismatch) is not re-raised").isEqualTo(1);
        verify(appHttpServer, times(0)).rotateCertificate(any());

        AetherNode.onCertificateRenewed(TlsProbe.validBundle("renewal-node"), clusterNode, appHttpServer, Option::none, alarm);

        assertThat(TlsProbe.eventually(() -> TlsProbe.countOf(events, "cluster-tls-renewal-restored") == 1, EVENT_BUDGET_MS))
            .as("the next accepted renewal recovers").isTrue();
        verify(appHttpServer, times(1)).rotateCertificate(any());

        AetherNode.onCertificateRenewed(TlsProbe.validBundle("renewal-node-2"), clusterNode, appHttpServer, Option::none, alarm);
        Thread.sleep(300);
        assertThat(TlsProbe.countOf(events, "cluster-tls-renewal-restored")).as("recovery only once per refusal").isEqualTo(1);
    }
}
