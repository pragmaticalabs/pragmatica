// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1052: the `dht-admission` component of `/health/ready`. A node whose post-formation DHT
/// encryption-marker check is still retrying stays `JOINING`, and this component is what says why --
/// "visibly not-ready, not silently half-up". The pending set itself is derived by
/// `StorageFactory.pendingDhtAdmissions` (pinned in `StorageFactoryDhtMarkerRetryTest`).
class StatusRoutesDhtAdmissionTest {
    private static final NodeId PROXY_SELF = new NodeId("proxy-self");

    @Test
    void buildDhtAdmissionHealth_reportsUp_whenNoInstanceIsPending() {
        var health = StatusRoutes.buildDhtAdmissionHealth(List.of());

        assertThat(health.name()).isEqualTo("dht-admission");
        assertThat(health.status()).isEqualTo("UP");
    }

    @Test
    void buildDhtAdmissionHealth_reportsDownNamingEveryPendingInstance_whenChecksArePending() {
        var health = StatusRoutes.buildDhtAdmissionHealth(List.of("artifacts", "content"));

        assertThat(health.name()).isEqualTo("dht-admission");
        assertThat(health.status()).as("a pending marker check must read DOWN, never UP").isEqualTo("DOWN");
        assertThat(health.detail()).as("the operator must see WHICH instances hold the node not-ready")
                                   .contains("artifacts")
                                   .contains("content");
    }

    /// The wiring pin: the component above is only an operator signal if `/health/ready` actually
    /// carries it. Stubs exactly what `buildReadinessResponse` reads; any other call fails the proxy.
    @Test
    void buildReadinessResponse_carriesDhtAdmissionComponent() {
        var routes = StatusRoutes.statusRoutes(StatusRoutesDhtAdmissionTest::stubNode, StatusRoutesDhtAdmissionTest::stubAppHttpServer);

        assertThat(routes.buildReadinessResponse()
                         .components()
                         .stream()
                         .map(ComponentHealth::name)
                         .toList()).as("/health/ready must report the dht-admission component")
                                   .contains("dht-admission");
    }

    private static ManageableNode stubNode() {
        var lifecycle = NodeLifecycle.nodeLifecycle();

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "self" -> PROXY_SELF;
                                                           case "nodeLifecycle" -> lifecycle;
                                                           case "isReady" -> false;
                                                           case "quorumLossSnapshot" -> Option.some(new QuorumLossSnapshot(1, 1, false, false));
                                                           case "storageSetups" -> Map.of();
                                                           default -> throw new UnsupportedOperationException("Not stubbed in test proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    private static AppHttpServer stubAppHttpServer() {
        return (AppHttpServer) Proxy.newProxyInstance(AppHttpServer.class.getClassLoader(),
                                                      new Class[]{AppHttpServer.class},
                                                      (_, method, _) -> switch (method.getName()) {
                                                          case "isRouteReady" -> false;
                                                          default -> throw new UnsupportedOperationException("Not stubbed in test proxy: "
                                                                                                             + method.getName());
                                                      });
    }
}
