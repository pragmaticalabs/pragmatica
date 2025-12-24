package org.pragmatica.aether.demo;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.cluster.net.NodeId.nodeId;
import static org.pragmatica.cluster.net.NodeInfo.nodeInfo;
import static org.pragmatica.net.NodeAddress.nodeAddress;

/**
 * Manages a cluster of AetherNodes for the demo.
 * Supports starting, stopping, adding, and killing nodes.
 */
public final class DemoCluster {
    private static final Logger log = LoggerFactory.getLogger(DemoCluster.class);

    private static final int BASE_PORT = 5050;
    private static final TimeSpan NODE_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    private final Map<String, AetherNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeInfo> nodeInfos = new ConcurrentHashMap<>();
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final int initialClusterSize;

    private DemoCluster(int initialClusterSize) {
        this.initialClusterSize = initialClusterSize;
    }

    public static DemoCluster demoCluster() {
        return demoCluster(5);
    }

    public static DemoCluster demoCluster(int initialSize) {
        return new DemoCluster(initialSize);
    }

    /**
     * Start the initial cluster with configured number of nodes.
     */
    public Promise<Unit> start() {
        log.info("Starting demo cluster with {} nodes", initialClusterSize);

        // Create node infos for initial cluster
        var initialNodes = new ArrayList<NodeInfo>();
        for (int i = 1; i <= initialClusterSize; i++) {
            var nodeId = nodeId("node-" + i);
            var port = BASE_PORT + i - 1;
            var info = nodeInfo(nodeId, nodeAddress("localhost", port));
            initialNodes.add(info);
            nodeInfos.put(nodeId.id(), info);
        }
        nodeCounter.set(initialClusterSize);

        // Create and start all nodes
        var startPromises = new ArrayList<Promise<Unit>>();
        for (int i = 0; i < initialClusterSize; i++) {
            var nodeInfo = initialNodes.get(i);
            var node = createNode(nodeInfo.id(), BASE_PORT + i, initialNodes);
            nodes.put(nodeInfo.id().id(), node);
            startPromises.add(node.start());
        }

        return Promise.allOf(startPromises)
                      .map(_ -> Unit.unit())
                      .onSuccess(_ -> log.info("Demo cluster started with {} nodes", initialClusterSize));
    }

    /**
     * Stop all nodes gracefully.
     */
    public Promise<Unit> stop() {
        log.info("Stopping demo cluster");

        var stopPromises = nodes.values()
                                .stream()
                                .map(node -> node.stop().timeout(NODE_TIMEOUT))
                                .toList();

        return Promise.allOf(stopPromises)
                      .map(_ -> Unit.unit())
                      .onSuccess(_ -> {
                          nodes.clear();
                          nodeInfos.clear();
                          log.info("Demo cluster stopped");
                      });
    }

    /**
     * Add a new node to the cluster.
     * Returns the new node's ID.
     */
    public Promise<NodeId> addNode() {
        var nodeNum = nodeCounter.incrementAndGet();
        var nodeId = nodeId("node-" + nodeNum);
        var port = BASE_PORT + nodeNum - 1;
        var info = nodeInfo(nodeId, nodeAddress("localhost", port));

        log.info("Adding new node {} on port {}", nodeId.id(), port);

        nodeInfos.put(nodeId.id(), info);

        // Get current topology including the new node
        var allNodes = new ArrayList<>(nodeInfos.values());

        var node = createNode(nodeId, port, allNodes);
        nodes.put(nodeId.id(), node);

        return node.start()
                   .map(_ -> nodeId)
                   .onSuccess(_ -> log.info("Node {} joined the cluster", nodeId.id()));
    }

    /**
     * Gracefully stop a node.
     */
    public Promise<Unit> killNode(String nodeIdStr) {
        var node = nodes.get(nodeIdStr);
        if (node == null) {
            log.warn("Node {} not found", nodeIdStr);
            return Promise.success(Unit.unit());
        }

        log.info("Killing node {}", nodeIdStr);

        return node.stop()
                   .timeout(NODE_TIMEOUT)
                   .map(_ -> {
                       nodes.remove(nodeIdStr);
                       nodeInfos.remove(nodeIdStr);
                       return Unit.unit();
                   })
                   .onSuccess(_ -> log.info("Node {} killed", nodeIdStr));
    }

