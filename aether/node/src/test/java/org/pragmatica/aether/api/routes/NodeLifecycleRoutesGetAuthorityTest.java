// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1868 — the CLI's drain wait reads the per-node lifecycle GET's 404 as "the node has gone". That is only
/// true if the answering node holds an authoritative (leader) or fresh cached (follower) view. LIST already
/// refuses with 503 otherwise; the per-node GET did not, so a follower with no fresh view answered 404 for
/// EVERY node, still-draining ones included.
class NodeLifecycleRoutesGetAuthorityTest {
    private static final NodeId DRAINING_NODE = new NodeId("core-2");

    private static NodeLifecycleRoutes routes(boolean authoritative, Map<NodeId, NodeReportedState> view) {
        return routes(authoritative, view, null);
    }

    private static NodeLifecycleRoutes routes(boolean authoritative, Map<NodeId, NodeReportedState> view, String advertisedVersion) {
        var collector = (ClusterSyncCollector) Proxy.newProxyInstance(
            ClusterSyncCollector.class.getClassLoader(),
            new Class[]{ClusterSyncCollector.class},
            (_, method, _) -> switch (method.getName()) {
                case "hasAuthoritativeReadiness" -> authoritative;
                case "reportedStates" -> view;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var node = (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _) -> switch (method.getName()) {
                case "metricsCollector" -> collector;
                case "membershipFsm" -> org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.membershipFsm();
                case "leader" -> Option.none();
                case "topologyManager" -> topologyWithVersion(DRAINING_NODE, advertisedVersion);
                default -> throw new UnsupportedOperationException(method.getName());
            });

        return NodeLifecycleRoutes.nodeLifecycleRoutes(() -> node, _ -> {}, Set::of);
    }

    /// #1543 part C: the lifecycle entry reads the advertised `version` label from the topology view.
    private static org.pragmatica.consensus.topology.TopologyManager topologyWithVersion(NodeId node, String version) {
        return (org.pragmatica.consensus.topology.TopologyManager) Proxy.newProxyInstance(
            org.pragmatica.consensus.topology.TopologyManager.class.getClassLoader(),
            new Class[]{org.pragmatica.consensus.topology.TopologyManager.class},
            (_, method, args) -> {
                if (!method.getName().equals("get")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                return args[0].equals(node) && version != null
                       ? Option.some(org.pragmatica.consensus.net.NodeInfo.nodeInfo(node,
                                                                                    org.pragmatica.net.tcp.NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap(),
                                                                                    Map.of(org.pragmatica.consensus.net.NodeInfo.LABEL_VERSION, version)))
                       : Option.<org.pragmatica.consensus.net.NodeInfo> none();
            });
    }

    private static int statusOf(NodeLifecycleRoutes routes) {
        var result = routes.getNodeLifecycleForTest(DRAINING_NODE.id()).await();

        return result.fold(cause -> ((HttpError) cause).status().code(), _ -> 200);
    }

    @Test
    void getNodeLifecycle_followerWithoutAFreshView_is503NotA404() {
        assertThat(statusOf(routes(false, Map.of()))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.code());
    }

    @Test
    void getNodeLifecycle_authoritativeViewWithoutAnUnknownNode_is404() {
        assertThat(statusOf(routes(true, Map.of()))).isEqualTo(HttpStatus.NOT_FOUND.code());
    }

    @Test
    void getNodeLifecycle_authoritativeViewHoldingTheNode_isItsState() {
        var result = routes(true, Map.of(DRAINING_NODE, NodeReportedState.DRAINING))
            .getNodeLifecycleForTest(DRAINING_NODE.id()).await();

        String state = result.fold(_ -> "failed", entry -> entry.state());

        assertThat(state).isEqualTo("DRAINING");
    }

    @Test
    void getNodeLifecycle_surfacesTheNodesAdvertisedVersion() {
        var entry = routes(true, Map.of(DRAINING_NODE, NodeReportedState.READY), "1.1.0")
            .getNodeLifecycleForTest(DRAINING_NODE.id()).await().unwrap();

        assertThat(entry.version()).isEqualTo("1.1.0");
    }

    @Test
    void getNodeLifecycle_nodeWithoutAVersionLabel_showsEmptyVersion_notAnError() {
        var entry = routes(true, Map.of(DRAINING_NODE, NodeReportedState.READY), null)
            .getNodeLifecycleForTest(DRAINING_NODE.id()).await().unwrap();

        assertThat(entry.version()).isEmpty();
    }
}
