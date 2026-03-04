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

    public record TopologyNode(String id, NodeType type, String label) {}

    public record TopologyEdge(String from, String to, EdgeStyle style) {}

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
            nodeMap.putIfAbsent(sliceId, new TopologyNode(sliceId, NodeType.SLICE, slice.sliceName()));
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
            nodeMap.putIfAbsent(endpointId, new TopologyNode(endpointId, NodeType.ENDPOINT, label));
            edgeList.add(new TopologyEdge(endpointId, sliceId, EdgeStyle.SOLID));
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
                nodeMap.putIfAbsent(depSliceId, new TopologyNode(depSliceId, NodeType.SLICE, dep.interfaceName()));
                edgeList.add(new TopologyEdge(sliceId, depSliceId, EdgeStyle.SOLID));
            }
        }
    }

    private static void addResourceNodes(SliceTopology slice,
                                         String sliceId,
                                         Map<String, TopologyNode> nodeMap,
                                         List<TopologyEdge> edgeList) {
        for (var resource : slice.resources()) {
            var resourceId = "resource:" + resource.type() + ":" + resource.config();
            var label = resource.type() + " (" + resource.config() + ")";
            nodeMap.putIfAbsent(resourceId, new TopologyNode(resourceId, NodeType.RESOURCE, label));
            edgeList.add(new TopologyEdge(sliceId, resourceId, EdgeStyle.SOLID));
        }
    }

    private static void addPublishNodes(SliceTopology slice,
                                        String sliceId,
                                        Map<String, TopologyNode> nodeMap,
                                        List<TopologyEdge> edgeList) {
        for (var pub : slice.publishes()) {
            var topicId = "topic-pub:" + pub.config();
            nodeMap.putIfAbsent(topicId, new TopologyNode(topicId, NodeType.TOPIC_PUB, pub.config()));
            edgeList.add(new TopologyEdge(sliceId, topicId, EdgeStyle.SOLID));
        }
    }

    private static void addSubscribeNodes(SliceTopology slice,
                                          String sliceId,
                                          Map<String, TopologyNode> nodeMap,
                                          List<TopologyEdge> edgeList) {
        for (var sub : slice.subscribes()) {
            var topicId = "topic-sub:" + sub.config();
            nodeMap.putIfAbsent(topicId, new TopologyNode(topicId, NodeType.TOPIC_SUB, sub.config()));
            edgeList.add(new TopologyEdge(topicId, sliceId, EdgeStyle.SOLID));
        }
    }

    private static void addPubSubEdges(Map<String, TopologyNode> nodeMap,
                                       List<TopologyEdge> edgeList) {
        for (var id : nodeMap.keySet()) {
            if (id.startsWith("topic-pub:")) {
                var config = id.substring("topic-pub:".length());
                var subId = "topic-sub:" + config;
                if (nodeMap.containsKey(subId)) {
                    edgeList.add(new TopologyEdge(id, subId, EdgeStyle.DOTTED));
                }
            }
        }
    }
}
