// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationResponse;
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
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterGenerationRoutesTest {
    private static final NodeId NODE_1 = new NodeId("node-1");

    private ClusterGenerationResponse fetchResponse(Option<ClusterGenerationSnapshot> snapshot) {
        var cacheRef = new AtomicReference<>(snapshot);
        var cache = cacheProxy(cacheRef::get);
        var node = nodeProxy(cache);
        var routes = ClusterGenerationRoutes.clusterGenerationRoutes(() -> node);
        var routeList = routes.routes().toList();
        assertThat(routeList).hasSize(1);
        @SuppressWarnings("unchecked")
        var handler = (org.pragmatica.http.routing.Handler<ClusterGenerationResponse>) routeList.getFirst().handler();
        var result = handler.handle(null).await();
        assertThat(result.isSuccess()).as("handler result").isTrue();
        return result.unwrap();
    }

    @Nested
    class SnapshotPresent {
        @Test
        void buildGenerationResponse_snapshotPresent_returnsAllFields() {
            var member = CoreMember.coreMember(NODE_1,
                                               "host-1",
                                               6000,
                                               NodeLifecycleState.ON_DUTY,
                                               HealthHint.HEALTHY,
                                               Epoch.epoch(7L, 0L),
                                               Epoch.epoch(7L, 142L));
            var snapshot = new ClusterGenerationSnapshot(Epoch.epoch(7L, 142L),
                                                         HlcTimestamp.ZERO,
                                                         GenerationReason.PERIODIC_REFRESH,
                                                         5,
                                                         Map.of(NODE_1, member),
                                                         Map.of(),
                                                         Map.of(),
                                                         ClusterMode.CORE_ONLY,
                                                         ClusterQuiescence.QUIESCED,
                                                         "");

            var response = fetchResponse(Option.some(snapshot));

            assertThat(response.epoch().isPresent()).isTrue();
            assertThat(response.epoch().unwrap().rabiaTerm()).isEqualTo(7L);
            assertThat(response.epoch().unwrap().localCounter()).isEqualTo(142L);
            assertThat(response.rabiaTerm()).isEqualTo(7L);
            assertThat(response.mode()).isEqualTo("CORE_ONLY");
            assertThat(response.quiescence()).isEqualTo("QUIESCED");
            assertThat(response.core().desiredSize()).isEqualTo(5);
            assertThat(response.core().members()).hasSize(1);
            var memberJson = response.core().members().getFirst();
            assertThat(memberJson.nodeId()).isEqualTo("node-1");
            assertThat(memberJson.lifecycle()).isEqualTo("ON_DUTY");
            assertThat(memberJson.healthHint()).isEqualTo("HEALTHY");
            assertThat(memberJson.port()).isEqualTo(6000);
            assertThat(response.communities()).isEmpty();
            assertThat(response.partitions()).isEmpty();
        }
    }

    @Nested
    class SnapshotAbsent {
        @Test
        void buildGenerationResponse_snapshotAbsent_returnsEmptySkeleton() {
            var response = fetchResponse(Option.none());

            assertThat(response.epoch().isEmpty()).isTrue();
            assertThat(response.rabiaTerm()).isEqualTo(0L);
            assertThat(response.mode()).isEqualTo("unknown");
            assertThat(response.quiescence()).isEqualTo("UNKNOWN");
            assertThat(response.quiescenceDetail()).isEmpty();
            assertThat(response.core().desiredSize()).isZero();
            assertThat(response.core().members()).isEmpty();
            assertThat(response.communities()).isEmpty();
            assertThat(response.partitions()).isEmpty();
        }
    }

    private static NodeSnapshotCache cacheProxy(java.util.function.Supplier<Option<ClusterGenerationSnapshot>> supplier) {
        return (NodeSnapshotCache) Proxy.newProxyInstance(NodeSnapshotCache.class.getClassLoader(),
                                                          new Class[]{NodeSnapshotCache.class},
                                                          (_, method, _) -> {
                                                              if ("current".equals(method.getName())) {
                                                                  return supplier.get();
                                                              }
                                                              throw new UnsupportedOperationException("Not in test proxy: " + method.getName());
                                                          });
    }

    private static AetherNode nodeProxy(NodeSnapshotCache cache) {
        return (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                   new Class[]{AetherNode.class},
                                                   (_, method, _) -> {
                                                       if ("nodeSnapshotCache".equals(method.getName())) {
                                                           return cache;
                                                       }
                                                       if ("currentGenerationSnapshot".equals(method.getName())) {
                                                           return cache.current();
                                                       }
                                                       throw new UnsupportedOperationException("Not in test proxy: " + method.getName());
                                                   });
    }
}
