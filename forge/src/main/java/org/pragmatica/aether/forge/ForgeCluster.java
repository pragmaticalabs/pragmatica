package org.pragmatica.aether.forge;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.RollbackConfig;
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
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/**
 * Manages a cluster of AetherNodes for Forge.
 * Supports starting, stopping, adding, and killing nodes.
 */
public final class ForgeCluster {
    private static final Logger log = LoggerFactory.getLogger(ForgeCluster.class);

    private static final int DEFAULT_BASE_PORT = 5050;
    private static final int DEFAULT_BASE_MGMT_PORT = 5150;
    private static final TimeSpan NODE_TIMEOUT = TimeSpan.timeSpan(10)
                                                        .seconds();

    private final Map<String, AetherNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeInfo> nodeInfos = new ConcurrentHashMap<>();
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final int initialClusterSize;
    private final int basePort;
    private final int baseMgmtPort;
    private final String nodeIdPrefix;

    private ForgeCluster(int initialClusterSize, int basePort, int baseMgmtPort, String nodeIdPrefix) {
        this.initialClusterSize = initialClusterSize;
        this.basePort = basePort;
        this.baseMgmtPort = baseMgmtPort;
        this.nodeIdPrefix = nodeIdPrefix;
    }

    public static ForgeCluster forgeCluster() {
        return forgeCluster(5);
    }

    public static ForgeCluster forgeCluster(int initialSize) {
        return new ForgeCluster(initialSize, DEFAULT_BASE_PORT, DEFAULT_BASE_MGMT_PORT, "node");
    }

    /**
     * Create a ForgeCluster with custom port ranges.
     * Use this to avoid port conflicts when running multiple tests in parallel.
     *
     * @param initialSize  Number of nodes to start with
     * @param basePort     Base port for cluster communication (each node uses basePort + nodeIndex)
     * @param baseMgmtPort Base port for management HTTP API (each node uses baseMgmtPort + nodeIndex)
     */
    public static ForgeCluster forgeCluster(int initialSize, int basePort, int baseMgmtPort) {
        return new ForgeCluster(initialSize, basePort, baseMgmtPort, "node");
    }

    /**
     * Create a ForgeCluster with custom port ranges and node ID prefix.
     * Use this to avoid port conflicts when running multiple tests in parallel.
     *
     * @param initialSize   Number of nodes to start with
     * @param basePort      Base port for cluster communication (each node uses basePort + nodeIndex)
     * @param baseMgmtPort  Base port for management HTTP API (each node uses baseMgmtPort + nodeIndex)
     * @param nodeIdPrefix  Prefix for node IDs (e.g., "cf" creates nodes "cf-1", "cf-2", etc.)
     */
    public static ForgeCluster forgeCluster(int initialSize, int basePort, int baseMgmtPort, String nodeIdPrefix) {
        return new ForgeCluster(initialSize, basePort, baseMgmtPort, nodeIdPrefix);
    }

    /**
     * Start the initial cluster with configured number of nodes.
     * If any node fails to start, all successfully started nodes are stopped and the failure is returned.
     */
    public Promise<Unit> start() {
        log.info("Starting Forge cluster with {} nodes on ports {}-{}",
                 initialClusterSize,
                 basePort,
                 basePort + initialClusterSize - 1);
        // Create node infos for initial cluster
        var initialNodes = new ArrayList<NodeInfo>();
        for (int i = 1; i <= initialClusterSize; i++) {
            var nodeId = nodeId(nodeIdPrefix + "-" + i).unwrap();
            var port = basePort + i - 1;
            var info = new NodeInfo(nodeId, nodeAddress("localhost", port).unwrap());
            initialNodes.add(info);
            nodeInfos.put(nodeId.id(), info);
        }
        nodeCounter.set(initialClusterSize);
        // Create and start all nodes, tracking results individually
        var startPromises = new ArrayList<Promise<NodeStartResult>>();
        for (int i = 0; i < initialClusterSize; i++) {
            var nodeInfo = initialNodes.get(i);
            var nodeIdStr = nodeInfo.id()
                                    .id();
            var port = basePort + i;
            var mgmtPort = baseMgmtPort + i;
            var node = createNode(nodeInfo.id(), port, mgmtPort, initialNodes);
            nodes.put(nodeIdStr, node);
            // Wrap start() to capture success/failure with node context
            startPromises.add(node.start()
                                  .map(_ -> new NodeStartResult(nodeIdStr, port, mgmtPort, null))
                                  .recover(cause -> new NodeStartResult(nodeIdStr, port, mgmtPort, cause)));
        }
        return Promise.allOf(startPromises)
                      .flatMap(this::handleStartResults);
    }

    private record NodeStartResult(String nodeId, int port, int mgmtPort, org.pragmatica.lang.Cause failure) {
        boolean succeeded() {
            return failure == null;
        }
    }

