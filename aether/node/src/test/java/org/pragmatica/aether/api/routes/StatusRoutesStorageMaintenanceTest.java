// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #800: `/api/health` carries a `storage-maintenance` component, DEGRADED while maintenance fails or is overdue, and a
/// degraded maintenance never takes the node out of `/health/ready` (a maintenance fault must not pull a serving node).
class StatusRoutesStorageMaintenanceTest {
    private static final NodeId SELF = new NodeId("maintenance-self");
    private static final Option<String> DEGRADED = Option.some("2 maintenance tick(s) in a row had a failed pass, latest: tier gone");

    @Test
    void storageMaintenanceHealth_noDegradation_isUp() {
        var health = StatusRoutes.storageMaintenanceHealth(Option.none());

        assertThat(health.name()).isEqualTo("storage-maintenance");
        assertThat(health.status()).isEqualTo("UP");
    }

    @Test
    void storageMaintenanceHealth_degradation_isDegradedWithTheReason() {
        var health = StatusRoutes.storageMaintenanceHealth(DEGRADED);

        assertThat(health.status()).isEqualTo("DEGRADED");
        assertThat(health.detail()).contains("tier gone");
    }

    @Test
    void buildHealthResponse_carriesTheDegradedComponent_andStaysHealthyAndReady() {
        var health = routes(DEGRADED).buildHealthResponse();

        assertThat(health.components()).containsExactly(new ComponentHealth("storage-maintenance",
                                                                            "DEGRADED",
                                                                            DEGRADED.or("")));
        assertThat(health.status()).isEqualTo("healthy");
        assertThat(health.ready()).isTrue();
    }

    @Test
    void buildHealthResponse_healthyMaintenance_isUp() {
        assertThat(routes(Option.none()).buildHealthResponse()
                                        .components()).extracting(ComponentHealth::status)
                                                      .containsExactly("UP");
    }

    @Test
    void buildReadinessResponse_degradedMaintenance_staysUpAndReady() {
        var readiness = routes(DEGRADED).buildReadinessResponse();

        assertThat(readiness.status()).isEqualTo("UP");
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.components()).extracting(ComponentHealth::name)
                                          .doesNotContain("storage-maintenance");
    }

    private static StatusRoutes routes(Option<String> degradation) {
        var lifecycle = NodeLifecycle.nodeLifecycle();

        lifecycle.subsystemsReady();
        lifecycle.signalReady();

        return StatusRoutes.statusRoutes(() -> stubNode(lifecycle, degradation),
                                         StatusRoutesStorageMaintenanceTest::stubAppHttpServer);
    }

    private static ManageableNode stubNode(NodeLifecycle lifecycle, Option<String> degradation) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "self" -> SELF;
                                                           case "nodeLifecycle" -> lifecycle;
                                                           case "isReady" -> true;
                                                           case "quorumLossSnapshot" -> Option.some(new QuorumLossSnapshot(1, 1, false, false));
                                                           case "storageSetups" -> Map.of();
                                                           case "dhtNode" -> Option.none();
                                                           case "connectedNodeCount" -> 0;
                                                           case "metricsCollector" -> stub(ClusterSyncCollector.class, "allMetrics", Map.of());
                                                           case "sliceStore" -> stub(SliceStore.class, "loaded", List.of());
                                                           case "storageMaintenanceDegradation" -> degradation;
                                                           default -> throw new UnsupportedOperationException("Not stubbed in test proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    private static <T> T stub(Class<T> type, String methodName, Object answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                                                new Class[]{type},
                                                (_, method, _) -> answerOnly(method, methodName, answer)));
    }

    private static Object answerOnly(Method method, String methodName, Object answer) {
        if (method.getName().equals(methodName)) {
            return answer;
        }
        throw new UnsupportedOperationException("Not stubbed in test proxy: " + method.getName());
    }

    private static AppHttpServer stubAppHttpServer() {
        return (AppHttpServer) Proxy.newProxyInstance(AppHttpServer.class.getClassLoader(),
                                                      new Class[]{AppHttpServer.class},
                                                      (_, method, _) -> switch (method.getName()) {
                                                          case "isRouteReady" -> true;
                                                          default -> throw new UnsupportedOperationException("Not stubbed in test proxy: "
                                                                                                             + method.getName());
                                                      });
    }
}
