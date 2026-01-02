package org.pragmatica.aether.forge;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.slice.serialization.FurySerializerFactoryProvider.furySerializerFactoryProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/**
 * Manages a cluster of AetherNodes for Forge.
 * Supports starting, stopping, adding, and killing nodes.
 */
public final class ForgeCluster {
    private static final Logger log = LoggerFactory.getLogger(ForgeCluster.class);

    private static final int BASE_PORT = 5050;
    private static final int BASE_MGMT_PORT = 5150;
    private static final TimeSpan NODE_TIMEOUT = TimeSpan.timeSpan(10)
                                                        .seconds();

    private final Map<String, AetherNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeInfo> nodeInfos = new ConcurrentHashMap<>();
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final int initialClusterSize;

    private ForgeCluster(int initialClusterSize) {
        this.initialClusterSize = initialClusterSize;
    }

    public static ForgeCluster forgeCluster() {
        return forgeCluster(5);
    }

    public static ForgeCluster forgeCluster(int initialSize) {
        return new ForgeCluster(initialSize);
    }

    /**
     * Start the initial cluster with configured number of nodes.
     */
    public Promise<Unit> start() {
        log.info("Starting Forge cluster with {} nodes", initialClusterSize);
        // Create node infos for initial cluster
        var initialNodes = new ArrayList<NodeInfo>();
        for (int i = 1; i <= initialClusterSize; i++ ) {
            var nodeId = nodeId("node-" + i);
            var port = BASE_PORT + i - 1;
            var info = nodeInfo(nodeId, nodeAddress("localhost", port));
            initialNodes.add(info);
            nodeInfos.put(nodeId.id(), info);
        }
        nodeCounter.set(initialClusterSize);
        // Create and start all nodes
        var startPromises = new ArrayList<Promise<Unit>>();
        for (int i = 0; i < initialClusterSize; i++ ) {
            var nodeInfo = initialNodes.get(i);
            var node = createNode(nodeInfo.id(), BASE_PORT + i, BASE_MGMT_PORT + i, initialNodes);
            nodes.put(nodeInfo.id()
                              .id(),
                      node);
            startPromises.add(node.start());
        }
        return Promise.allOf(startPromises)
                      .map(_ -> Unit.unit())
                      .onSuccess(_ -> log.info("Forge cluster started with {} nodes", initialClusterSize));
    }

    /**
     * Stop all nodes gracefully.
     */
    public Promise<Unit> stop() {
        log.info("Stopping Forge cluster");
        var stopPromises = nodes.values()
                                .stream()
                                .map(node -> node.stop()
                                                 .timeout(NODE_TIMEOUT))
                                .toList();
        return Promise.allOf(stopPromises)
                      .map(_ -> Unit.unit())
                      .onSuccess(_ -> {
                          nodes.clear();
                          nodeInfos.clear();
                          log.info("Forge cluster stopped");
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
        var mgmtPort = BASE_MGMT_PORT + nodeNum - 1;
        var node = createNode(nodeId, port, mgmtPort, allNodes);
        nodes.put(nodeId.id(), node);
        return node.start()
                   .map(_ -> nodeId)
                   .onSuccess(_ -> log.info("Node {} joined the cluster",
                                            nodeId.id()));
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
                   .timeout(TimeSpan.timeSpan(1)
                                    .seconds())
                   .recover(_ -> Unit.unit())
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
                   .flatMap(_ -> Promise.promise(TimeSpan.timeSpan(300)
                                                         .millis(),
                                                 () -> Result.success(Unit.unit())))
                   .flatMap(_ -> {
                                var port = nodeInfo.address()
                                                   .port();
                                var mgmtPort = BASE_MGMT_PORT + (port - BASE_PORT);
                                var newNode = createNode(nodeInfo.id(),
                                                         port,
                                                         mgmtPort,
                                                         topology);
                                nodes.put(nodeIdStr, newNode);
                                return newNode.start();
                            })
                   .flatMap(_ -> Promise.promise(TimeSpan.timeSpan(500)
                                                         .millis(),
                                                 () -> Result.success(Unit.unit())))
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
        // Take snapshot and sort to ensure consistent leader detection
        var sortedNodes = nodeInfos.keySet()
                                   .stream()
                                   .sorted()
                                   .toList();
        return sortedNodes.isEmpty()
               ? Option.none()
               : Option.option(sortedNodes.getFirst());
    }

    /**
     * Get the current cluster status for the dashboard.
     */
    public ClusterStatus status() {
        var nodeStatuses = nodes.entrySet()
                                .stream()
                                .map(entry -> {
                                         var clusterPort = nodeInfos.get(entry.getKey())
                                                                    .address()
                                                                    .port();
                                         return new NodeStatus(
        entry.getKey(),
        clusterPort,
        BASE_MGMT_PORT + (clusterPort - BASE_PORT),
        "healthy",
        currentLeader()
        .fold(() -> false,
              leaderId -> leaderId.equals(entry.getKey())));
                                     })
                                .toList();
        return new ClusterStatus(nodeStatuses, currentLeader()
                                              .fold(() -> "none",
                                                    leader -> leader));
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

    private AetherNode createNode(NodeId nodeId, int port, int mgmtPort, List<NodeInfo> coreNodes) {
        var topology = new TopologyConfig(
        nodeId, timeSpan(500)
               .millis(), timeSpan(100)
                         .millis(), coreNodes);
        var config = new AetherNodeConfig(
        topology,
        ProtocolConfig.testConfig(),
        SliceActionConfig.defaultConfiguration(furySerializerFactoryProvider()),
        mgmtPort,
        Option.empty(),
        DHTConfig.FULL,
        Option.empty());
        return AetherNode.aetherNode(config);
    }

    /**
     * Get per-node metrics for all nodes.
     */
    public List<NodeMetrics> nodeMetrics() {
        return nodes.entrySet()
                    .stream()
                    .map(entry -> {
                             var nodeId = entry.getKey();
                             var node = entry.getValue();
                             var metrics = node.metricsCollector()
                                               .collectLocal();
                             var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
                             var heapUsed = metrics.getOrDefault("heap.used", 0.0);
                             var heapMax = metrics.getOrDefault("heap.max", 1.0);
                             return new NodeMetrics(
        nodeId,
        currentLeader()
        .fold(() -> false,
              l -> l.equals(nodeId)),
        cpuUsage,
        (long)(heapUsed / 1024 / 1024),
        (long)(heapMax / 1024 / 1024));
                         })
                    .toList();
    }

    /**
     * Status of a single node.
     */
    public record NodeStatus(
    String id,
    int port,
    int mgmtPort,
    String state,
    boolean isLeader) {}

    /**
     * Status of the entire cluster.
     */
    public record ClusterStatus(
    List<NodeStatus> nodes,
    String leaderId) {}

    /**
     * Per-node metrics for dashboard display.
     */
    public record NodeMetrics(
    String nodeId,
    boolean isLeader,
    double cpuUsage,
    long heapUsedMb,
    long heapMaxMb) {}
}