    /**
     * Abruptly crash a node (immediate stop).
     */
    public Promise<Unit> crashNode(String nodeIdStr) {
        var node = nodes.get(nodeIdStr);
        if (node == null) {
            log.warn("Node {} not found", nodeIdStr);
            return Promise.success(Unit.unit());
        }

        log.info("Crashing node {} abruptly", nodeIdStr);

        // For crash simulation, we still call stop() but don't wait long
        return node.stop()
                   .timeout(TimeSpan.timeSpan(1).seconds())
                   .recover(_ -> Unit.unit())  // Ignore timeout errors
                   .map(_ -> {
                       nodes.remove(nodeIdStr);
                       nodeInfos.remove(nodeIdStr);
                       return Unit.unit();
                   })
                   .onSuccess(_ -> log.info("Node {} crashed", nodeIdStr));
    }

    /**
     * Perform a rolling restart of all nodes.
     * Restarts one node at a time, waiting for it to rejoin before continuing.
     */
    public Promise<Unit> rollingRestart() {
        log.info("Starting rolling restart");

        var nodeIds = new ArrayList<>(nodes.keySet());
        var currentTopology = new ArrayList<>(nodeInfos.values());

        // Chain restarts sequentially
        Promise<Unit> chain = Promise.success(Unit.unit());

        for (var nodeIdStr : nodeIds) {
            var nodeInfo = nodeInfos.get(nodeIdStr);
            if (nodeInfo == null) continue;

            chain = chain.flatMap(_ -> restartNode(nodeIdStr, nodeInfo, currentTopology));
        }

        return chain.onSuccess(_ -> log.info("Rolling restart completed"));
    }

    private Promise<Unit> restartNode(String nodeIdStr, NodeInfo nodeInfo, List<NodeInfo> topology) {
        log.info("Rolling restart: restarting {}", nodeIdStr);

        var node = nodes.get(nodeIdStr);
        if (node == null) {
            return Promise.success(Unit.unit());
        }

        return node.stop()
                   .timeout(NODE_TIMEOUT)
                   .flatMap(_ -> {
                       // Brief delay before restart
                       try {
                           Thread.sleep(300);
                       } catch (InterruptedException e) {
                           Thread.currentThread().interrupt();
                       }

                       var port = nodeInfo.address().port();
                       var newNode = createNode(nodeInfo.id(), port, topology);
                       nodes.put(nodeIdStr, newNode);

                       return newNode.start();
                   })
                   .flatMap(_ -> {
                       // Wait for node to stabilize
                       try {
                           Thread.sleep(500);
                       } catch (InterruptedException e) {
                           Thread.currentThread().interrupt();
                       }
                       return Promise.success(Unit.unit());
                   })
                   .onSuccess(_ -> log.info("Node {} restarted", nodeIdStr));
    }

    /**
     * Get the current leader node ID.
     * In Aether, the leader is deterministically the first node in sorted topology.
     */
    public Option<String> currentLeader() {
        if (nodes.isEmpty()) {
            return Option.none();
        }

        return nodeInfos.keySet()
                        .stream()
                        .sorted()
                        .findFirst()
                        .map(Option::option)
                        .orElse(Option.none());
    }

    /**
     * Get the current cluster status for the dashboard.
     */
    public ClusterStatus status() {
        var nodeStatuses = nodes.entrySet()
                                .stream()
                                .map(entry -> new NodeStatus(
                                        entry.getKey(),
                                        nodeInfos.get(entry.getKey()).address().port(),
                                        "healthy",
                                        currentLeader().fold(
                                                () -> false,
                                                leaderId -> leaderId.equals(entry.getKey())
                                        )
                                ))
                                .toList();

        return new ClusterStatus(nodeStatuses, currentLeader().fold(() -> "none", leader -> leader));
    }

    /**
     * Get a node by ID.
     */
    public Option<AetherNode> getNode(String nodeIdStr) {
        return Option.option(nodes.get(nodeIdStr));
    }

    /**
     * Get all nodes.
     */
    public List<AetherNode> allNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Get node count.
     */
    public int nodeCount() {
        return nodes.size();
    }

    private AetherNode createNode(NodeId nodeId, int port, List<NodeInfo> coreNodes) {
        var config = AetherNodeConfig.testConfig(nodeId, port, coreNodes);
        return AetherNode.aetherNode(config);
    }

    /**
     * Status of a single node.
     */
    public record NodeStatus(
            String id,
            int port,
            String state,
            boolean isLeader
    ) {}

    /**
     * Status of the entire cluster.
     */
    public record ClusterStatus(
            List<NodeStatus> nodes,
            String leaderId
    ) {}
}
