package org.pragmatica.aether.forge;

import org.pragmatica.aether.controller.ControllerConfig;
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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.forge.api.ChaosRoutes.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RollingRestartResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RollingRestartStatusResponse;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    public static final int DEFAULT_BASE_PORT = 6000;
    public static final int DEFAULT_BASE_MGMT_PORT = 6100;
    public static final int DEFAULT_BASE_APP_HTTP_PORT = 8070;
    private static final TimeSpan NODE_TIMEOUT = TimeSpan.timeSpan(10)
                                                        .seconds();
    private static final long ROLLING_RESTART_DELAY_MS = 10_000;

    // Auto-heal constants
    private static final int AUTO_HEAL_MAX_RETRIES = 3;
    private static final TimeSpan AUTO_HEAL_RETRY_DELAY = timeSpan(5).seconds();

    private final Map<String, AetherNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeInfo> nodeInfos = new ConcurrentHashMap<>();
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final Queue<Integer> availableSlots = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> slotsByNodeId = new ConcurrentHashMap<>();
    private final int initialClusterSize;
    private final int basePort;
    private final int baseMgmtPort;
    private final int baseAppHttpPort;
    private final String nodeIdPrefix;
    private final AtomicBoolean rollingRestartActive = new AtomicBoolean(false);
    private final ScheduledExecutorService rollingRestartExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> rollingRestartTask;
    private final Random random = new Random();

    // Auto-heal state
    private final boolean autoHealEnabled;
    private final int targetClusterSize;

    private ForgeCluster(int initialClusterSize,
                         int basePort,
                         int baseMgmtPort,
                         int baseAppHttpPort,
                         String nodeIdPrefix,
                         boolean autoHealEnabled) {
        this.initialClusterSize = initialClusterSize;
        this.basePort = basePort;
        this.baseMgmtPort = baseMgmtPort;
        this.baseAppHttpPort = baseAppHttpPort;
        this.nodeIdPrefix = nodeIdPrefix;
        this.autoHealEnabled = autoHealEnabled;
        this.targetClusterSize = initialClusterSize;
    }

    public static ForgeCluster forgeCluster() {
        return forgeCluster(5);
    }

    public static ForgeCluster forgeCluster(int initialSize) {
        return forgeCluster(initialSize, false);
    }

    /**
     * Create a ForgeCluster with auto-heal option.
     *
     * @param initialSize     Number of nodes to start with
     * @param autoHealEnabled If true, automatically replace killed nodes to maintain target size
     */
    public static ForgeCluster forgeCluster(int initialSize, boolean autoHealEnabled) {
        return new ForgeCluster(initialSize,
                                DEFAULT_BASE_PORT,
                                DEFAULT_BASE_MGMT_PORT,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                "node",
                                autoHealEnabled);
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
        return new ForgeCluster(initialSize, basePort, baseMgmtPort, DEFAULT_BASE_APP_HTTP_PORT, "node", false);
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
        return new ForgeCluster(initialSize, basePort, baseMgmtPort, DEFAULT_BASE_APP_HTTP_PORT, nodeIdPrefix, false);
    }

    /**
     * Create a ForgeCluster with custom port ranges including app HTTP.
     *
     * @param initialSize     Number of nodes to start with
     * @param basePort        Base port for cluster communication
     * @param baseMgmtPort    Base port for management HTTP API
     * @param baseAppHttpPort Base port for application HTTP API (slice endpoints)
     * @param nodeIdPrefix    Prefix for node IDs
     */
    public static ForgeCluster forgeCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix) {
        return forgeCluster(initialSize, basePort, baseMgmtPort, baseAppHttpPort, nodeIdPrefix, false);
    }

    /**
     * Create a ForgeCluster with all options including auto-heal.
     *
     * @param initialSize     Number of nodes to start with
     * @param basePort        Base port for cluster communication
     * @param baseMgmtPort    Base port for management HTTP API
     * @param baseAppHttpPort Base port for application HTTP API (slice endpoints)
     * @param nodeIdPrefix    Prefix for node IDs
     * @param autoHealEnabled If true, automatically replace killed nodes to maintain target size
     */
    public static ForgeCluster forgeCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            boolean autoHealEnabled) {
        return new ForgeCluster(initialSize, basePort, baseMgmtPort, baseAppHttpPort, nodeIdPrefix, autoHealEnabled);
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
        // Initialize slot pool (2x target size for headroom)
        int poolSize = 2 * targetClusterSize;
        availableSlots.clear();
        for (int i = 0; i < poolSize; i++) {
            availableSlots.offer(i);
        }
        // Create node infos for initial cluster
        var initialNodes = new ArrayList<NodeInfo>();
        for (int i = 1; i <= initialClusterSize; i++) {
            var slot = availableSlots.poll();
            var nodeId = nodeId(nodeIdPrefix + "-" + i).unwrap();
            var port = basePort + slot;
            var info = new NodeInfo(nodeId, nodeAddress("localhost", port).unwrap());
            initialNodes.add(info);
            nodeInfos.put(nodeId.id(), info);
            slotsByNodeId.put(nodeId.id(), slot);
        }
        nodeCounter.set(initialClusterSize);
        // Create and start all nodes, tracking results individually
        var startPromises = new ArrayList<Promise<NodeStartResult>>();
        for (int i = 0; i < initialClusterSize; i++) {
            var nodeInfo = initialNodes.get(i);
            var nodeIdStr = nodeInfo.id()
                                    .id();
            var slot = slotsByNodeId.get(nodeIdStr);
            var port = basePort + slot;
            var mgmtPort = baseMgmtPort + slot;
            var appHttpPort = baseAppHttpPort + slot;
            var node = createNode(nodeInfo.id(), port, mgmtPort, appHttpPort, initialNodes);
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
                               slotsByNodeId.clear();
                               availableSlots.clear();
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
        slotsByNodeId.clear();
        availableSlots.clear();
        log.info("Forge cluster stopped");
    }

    /**
     * Add a new node to the cluster.
     * Returns the new node's ID.
     */
    public Promise<NodeId> addNode() {
        var slot = availableSlots.poll();
        if (slot == null) {
            // Wrap around - reuse slot 0 (shouldn't happen with 2x buffer)
            log.warn("Slot pool exhausted, this shouldn't happen");
            slot = 0;
        }
        var nodeNum = nodeCounter.incrementAndGet();
        var nodeId = nodeId(nodeIdPrefix + "-" + nodeNum).unwrap();
        var port = basePort + slot;
        var mgmtPort = baseMgmtPort + slot;
        var appHttpPort = baseAppHttpPort + slot;
        var info = new NodeInfo(nodeId, nodeAddress("localhost", port).unwrap());
        log.info("Adding new node {} on port {}", nodeId.id(), port);
        nodeInfos.put(nodeId.id(), info);
        slotsByNodeId.put(nodeId.id(), slot);
        // Get current topology including the new node
        var allNodes = new ArrayList<>(nodeInfos.values());
        var node = createNode(nodeId, port, mgmtPort, appHttpPort, allNodes);
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
                     .fold(() -> nodeNotFound(nodeIdStr),
                           node -> killNodeInternal(nodeIdStr, node, graceful));
    }

    private Promise<Unit> nodeNotFound(String nodeIdStr) {
        log.warn("Node {} not found", nodeIdStr);
        return Promise.success(Unit.unit());
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
                            // Release slot back to pool
        var slot = slotsByNodeId.remove(nodeIdStr);
                            if (slot != null) {
                                availableSlots.offer(slot);
                            }
                            return Unit.unit();
                        })
                   .onSuccess(_ -> log.info("Node {} removed from cluster", nodeIdStr))
                   .onSuccess(_ -> triggerAutoHeal());
    }

    /**
     * Trigger auto-healing if enabled and cluster is below target size.
     * Called after a node is killed to maintain target cluster size.
     */
    private void triggerAutoHeal() {
        if (!autoHealEnabled) {
            return;
        }
        var currentSize = nodes.size();
        var deficit = targetClusterSize - currentSize;
        if (deficit > 0) {
            log.info("AUTO-HEAL: Cluster size {} below target {}, starting {} replacement node(s)",
                     currentSize,
                     targetClusterSize,
                     deficit);
            for (int i = 0; i < deficit; i++) {
                attemptNodeStart(0);
            }
        }
    }

    private void attemptNodeStart(int attemptNumber) {
        if (attemptNumber >= AUTO_HEAL_MAX_RETRIES) {
            log.error("AUTO-HEAL: Failed to start replacement node after {} attempts", AUTO_HEAL_MAX_RETRIES);
            return;
        }
        log.info("AUTO-HEAL: Attempting to start replacement node (attempt {}/{})",
                 attemptNumber + 1,
                 AUTO_HEAL_MAX_RETRIES);
        addNode().onSuccess(nodeId -> log.info("AUTO-HEAL: Successfully started replacement node {}",
                                               nodeId.id()))
               .onFailure(cause -> {
                              log.warn("AUTO-HEAL: Failed to start replacement node: {}, retrying in {}",
                                       cause.message(),
                                       AUTO_HEAL_RETRY_DELAY);
                              SharedScheduler.schedule(() -> attemptNodeStart(attemptNumber + 1),
                                                       AUTO_HEAL_RETRY_DELAY);
                          });
    }

    /**
     * Check if auto-heal is enabled.
     */
    public boolean isAutoHealEnabled() {
        return autoHealEnabled;
    }

    /**
     * Get the target cluster size for auto-heal.
     */
    public int targetClusterSize() {
        return targetClusterSize;
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

    /**
     * Get the app HTTP port of the first node (for load generation).
     */
    public int getAppHttpPort() {
        return baseAppHttpPort;
    }

    private AetherNode createNode(NodeId nodeId, int port, int mgmtPort, int appHttpPort, List<NodeInfo> coreNodes) {
        var topology = new TopologyConfig(nodeId,
                                          coreNodes.size(),
                                          timeSpan(1).seconds(),
                                          timeSpan(10).seconds(),
                                          coreNodes);
        // Use forgeDefaults() for controller config - disables CPU-based scaling in simulation
        var config = new AetherNodeConfig(topology,
                                          ProtocolConfig.testConfig(),
                                          SliceActionConfig.defaultConfiguration(furySerializerFactoryProvider()),
                                          org.pragmatica.aether.config.SliceConfig.defaults(),
                                          mgmtPort,
                                          DHTConfig.FULL,
                                          Option.empty(),
                                          org.pragmatica.aether.config.TTMConfig.disabled(),
                                          RollbackConfig.defaults(),
                                          AppHttpConfig.enabledOnPort(appHttpPort),
                                          ControllerConfig.forgeDefaults());
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

    /**
     * Start rolling restart cycle.
     * Continuously kills random nodes and adds new ones to simulate rolling updates.
     */
    public Promise<RollingRestartResponse> startRollingRestart(Consumer<EventLogEntry> eventLogger) {
        if (rollingRestartActive.compareAndSet(false, true)) {
            eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Rolling restart started"));
            log.info("Starting rolling restart cycle");
            scheduleNextCycle(eventLogger);
            return Promise.success(new RollingRestartResponse(true, "Rolling restart started"));
        }
        return Promise.success(new RollingRestartResponse(false, "Rolling restart already active"));
    }

    private void scheduleNextCycle(Consumer<EventLogEntry> eventLogger) {
        if (!rollingRestartActive.get()) {
            return;
        }
        rollingRestartTask = rollingRestartExecutor.schedule(() -> performRollingRestartCycle(eventLogger),
                                                             ROLLING_RESTART_DELAY_MS,
                                                             TimeUnit.MILLISECONDS);
    }

    private void performRollingRestartCycle(Consumer<EventLogEntry> eventLogger) {
        if (!rollingRestartActive.get() || nodes.isEmpty()) {
            return;
        }
        // Pick random node to kill
        var nodeIds = new ArrayList<>(nodes.keySet());
        var targetNodeId = nodeIds.get(random.nextInt(nodeIds.size()));
        log.info("Rolling restart: killing node {}", targetNodeId);
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Killing node " + targetNodeId));
        if (autoHealEnabled) {
            // Auto-heal handles replacement, just kill and wait 2x delay to maintain pace
            killNode(targetNodeId).onSuccess(_ -> {
                                                 eventLogger.accept(new EventLogEntry("ROLLING_RESTART",
                                                                                      "Auto-heal will replace node"));
                                                 scheduleNextCycleWithDelay(eventLogger, ROLLING_RESTART_DELAY_MS * 2);
                                             })
                    .onFailure(cause -> handleRollingRestartFailure(eventLogger, "kill node", cause));
        } else {
            // Manual replacement: kill → wait → add
            killNode(targetNodeId).onSuccess(_ -> scheduleAddNode(eventLogger))
                    .onFailure(cause -> handleRollingRestartFailure(eventLogger, "kill node", cause));
        }
    }

    private void scheduleAddNode(Consumer<EventLogEntry> eventLogger) {
        rollingRestartExecutor.schedule(() -> {
                                            if (!rollingRestartActive.get()) {
                                                return;
                                            }
                                            log.info("Rolling restart: adding new node");
                                            eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Adding new node"));
                                            addNode().onSuccess(newNodeId -> {
                                                                    eventLogger.accept(new EventLogEntry("ROLLING_RESTART",
                                                                                                         "Added node " + newNodeId.id()));
                                                                    scheduleNextCycle(eventLogger);
                                                                })
                                                   .onFailure(cause -> handleRollingRestartFailure(eventLogger,
                                                                                                   "add node",
                                                                                                   cause));
                                        },
                                        ROLLING_RESTART_DELAY_MS,
                                        TimeUnit.MILLISECONDS);
    }

    private void scheduleNextCycleWithDelay(Consumer<EventLogEntry> eventLogger, long delayMs) {
        if (!rollingRestartActive.get()) {
            return;
        }
        rollingRestartTask = rollingRestartExecutor.schedule(() -> performRollingRestartCycle(eventLogger),
                                                             delayMs,
                                                             TimeUnit.MILLISECONDS);
    }

    private void handleRollingRestartFailure(Consumer<EventLogEntry> eventLogger, String operation, Cause cause) {
        log.error("Rolling restart: failed to {}: {}", operation, cause.message());
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART_ERROR", "Failed to " + operation + ": " + cause.message()));
        scheduleNextCycle(eventLogger);
    }

    /**
     * Stop rolling restart cycle.
     */
    public Promise<RollingRestartResponse> stopRollingRestart(Consumer<EventLogEntry> eventLogger) {
        if (rollingRestartActive.compareAndSet(true, false)) {
            if (rollingRestartTask != null) {
                rollingRestartTask.cancel(false);
                rollingRestartTask = null;
            }
            eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Rolling restart stopped"));
            log.info("Rolling restart stopped");
            return Promise.success(new RollingRestartResponse(true, "Rolling restart stopped"));
        }
        return Promise.success(new RollingRestartResponse(false, "Rolling restart not active"));
    }

    /**
     * Get rolling restart status.
     */
    public RollingRestartStatusResponse rollingRestartStatus() {
        return new RollingRestartStatusResponse(rollingRestartActive.get());
    }
}