    private Promise<Unit> handleStartResults(List<Result<NodeStartResult>> results) {
        // Extract NodeStartResult from each Result (all succeed due to recover())
        var nodeResults = results.stream()
                                 .map(r -> r.fold(_ -> null, r1 -> r1))
                                 .filter(r -> r != null)
                                 .toList();
        var failed = nodeResults.stream()
                                .filter(r -> !r.succeeded())
                                .toList();
        var succeeded = nodeResults.stream()
                                   .filter(NodeStartResult::succeeded)
                                   .toList();
        if (failed.isEmpty()) {
            log.info("Forge cluster started with {} nodes", initialClusterSize);
            return Promise.success(Unit.unit());
        }
        // Log all failures with details
        for (var f : failed) {
            log.error("Node {} failed to start on port {} (mgmt: {}): {}",
                      f.nodeId(),
                      f.port(),
                      f.mgmtPort(),
                      f.failure()
                       .message());
        }
        log.error("Cluster startup failed: {} of {} nodes failed to start", failed.size(), initialClusterSize);
        // Stop successfully started nodes
        var stopPromises = succeeded.stream()
                                    .map(r -> Option.option(nodes.get(r.nodeId()))
                                                    .fold(() -> Promise.success(Unit.unit()),
                                                          node -> node.stop()
                                                                      .timeout(NODE_TIMEOUT)
                                                                      .recover(_ -> Unit.unit())))
                                    .toList();
        return Promise.allOf(stopPromises)
                      .map(_ -> {
                          nodes.clear();
                          nodeInfos.clear();
                          nodeCounter.set(0);
                          return Unit.unit();
                      })
                      .flatMap(_ -> failed.getFirst()
                                          .failure()
                                          .promise());
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
                      .onSuccess(this::clearClusterState);
    }

    private void clearClusterState(Unit unit) {
        nodes.clear();
        nodeInfos.clear();
        log.info("Forge cluster stopped");
    }

    /**
     * Add a new node to the cluster.
     * Returns the new node's ID.
     */
    public Promise<NodeId> addNode() {
        var nodeNum = nodeCounter.incrementAndGet();
        var nodeId = nodeId(nodeIdPrefix + "-" + nodeNum).unwrap();
        var port = basePort + nodeNum - 1;
        var info = new NodeInfo(nodeId, nodeAddress("localhost", port).unwrap());
        log.info("Adding new node {} on port {}", nodeId.id(), port);
        nodeInfos.put(nodeId.id(), info);
        // Get current topology including the new node
        var allNodes = new ArrayList<>(nodeInfos.values());
        var mgmtPort = baseMgmtPort + nodeNum - 1;
        var node = createNode(nodeId, port, mgmtPort, allNodes);
        nodes.put(nodeId.id(), node);
        return node.start()
                   .map(_ -> nodeId)
                   .onSuccess(_ -> log.info("Node {} joined the cluster",
                                            nodeId.id()));
    }

    /**
     * Gracefully stop and remove a node from the cluster.
     * The node cannot be restarted - use addNode() to create a new node.
     */
    public Promise<Unit> killNode(String nodeIdStr) {
        return killNode(nodeIdStr, true);
    }

    /**
     * Stop and remove a node from the cluster.
     *
     * @param nodeIdStr Node ID to kill
     * @param graceful  If true, waits for normal timeout; if false, uses 1-second timeout
     */
    public Promise<Unit> killNode(String nodeIdStr, boolean graceful) {
        return Option.option(nodes.get(nodeIdStr))
                     .fold(() -> {
                               log.warn("Node {} not found", nodeIdStr);
                               return Promise.success(Unit.unit());
                           },
                           node -> killNodeInternal(nodeIdStr, node, graceful));
    }

    private Promise<Unit> killNodeInternal(String nodeIdStr, AetherNode node, boolean graceful) {
        var timeout = graceful
                      ? NODE_TIMEOUT
                      : TimeSpan.timeSpan(1)
                                .seconds();
        log.info("{} node {}", graceful
                              ? "Stopping"
                              : "Force-killing", nodeIdStr);
        return node.stop()
                   .timeout(timeout)
                   .recover(_ -> Unit.unit())
                   .map(_ -> {
                       nodes.remove(nodeIdStr);
                       nodeInfos.remove(nodeIdStr);
                       return Unit.unit();
                   })
                   .onSuccess(_ -> log.info("Node {} removed from cluster", nodeIdStr));
    }

