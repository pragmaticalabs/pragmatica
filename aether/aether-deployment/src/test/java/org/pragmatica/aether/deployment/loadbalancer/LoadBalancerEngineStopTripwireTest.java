// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.loadbalancer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManagerTest.RecordingLoadBalancerProvider;
import org.pragmatica.aether.deployment.loadbalancer.LoadBalancerManagerTest.RecordingTopologyManager;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// C9 tripwire, cloud call kind (KVStore dispatcher design §3.5.1 rows 53/54, D1-react entry criterion).
///
/// Pins TODAY's behaviour: a NodeRoutes put delivered after an engine-only stop calls the cloud load balancer
/// provider inline from the absorb path. Nothing gates the call: the engine stop has no input into the manager, and
/// that absence is exactly what this test pins (the stop is not simulated: no seam exists to simulate it through).
///
/// When the D1-react engine-alive gate lands this test goes red. Then delete it and enable the (ab) cloud-call arm.
class LoadBalancerEngineStopTripwireTest {
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();
    private static final String ENABLE = "D1-react engine-alive gate landed: delete LoadBalancerEngineStopTripwireTest "
                                         + "and enable the exit (ab) cloud-call arm";

    @Test
    void tripwire_nodeRoutesPutAfterEngineStop_callsProviderInline() {
        var node = NodeId.randomNodeId();
        var provider = new RecordingLoadBalancerProvider();
        var topology = new RecordingTopologyManager();
        topology.register(node, "10.0.0.1", 8080);
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        var manager = LoadBalancerManager.loadBalancerManager(NodeId.randomNodeId(),
                                                              new KVStore<>(router, LoadBalancerManagerTest.noopSerializer(), null),
                                                              topology,
                                                              provider,
                                                              8080);
        manager.activate().await();
        provider.clear();

        manager.onNodeRoutesPut(put(node));

        assertThat(provider.routeChanges).as(ENABLE).hasSize(1);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node) {
        var value = NodeRoutesValue.nodeRoutesValue(List.of(RouteEntry.activeRoute("POST", "/api/orders/", "create")));

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, ARTIFACT), value), Option.none());
    }
}
