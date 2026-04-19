// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterTopologyStatusResponse;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.generation.NodeSnapshotCache;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;


class ClusterTopologyRoutesCoreCountTest {
    private static final NodeId NODE_1 = new NodeId("node-1");
    private static final NodeId NODE_2 = new NodeId("node-2");
    private static final NodeId NODE_3 = new NodeId("node-3");

    private ClusterTopologyStatusResponse invoke(AetherNode node) {
        var routes = ClusterTopologyRoutes.clusterTopologyRoutes(() -> node);
        var routeList = routes.routes().toList();
        var topologyRoute = routeList.stream()
                                     .filter(route -> route.path().startsWith("/api/cluster/topology"))
                                     .findFirst()
                                     .orElseThrow();
        @SuppressWarnings("unchecked")
        var handler = (org.pragmatica.http.routing.Handler<ClusterTopologyStatusResponse>) topologyRoute.handler();
        var result = handler.handle(null).await();
        org.assertj.core.api.Assertions.assertThat(result.isSuccess()).isTrue();
        return result.unwrap();
    }

    @Nested
    class SnapshotBacked {
        @Test
        void buildTopologyStatus_withSnapshot_coreCountFromSnapshot() {
            var member1 = CoreMember.coreMember(NODE_1,
                                                "h1",
                                                6000,
                                                NodeLifecycleState.ON_DUTY,
                                                HealthHint.HEALTHY,
                                                Epoch.ZERO,
                                                Epoch.epoch(5L, 10L));
            var member2 = CoreMember.coreMember(NODE_2,
                                                "h2",
                                                6000,
                                                NodeLifecycleState.ON_DUTY,
                                                HealthHint.HEALTHY,
                                                Epoch.ZERO,
                                                Epoch.epoch(5L, 10L));
            var member3 = CoreMember.coreMember(NODE_3,
                                                "h3",
                                                6000,
                                                NodeLifecycleState.DRAINING,
                                                HealthHint.HEALTHY,
                                                Epoch.ZERO,
                                                Epoch.epoch(5L, 10L));
            var snapshot = new ClusterGenerationSnapshot(Epoch.epoch(5L, 10L),
                                                         5L,
                                                         HlcTimestamp.ZERO,
                                                         GenerationReason.PERIODIC_REFRESH,
                                                         3,
                                                         Map.of(NODE_1, member1, NODE_2, member2, NODE_3, member3),
                                                         Map.of(),
                                                         Map.of(),
                                                         ClusterMode.CORE_ONLY,
                                                         ClusterQuiescence.QUIESCED,
                                                         "");
            var node = nodeProxy(topologyManagerProxy(99), Option.some(snapshot));

            var response = invoke(node);

            // Only 2 members are ON_DUTY+HEALTHY — DRAINING member is excluded.
            org.assertj.core.api.Assertions.assertThat(response.coreCount()).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(response.epoch().isPresent()).isTrue();
            org.assertj.core.api.Assertions.assertThat(response.epoch().unwrap()).isEqualTo("5:10");
        }
    }

    @Nested
    class LegacyBacked {
        @Test
        void buildTopologyStatus_noSnapshot_coreCountFromTopologyManager() {
            var node = nodeProxy(topologyManagerProxy(4), Option.none());

            var response = invoke(node);

            org.assertj.core.api.Assertions.assertThat(response.coreCount()).isEqualTo(4);
            org.assertj.core.api.Assertions.assertThat(response.epoch().isEmpty()).isTrue();
        }
    }

    private static AetherNode nodeProxy(TopologyManager topologyManager, Option<ClusterGenerationSnapshot> snapshot) {
        var cache = cacheProxy(snapshot);
        var topologyConfig = topologyConfig();
        return (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                   new Class[]{AetherNode.class},
                                                   (_, method, _) -> switch (method.getName()) {
                                                       case "topologyManager" -> topologyManager;
                                                       case "topologyConfig" -> topologyConfig;
                                                       case "connectedPeerIds" -> Set.of(NODE_1, NODE_2);
                                                       case "nodeSnapshotCache" -> cache;
                                                       default -> throw new UnsupportedOperationException("Not in test proxy: " + method.getName());
                                                   });
    }

    private static NodeSnapshotCache cacheProxy(Option<ClusterGenerationSnapshot> snapshot) {
        return (NodeSnapshotCache) Proxy.newProxyInstance(NodeSnapshotCache.class.getClassLoader(),
                                                          new Class[]{NodeSnapshotCache.class},
                                                          (_, method, _) -> switch (method.getName()) {
                                                              case "current" -> snapshot;
                                                              default -> throw new UnsupportedOperationException("Not in test proxy: " + method.getName());
                                                          });
    }

    private static TopologyManager topologyManagerProxy(int healthyActiveCount) {
        return (TopologyManager) Proxy.newProxyInstance(TopologyManager.class.getClassLoader(),
                                                        new Class[]{TopologyManager.class},
                                                        (_, method, _) -> switch (method.getName()) {
                                                            case "healthyActiveNodeCount" -> healthyActiveCount;
                                                            case "topology" -> List.of();
                                                            case "isPassive" -> false;
                                                            case "getState" -> Option.none();
                                                            case "get" -> Option.none();
                                                            default -> throw new UnsupportedOperationException("Not in test proxy: " + method.getName());
                                                        });
    }

    private static TopologyConfig topologyConfig() {
        return new TopologyConfig(NODE_1,
                                  3,
                                  TimeSpan.timeSpan(5).seconds(),
                                  TimeSpan.timeSpan(500).millis(),
                                  List.of());
    }
}
