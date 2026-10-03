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
                case "leader" -> Option.none();
                default -> throw new UnsupportedOperationException(method.getName());
            });

        return NodeLifecycleRoutes.nodeLifecycleRoutes(() -> node, _ -> {}, Set::of);
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
    void getNodeLifecycle_authoritativeViewWithoutTheNode_is404() {
        assertThat(statusOf(routes(true, Map.of()))).isEqualTo(HttpStatus.NOT_FOUND.code());
    }

    @Test
    void getNodeLifecycle_authoritativeViewHoldingTheNode_isItsState() {
        var result = routes(true, Map.of(DRAINING_NODE, NodeReportedState.DRAINING))
            .getNodeLifecycleForTest(DRAINING_NODE.id()).await();

        String state = result.fold(_ -> "failed", entry -> entry.state());

        assertThat(state).isEqualTo("DRAINING");
    }
}
