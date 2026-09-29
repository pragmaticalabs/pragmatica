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
    private static final Artifact ECHO_V2 = Artifact.artifact("com.example:echo:2.0.0").unwrap();

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

    /// v1670 F4: "strongest" is a TOTAL order. Equal strengths (`ROLE:a` vs `ROLE:b`, `API_KEY` vs `BEARER_TOKEN`)
    /// used to resolve in `Map.copyOf` iteration order, which is seeded per JVM and varies with the node ids -- two
    /// ingresses could pick different credential types for one route. Across 64 node-id pairs, in both put orders,
    /// every registry must resolve to the SAME policy.
    @Test
    void equallyStrongPolicies_resolveToTheSamePolicy_whateverTheNodeIdsOrPutOrder() {
        for (var pair : List.of(List.of("ROLE:a", "ROLE:b"), List.of("API_KEY", "BEARER_TOKEN"))) {
            var resolved = new java.util.HashSet<String>();

            for (var i = 0; i < 64; i++) {
                var first = NodeId.nodeId("tie-a-" + i).unwrap();
                var second = NodeId.nodeId("tie-b-" + i).unwrap();
                var forward = HttpRouteRegistry.httpRouteRegistry();
                var backward = HttpRouteRegistry.httpRouteRegistry();

                forward.onNodeRoutesPut(put(first, pair.get(0)));
                forward.onNodeRoutesPut(put(second, pair.get(1)));
                backward.onNodeRoutesPut(put(second, pair.get(1)));
                backward.onNodeRoutesPut(put(first, pair.get(0)));
                resolved.add(security(forward));
                resolved.add(security(backward));
            }

            assertThat(resolved).as("one resolution for %s across 64 node-id pairs and both put orders", pair).hasSize(1);
        }
    }

    /// v1670 F3: one node serving the same route from TWO artifacts (two versions during a rollout) keeps both
    /// entries. Keyed by node alone, the later put (a weaker PUBLIC) overwrote the stricter one the node still served.
    @Test
    void oneNodeTwoArtifacts_bothEntriesCount_theStrongerGoverns() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, ECHO_V2, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_A, ECHO, "PUBLIC"));

        assertThat(security(registry)).isEqualTo("ROLE:admin");
    }

    /// v1670 F3: removing ONE artifact's key drops only that artifact's entries -- the node keeps serving the route
    /// from its other artifact. Keyed by node alone, the removal dropped the node from every route.
    @Test
    void oneArtifactRemoved_theNodeKeepsServingTheRouteFromTheOther() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, ECHO_V2, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_A, ECHO, "PUBLIC"));
        registry.onNodeRoutesRemove(remove(NODE_A, ECHO));

        assertThat(registry.allRoutes()).singleElement()
                                        .satisfies(route -> {
                                            assertThat(route.nodes()).containsExactly(NODE_A);
                                            assertThat(route.security()).isEqualTo("ROLE:admin");
                                        });
    }

    /// CONTROL for the two above: a DEPARTURE still drops every entry of the node, whatever artifact published it.
    @Test
    void departedNode_dropsTheEntriesOfAllItsArtifacts() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE_A, ECHO_V2, "ROLE:admin"));
        registry.onNodeRoutesPut(put(NODE_A, ECHO, "PUBLIC"));
        registry.evictNode(NODE_A);

        assertThat(registry.allRoutes()).isEmpty();
    }

    private static String security(HttpRouteRegistry registry) {
        return registry.allRoutes()
                       .getFirst()
                       .security();
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, String security) {
        return put(node, ECHO, security);
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, Artifact artifact, String security) {
        var route = RouteEntry.activeRoute("GET", "/echo/", "echo", security, "UNSPECIFIED");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.ZERO);

        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, artifact), value), Option.none());
    }

    private static ValueRemove<NodeRoutesKey, NodeRoutesValue> remove(NodeId node) {
        return remove(node, ECHO);
    }

    private static ValueRemove<NodeRoutesKey, NodeRoutesValue> remove(NodeId node, Artifact artifact) {
        return new ValueRemove<>(new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(node, artifact)), Option.none());
    }
}
