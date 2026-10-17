// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1314: a `NodeRoutesKey` names one (node, artifact) contribution. Its removal takes away that contribution only,
/// and its put REPLACES that contribution: before, a put only added, so a route a republication dropped stayed
/// registered on the node for as long as the registry lived.
class HttpRouteRegistryContributionTest {
    private static final NodeId NODE = NodeId.nodeId("node-a").unwrap();
    private static final NodeId OTHER_NODE = NodeId.nodeId("node-b").unwrap();
    private static final Artifact ORDERS = Artifact.artifact("com.example:orders:1.0.0").unwrap();
    private static final Artifact USERS = Artifact.artifact("com.example:users:1.0.0").unwrap();

    @Test
    void onNodeRoutesRemove_oneArtifactOfTwoOnANode_theOtherArtifactStaysRoutable() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/orders/")));
        registry.onNodeRoutesPut(put(NODE, USERS, route("GET", "/users/")));
        registry.onNodeRoutesRemove(remove(NODE, ORDERS));

        assertThat(registry.findRoute("GET", "/orders/").isEmpty()).isTrue();
        assertThat(nodes(registry, "GET", "/users/")).containsExactly(NODE);
    }

    @Test
    void onNodeRoutesPut_replacementValue_subtractsOmittedRoutes_andAddsNewOnes() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/orders/"), route("POST", "/orders/")));
        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/orders/"), route("GET", "/invoices/")));

        assertThat(registry.findRoute("POST", "/orders/").isEmpty())
                .as("a route the replacement omits must be subtracted")
                .isTrue();
        assertThat(nodes(registry, "GET", "/orders/")).containsExactly(NODE);
        assertThat(nodes(registry, "GET", "/invoices/")).containsExactly(NODE);
    }

    /// The subtraction is scoped to the publisher: another node's entry on the omitted route survives.
    @Test
    void onNodeRoutesPut_replacementValue_keepsOtherNodesOnTheOmittedRoute() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE, ORDERS, route("POST", "/orders/")));
        registry.onNodeRoutesPut(put(OTHER_NODE, ORDERS, route("POST", "/orders/")));
        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/orders/")));

        assertThat(nodes(registry, "POST", "/orders/")).containsExactly(OTHER_NODE);
    }

    @Test
    void sharedRouteNodePair_staysUntilTheLastContributionIsRemoved() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/shared/")));
        registry.onNodeRoutesPut(put(NODE, USERS, route("GET", "/shared/")));

        registry.onNodeRoutesRemove(remove(NODE, ORDERS));
        assertThat(nodes(registry, "GET", "/shared/")).containsExactly(NODE);

        registry.onNodeRoutesRemove(remove(NODE, USERS));
        assertThat(registry.findRoute("GET", "/shared/").isEmpty()).isTrue();
    }

    /// A replacement by one artifact drops only that artifact's entry on a shared pair.
    @Test
    void sharedRouteNodePair_replacementByOneArtifact_keepsTheOtherArtifactsEntry() {
        var registry = HttpRouteRegistry.httpRouteRegistry();

        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/shared/")));
        registry.onNodeRoutesPut(put(NODE, USERS, route("GET", "/shared/")));
        registry.onNodeRoutesPut(put(NODE, ORDERS, route("GET", "/orders/")));

        assertThat(nodes(registry, "GET", "/shared/")).containsExactly(NODE);
        registry.onNodeRoutesRemove(remove(NODE, USERS));
        assertThat(registry.findRoute("GET", "/shared/").isEmpty()).isTrue();
    }

    @Test
    void repeatedPutsAndRemoves_areIdempotent() {
        var once = HttpRouteRegistry.httpRouteRegistry();
        var repeated = HttpRouteRegistry.httpRouteRegistry();
        var first = put(NODE, ORDERS, route("GET", "/orders/"), route("POST", "/orders/"));
        var second = put(NODE, USERS, route("GET", "/users/"));

        once.onNodeRoutesPut(first);
        once.onNodeRoutesPut(second);
        once.onNodeRoutesRemove(remove(NODE, USERS));
        repeated.onNodeRoutesPut(first);
        repeated.onNodeRoutesPut(first);
        repeated.onNodeRoutesPut(second);
        repeated.onNodeRoutesPut(second);
        repeated.onNodeRoutesRemove(remove(NODE, USERS));
        repeated.onNodeRoutesRemove(remove(NODE, USERS));

        assertThat(repeated.allRoutes()).containsExactlyInAnyOrderElementsOf(once.allRoutes());
        assertThat(nodes(repeated, "POST", "/orders/")).containsExactly(NODE);
        assertThat(repeated.findRoute("GET", "/users/").isEmpty()).isTrue();
    }

    private static List<NodeId> nodes(HttpRouteRegistry registry, String method, String path) {
        return registry.findRoute(method, path)
                       .map(route -> List.copyOf(route.nodes()))
                       .or(List.of());
    }

    private static RouteEntry route(String method, String path) {
        return RouteEntry.activeRoute(method, path, "handle");
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> put(NodeId node, Artifact artifact, RouteEntry... routes) {
        return new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, artifact),
                                                  NodeRoutesValue.nodeRoutesValue(List.of(routes))),
                              Option.none());
    }

    private static ValueRemove<NodeRoutesKey, NodeRoutesValue> remove(NodeId node, Artifact artifact) {
        return new ValueRemove<>(new KVCommand.Remove<>(NodeRoutesKey.nodeRoutesKey(node, artifact)), Option.none());
    }
}