    /**
     * Get the current leader node ID from consensus.
     */
    public Option<String> currentLeader() {
        if (nodes.isEmpty()) {
            return Option.none();
        }
        // Query actual leader from any node's consensus layer
        return nodes.values()
                    .stream()
                    .findFirst()
                    .flatMap(node -> node.leader()
                                         .toOptional())
                    .map(nodeId -> nodeId.id())
                    .map(Option::option)
                    .orElse(Option.none());
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
                                         return new NodeStatus(entry.getKey(),
                                                               clusterPort,
                                                               baseMgmtPort + (clusterPort - basePort),
                                                               "healthy",
                                                               currentLeader().map(leaderId -> leaderId.equals(entry.getKey()))
                                                                            .or(false));
                                     })
                                .toList();
        return new ClusterStatus(nodeStatuses, currentLeader().or("none"));
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

    /**
     * Get the management port of the current leader node.
     */
    public Option<Integer> getLeaderManagementPort() {
        return currentLeader().flatMap(leaderId -> Option.option(nodeInfos.get(leaderId)))
                            .map(info -> baseMgmtPort + (info.address()
                                                             .port() - basePort));
    }

    private AetherNode createNode(NodeId nodeId, int port, int mgmtPort, List<NodeInfo> coreNodes) {
        var topology = new TopologyConfig(nodeId, timeSpan(500).millis(), timeSpan(100).millis(), coreNodes);
        var config = new AetherNodeConfig(topology,
                                          ProtocolConfig.testConfig(),
                                          SliceActionConfig.defaultConfiguration(furySerializerFactoryProvider()),
                                          org.pragmatica.aether.config.SliceConfig.defaults(),
                                          mgmtPort,
                                          DHTConfig.FULL,
                                          Option.empty(),
                                          org.pragmatica.aether.config.TTMConfig.disabled(),
                                          RollbackConfig.defaults(),
                                          AppHttpConfig.disabled());
        return AetherNode.aetherNode(config)
                         .unwrap();
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
                             return new NodeMetrics(nodeId,
                                                    currentLeader().map(l -> l.equals(nodeId))
                                                                 .or(false),
                                                    cpuUsage,
                                                    (long)(heapUsed / 1024 / 1024),
                                                    (long)(heapMax / 1024 / 1024));
                         })
                    .toList();
    }

    /**
     * Status of a single node.
     */
    public record NodeStatus(String id,
                             int port,
                             int mgmtPort,
                             String state,
                             boolean isLeader) {}

    /**
     * Status of the entire cluster.
     */
    public record ClusterStatus(List<NodeStatus> nodes,
                                String leaderId) {}

    /**
     * Per-node metrics for dashboard display.
     */
    public record NodeMetrics(String nodeId,
                              boolean isLeader,
                              double cpuUsage,
                              long heapUsedMb,
                              long heapMaxMb) {}

    /**
     * Slice status records for dashboard display.
     */
    public record SliceStatus(String artifact,
                              String state,
                              List<SliceInstanceStatus> instances) {}

    public record SliceInstanceStatus(String nodeId,
                                      String state,
                                      String health) {}

    /**
     * Get slice status from the KV store.
     * Returns status for all slices across all nodes.
     */
    public List<SliceStatus> slicesStatus() {
        // Get any node to query KV store (all nodes have consistent view)
        if (nodes.isEmpty()) {
            return List.of();
        }
        var node = nodes.values()
                        .iterator()
                        .next();
        var kvSnapshot = node.kvStore()
                             .snapshot();
        // Group slice instances by artifact
        var slicesByArtifact = new java.util.HashMap<String, List<SliceInstanceStatus>>();
        var stateByArtifact = new java.util.HashMap<String, org.pragmatica.aether.slice.SliceState>();
        kvSnapshot.forEach((key, value) -> {
                               if (key instanceof org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey sliceKey && value instanceof org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue sliceValue) {
                                   var artifactStr = sliceKey.artifact()
                                                             .asString();
                                   var instanceState = sliceValue.state();
                                   var health = instanceState == org.pragmatica.aether.slice.SliceState.ACTIVE
                                                ? "HEALTHY"
                                                : "UNHEALTHY";
                                   slicesByArtifact.computeIfAbsent(artifactStr,
                                                                    _ -> new ArrayList<>())
                                                   .add(new SliceInstanceStatus(sliceKey.nodeId()
                                                                                        .id(),
                                                                                instanceState.name(),
                                                                                health));
                                   // Track highest state for aggregate
        stateByArtifact.merge(artifactStr,
                              instanceState,
                              (old, curr) -> curr == org.pragmatica.aether.slice.SliceState.ACTIVE
                                             ? curr
                                             : old);
                               }
                           });
        // Build result
        return slicesByArtifact.entrySet()
                               .stream()
                               .map(entry -> new SliceStatus(entry.getKey(),
                                                             stateByArtifact.getOrDefault(entry.getKey(),
                                                                                          org.pragmatica.aether.slice.SliceState.FAILED)
                                                                            .name(),
                                                             entry.getValue()))
                               .toList();
    }
}
