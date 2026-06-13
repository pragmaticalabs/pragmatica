// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topology;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.topology.TopologyGraph.EdgeStyle;
import org.pragmatica.aether.slice.topology.TopologyGraph.NodeType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Topology node identity and cross-slice pub/sub matching key on the resolved canonical
/// `namespace:topic:version` address, not the raw declared config. This lets a bare-declared
/// publisher connect to an explicitly-namespaced subscriber once both resolve to the same address.
class TopologyGraphTest {

    private static SliceTopology publisher(String artifact, String config, String address) {
        return new SliceTopology("pub", artifact,
                                 List.of(), List.of(), List.of(),
                                 List.of(new SliceTopology.TopicPub(config, address, "Event")),
                                 List.of());
    }

    private static SliceTopology subscriber(String artifact, String config, String address) {
        return new SliceTopology("sub", artifact,
                                 List.of(), List.of(), List.of(),
                                 List.of(),
                                 List.of(new SliceTopology.TopicSub(config, address, "onEvent", "Event")));
    }

    @Test
    void topicNodeIdAndLabelUseResolvedNamespacedAddress() {
        var graph = TopologyGraph.build(List.of(
            publisher("org.example:url-shortener:1.0.0", "click-events",
                      "org.example.url-shortener:click-events:1.0.0")));

        var pubNode = graph.nodes().stream()
                           .filter(n -> n.type() == NodeType.TOPIC_PUB)
                           .findFirst()
                           .orElseThrow();

        assertThat(pubNode.id())
            .isEqualTo("topic-pub:org.example:url-shortener:1.0.0:org.example.url-shortener:click-events:1.0.0");
        assertThat(pubNode.label()).isEqualTo("org.example.url-shortener:click-events:1.0.0");
    }

    @Test
    void publisherAndSubscriberConnectWhenResolvedAddressesMatch() {
        var address = "org.example.shop:order-events:1.0.0";
        var graph = TopologyGraph.build(List.of(
            // one declares bare, the other explicit — both resolve to the same address upstream
            publisher("org.example:shop:1.0.0", "order-events", address),
            subscriber("org.example:analytics:1.0.0", address, address)));

        var dottedEdges = graph.edges().stream()
                              .filter(e -> e.style() == EdgeStyle.DOTTED)
                              .toList();

        assertThat(dottedEdges).hasSize(1);
        assertThat(dottedEdges.getFirst().topicConfig()).isEqualTo(address);
    }

    @Test
    void differentResolvedAddressesDoNotConnect() {
        var graph = TopologyGraph.build(List.of(
            publisher("org.example:shop:1.0.0", "order-events",
                      "org.example.shop:order-events:1.0.0"),
            subscriber("org.example:analytics:1.0.0", "order-events",
                       "org.example.analytics:order-events:1.0.0")));

        assertThat(graph.edges().stream().filter(e -> e.style() == EdgeStyle.DOTTED)).isEmpty();
    }
}
