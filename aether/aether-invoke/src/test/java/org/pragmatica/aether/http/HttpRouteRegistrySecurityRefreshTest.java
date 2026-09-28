// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1659: the registry reports, per route, the STRONGEST policy among the nodes currently serving it, and each
/// node's own entry is replaced by its next publication. Before, the first policy ever registered for a route
/// stuck for as long as any node stayed registered, so an override republished after the route first registered
/// never reached a node that did not host the route -- and that node authorized requests against the stale,
/// weaker policy (fail open).
class HttpRouteRegistrySecurityRefreshTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final Artifact ECHO = Artifact.artifact("com.example:echo:1.0.0").unwrap();

    /// The CI shape: registered before the override, republished with it.
    @Test
    void republishedEntry_withAnOverride_replacesTheRegisteredSecurity() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, "UNSPECIFIED"));
        registry.onNodeRoutesPut(put(NODE_A, "ROLE:admin"));

        assertThat(security(registry)).isEqualTo("ROLE:admin");
    }

    /// Nodes disagree while a republish is in flight or failed: the route is as strict as its strictest node,
    /// whichever registered first.
    @Test
    void nodesDisagree_theStrongestPolicyGoverns_inEitherRegistrationOrder() {
        var weakFirst = HttpRouteRegistry.httpRouteRegistry();
        var strongFirst = HttpRouteRegistry.httpRouteRegistry();

        weakFirst.onNodeRoutesPut(put(NODE_A, "UNSPECIFIED"));
        weakFirst.onNodeRoutesPut(put(NODE_B, "ROLE:admin"));
        strongFirst.onNodeRoutesPut(put(NODE_B, "ROLE:admin"));
        strongFirst.onNodeRoutesPut(put(NODE_A, "UNSPECIFIED"));

        assertThat(security(weakFirst)).isEqualTo("ROLE:admin");
        assertThat(security(strongFirst)).isEqualTo("ROLE:admin");
    }

    /// An override REMOVED: the route stays strict until every node has republished, then converges to the
    /// declared policy.
    @Test
    void overrideRemoved_routeRelaxes_onlyOnceEveryNodeHasRepublished() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_B, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_A, "UNSPECIFIED"));

        assertThat(security(registry)).as("fail closed while node B still advertises the override").isEqualTo("ROLE:admin");

        registry.onNodeRoutesPut(put(NODE_B, "UNSPECIFIED"));

        assertThat(security(registry)).as("converged to the declared policy").isEqualTo("UNSPECIFIED");
    }

    /// A departed node must not pin the route: evicted on departure, its strict entry goes with it.
    @Test
    void departedNode_doesNotPinTheRouteStrict() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_B, "UNSPECIFIED"));
        registry.evictNode(NODE_A);

        assertThat(registry.allRoutes()).singleElement()
                                        .satisfies(route -> assertThat(route.nodes()).containsExactly(NODE_B));
        assertThat(security(registry)).isEqualTo("UNSPECIFIED");
    }

    /// The same through the KV removal of the departed node's route entry.
    @Test
    void removedEntry_doesNotPinTheRouteStrict() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_B, "UNSPECIFIED"));
        registry.onNodeRoutesRemove(remove(NODE_A));

        assertThat(security(registry)).isEqualTo("UNSPECIFIED");
    }

    /// `UNSPECIFIED` inherits the global mode, which is at least public, so an explicit `PUBLIC` elsewhere must not
    /// outrank it.
    @Test
    void publicDoesNotOutrankUnspecified() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, "UNSPECIFIED"));
        registry.onNodeRoutesPut(put(NODE_B, "PUBLIC"));

        assertThat(security(registry)).isEqualTo("UNSPECIFIED");
    }

    /// The declared policy rides along, so an ingress can re-apply its own committed overrides to it.
    @Test
    void declaredPolicy_isCarriedSeparatelyFromTheEnforcedOne() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, "ROLE:admin"));

        assertThat(registry.allRoutes()).singleElement()
                                        .satisfies(route -> assertThat(route.declaredSecurity()).isEqualTo("UNSPECIFIED"));
    }

    private static String security(HttpRouteRegistry registry) {
        return registry.allRoutes()
                       .getFirst()
                       .security();
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, String security) {
        var route = RouteEntry.activeRoute("GET", "/echo/", "echo", security, "UNSPECIFIED");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, ECHO), value), Option.none());
    }

    private static ValueRemove<NodeRoutesKey, NodeRoutesValue> remove(NodeId node) {
        return new ValueRemove<>(new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(node, ECHO)), Option.none());
    }
}
