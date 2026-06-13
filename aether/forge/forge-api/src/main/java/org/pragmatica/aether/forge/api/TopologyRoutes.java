// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.forge.api.ForgeApiResponses.TopologyEdgeInfo;
import org.pragmatica.aether.forge.api.ForgeApiResponses.TopologyNodeInfo;
import org.pragmatica.aether.forge.api.ForgeApiResponses.TopologyResponse;
import org.pragmatica.aether.slice.topology.TopologyGraph;
import org.pragmatica.aether.slice.topology.TopologyParser;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.HashSet;
import java.util.stream.Stream;


public final class TopologyRoutes implements RouteSource {
    private final EmberCluster cluster;

    private TopologyRoutes(EmberCluster cluster) {
        this.cluster = cluster;
    }

    public static TopologyRoutes topologyRoutes(EmberCluster cluster) {
        return new TopologyRoutes(cluster);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<TopologyResponse> get("/api/slices/topology").toJson(this::buildTopology));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private TopologyResponse buildTopology() {
        var seen = new HashSet<String>();
        var sliceTopologies = cluster.allNodes().stream().flatMap(node -> node.sliceStore()
                                                                              .loaded()
                                                                              .stream()).filter(ls -> seen.add(ls.artifact()
                                                                                                                 .asString())).flatMap(ls -> TopologyParser.parse(ls.slice(),
                                                                                                                                                                  ls.artifact()
                                                                                                                                                                    .asString())
                                                                                                                                                           .stream()).toList();
        var graph = TopologyGraph.build(sliceTopologies);
        var nodes = graph.nodes().stream().map(n -> new TopologyNodeInfo(n.id(),
                                                                         n.type().name(),
                                                                         n.label(),
                                                                         n.sliceArtifact())).toList();
        var edges = graph.edges().stream().map(e -> new TopologyEdgeInfo(e.from(),
                                                                         e.to(),
                                                                         e.style().name(),
                                                                         e.topicConfig())).toList();

        return new TopologyResponse(nodes, edges);
    }
}
