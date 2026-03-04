package org.pragmatica.aether.slice.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Cluster-wide topology graph aggregated from per-slice topologies.
///
/// Nodes represent endpoints, slices, topics, and resources.
/// Edges represent data flow between them.
public record TopologyGraph(List<TopologyNode> nodes, List<TopologyEdge> edges) {
    public TopologyGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record TopologyNode(String id, NodeType type, String label, String sliceArtifact) {}

    public record TopologyEdge(String from, String to, EdgeStyle style, String topicConfig) {}

    public enum NodeType {
        ENDPOINT,
        SLICE,
        TOPIC,
        TOPIC_PUB,
        TOPIC_SUB,
        RESOURCE
    }

    public enum EdgeStyle {
        SOLID,
        DOTTED
    }

    /// Build a cluster-wide topology graph from per-slice topologies.
    @SuppressWarnings("JBCT-PAT-01")
    public static TopologyGraph build(List<SliceTopology> slices) {
        var nodeMap = new LinkedHashMap<String, TopologyNode>();
        var edgeList = new ArrayList<TopologyEdge>();
        for (var slice : slices) {
            var sliceId = "slice:" + slice.artifact();
            nodeMap.putIfAbsent(sliceId,
                                new TopologyNode(sliceId, NodeType.SLICE, slice.sliceName(), slice.artifact()));
            addRouteNodes(slice, sliceId, nodeMap, edgeList);
            addDependencyEdges(slice, sliceId, nodeMap, edgeList);
            addResourceNodes(slice, sliceId, nodeMap, edgeList);
            addPublishNodes(slice, sliceId, nodeMap, edgeList);
            addSubscribeNodes(slice, sliceId, nodeMap, edgeList);
        }
        addPubSubEdges(nodeMap, edgeList);
        return new TopologyGraph(new ArrayList<>(nodeMap.values()), edgeList);
    }

    private static void addRouteNodes(SliceTopology slice,
                                      String sliceId,
                                      Map<String, TopologyNode> nodeMap,
                                      List<TopologyEdge> edgeList) {
        for (var route : slice.routes()) {
            var endpointId = "endpoint:" + route.method() + ":" + route.path();
            var label = route.method() + " " + route.path();
            nodeMap.putIfAbsent(endpointId, new TopologyNode(endpointId, NodeType.ENDPOINT, label, slice.artifact()));
            edgeList.add(new TopologyEdge(endpointId, sliceId, EdgeStyle.SOLID, ""));
        }
    }

    private static void addDependencyEdges(SliceTopology slice,
                                           String sliceId,
                                           Map<String, TopologyNode> nodeMap,
                                           List<TopologyEdge> edgeList) {
        for (var dep : slice.dependencies()) {
            if (!dep.artifact()
                    .isEmpty()) {
                var depSliceId = "slice:" + dep.artifact();
                nodeMap.putIfAbsent(depSliceId,
                                    new TopologyNode(depSliceId, NodeType.SLICE, dep.interfaceName(), dep.artifact()));
                edgeList.add(new TopologyEdge(sliceId, depSliceId, EdgeStyle.SOLID, ""));
            }
        }
    }

    private static void addResourceNodes(SliceTopology slice,
                                         String sliceId,
                                         Map<String, TopologyNode> nodeMap,
                                         List<TopologyEdge> edgeList) {
        for (var resource : slice.resources()) {
            var resourceId = "resource:" + slice.artifact() + ":" + resource.type() + ":" + resource.config();
            var label = resource.type() + " (" + resource.config() + ")";
            nodeMap.putIfAbsent(resourceId, new TopologyNode(resourceId, NodeType.RESOURCE, label, slice.artifact()));
            edgeList.add(new TopologyEdge(sliceId, resourceId, EdgeStyle.SOLID, ""));
        }
    }

    private static void addPublishNodes(SliceTopology slice,
                                        String sliceId,
                                        Map<String, TopologyNode> nodeMap,
                                        List<TopologyEdge> edgeList) {
        for (var pub : slice.publishes()) {
            var topicId = "topic-pub:" + slice.artifact() + ":" + pub.config();
            nodeMap.putIfAbsent(topicId,
                                new TopologyNode(topicId, NodeType.TOPIC_PUB, pub.config(), slice.artifact()));
            edgeList.add(new TopologyEdge(sliceId, topicId, EdgeStyle.SOLID, ""));
        }
    }

    private static void addSubscribeNodes(SliceTopology slice,
                                          String sliceId,
                                          Map<String, TopologyNode> nodeMap,
                                          List<TopologyEdge> edgeList) {
        for (var sub : slice.subscribes()) {
            var topicId = "topic-sub:" + slice.artifact() + ":" + sub.config();
            nodeMap.putIfAbsent(topicId,
                                new TopologyNode(topicId, NodeType.TOPIC_SUB, sub.config(), slice.artifact()));
            edgeList.add(new TopologyEdge(topicId, sliceId, EdgeStyle.SOLID, ""));
        }
    }

    private static void addPubSubEdges(Map<String, TopologyNode> nodeMap,
                                       List<TopologyEdge> edgeList) {
        var pubsByConfig = new LinkedHashMap<String, List<String>>();
        var subsByConfig = new LinkedHashMap<String, List<String>>();
        for (var id : nodeMap.keySet()) {
            if (id.startsWith("topic-pub:")) {
                var config = id.substring(id.indexOf(':', "topic-pub:".length()) + 1);
                pubsByConfig.computeIfAbsent(config,
                                             _ -> new ArrayList<>())
                            .add(id);
            } else if (id.startsWith("topic-sub:")) {
                var config = id.substring(id.indexOf(':', "topic-sub:".length()) + 1);
                subsByConfig.computeIfAbsent(config,
                                             _ -> new ArrayList<>())
                            .add(id);
            }
        }
        for (var entry : pubsByConfig.entrySet()) {
            var config = entry.getKey();
            var subs = subsByConfig.get(config);
            if (subs != null) {
                for (var pubId : entry.getValue()) {
                    for (var subId : subs) {
                        edgeList.add(new TopologyEdge(pubId, subId, EdgeStyle.DOTTED, config));
                    }
                }
            }
        }
    }
}
