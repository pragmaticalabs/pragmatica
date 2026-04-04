package org.pragmatica.aether.ember;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.node.AetherNodeConfig.defaultSliceActionConfig;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;


/// Manages a cluster of AetherNodes for Ember.
/// Supports starting, stopping, adding, and killing nodes.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) public final class EmberCluster {
    private static final Logger log = LoggerFactory.getLogger(EmberCluster.class);

    public static final int DEFAULT_BASE_PORT = 6000;

    public static final int DEFAULT_BASE_MGMT_PORT = 6100;

    public static final int DEFAULT_BASE_APP_HTTP_PORT = 8070;

    private static final TimeSpan NODE_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    private static final long ROLLING_RESTART_DELAY_MS = 5_000;

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

    private final CancellableTask rollingRestartTask = CancellableTask.cancellableTask();

    private final Random random = new Random();

    private long lastTotalInvocations = 0;

    private long lastTotalSuccess = 0;

    private double emaRps = 0.0;

    private double emaSuccessRate = 1.0;

    private double emaAvgLatencyMs = 0.0;

    private static final double EMA_ALPHA = 0.2;

    private final int targetClusterSize;
    private final AtomicInteger effectiveSize;
    private final Option<ConfigurationProvider> configProvider;
    private final ObservabilityConfig observability;
    private final int coreMax;
    private final EnvironmentIntegration emberEnvironment;

    private final class EmberComputeProvider implements ComputeProvider {
        @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
            return addNode().map(nodeId -> toInstanceInfo(nodeId.id()));
        }

        @Override public Promise<Unit> terminate(InstanceId instanceId) {
            return killNode(instanceId.value());
        }

        @Override public Promise<List<InstanceInfo>> listInstances() {
            var infos = nodes.keySet().stream()
                                    .map(this::toInstanceInfo)
                                    .toList();
            return Promise.success(infos);
        }

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Option.option(nodes.get(instanceId.value())).map(_ -> toInstanceInfo(instanceId.value()))
                                .async(EnvironmentError.instanceNotFound(instanceId));
        }

        private InstanceInfo toInstanceInfo(String nodeIdStr) {
            var addresses = Option.option(nodeInfos.get(nodeIdStr)).map(info -> List.of("localhost:" + info.address()
                                                                                                                   .port()))
                                         .or(List.of());
            return new InstanceInfo(new InstanceId(nodeIdStr),
                                    InstanceStatus.RUNNING,
                                    addresses,
                                    InstanceType.ON_DEMAND,
                                    Map.of());
        }
    }

    private EmberCluster(int initialClusterSize,
                         int basePort,
                         int baseMgmtPort,
                         int baseAppHttpPort,
                         String nodeIdPrefix,
                         Option<ConfigurationProvider> configProvider,
                         ObservabilityConfig observability,
                         int coreMax) {
        this.initialClusterSize = initialClusterSize;
        this.basePort = basePort;
        this.baseMgmtPort = baseMgmtPort;
        this.baseAppHttpPort = baseAppHttpPort;
        this.nodeIdPrefix = nodeIdPrefix;
        this.targetClusterSize = initialClusterSize;
        this.effectiveSize = new AtomicInteger(initialClusterSize);
        this.configProvider = configProvider;
        this.observability = observability;
        this.coreMax = coreMax;
        this.emberEnvironment = EnvironmentIntegration.withCompute(new EmberComputeProvider());
    }

    public static EmberCluster emberCluster() {
        return emberCluster(5);
    }

    public static EmberCluster emberCluster(int initialSize) {
        return new EmberCluster(initialSize,
                                DEFAULT_BASE_PORT,
                                DEFAULT_BASE_MGMT_PORT,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                "node",
                                Option.empty(),
                                ObservabilityConfig.DEFAULT,
                                0);
    }

    public static EmberCluster emberCluster(int initialSize, int basePort, int baseMgmtPort) {
        return new EmberCluster(initialSize,
                                basePort,
                                baseMgmtPort,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                "node",
                                Option.empty(),
                                ObservabilityConfig.DEFAULT,
                                0);
    }

    public static EmberCluster emberCluster(int initialSize, int basePort, int baseMgmtPort, String nodeIdPrefix) {
        return new EmberCluster(initialSize,
                                basePort,
                                baseMgmtPort,
                                DEFAULT_BASE_APP_HTTP_PORT,
                                nodeIdPrefix,
                                Option.empty(),
                                ObservabilityConfig.DEFAULT,
                                0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix) {
        return emberCluster(initialSize,
                            basePort,
                            baseMgmtPort,
                            baseAppHttpPort,
                            nodeIdPrefix,
                            Option.empty(),
                            ObservabilityConfig.DEFAULT,
                            0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            Option<ConfigurationProvider> configProvider) {
        return emberCluster(initialSize,
                            basePort,
                            baseMgmtPort,
                            baseAppHttpPort,
                            nodeIdPrefix,
                            configProvider,
                            ObservabilityConfig.DEFAULT,
                            0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            Option<ConfigurationProvider> configProvider,
                                            ObservabilityConfig observability) {
        return emberCluster(initialSize,
                            basePort,
                            baseMgmtPort,
                            baseAppHttpPort,
                            nodeIdPrefix,
                            configProvider,
                            observability,
                            0);
    }

    public static EmberCluster emberCluster(int initialSize,
                                            int basePort,
                                            int baseMgmtPort,
                                            int baseAppHttpPort,
                                            String nodeIdPrefix,
                                            Option<ConfigurationProvider> configProvider,
                                            ObservabilityConfig observability,
                                            int coreMax) {
        return new EmberCluster(initialSize,
                                basePort,
                                baseMgmtPort,
                                baseAppHttpPort,
                                nodeIdPrefix,
                                configProvider,
                                observability,
                                coreMax);
    }

    public Promise<Unit> start() {
        log.info("Starting Ember cluster with {} nodes on ports {}-{}",
                 initialClusterSize,
                 basePort,
                 basePort + initialClusterSize - 1);
        int poolSize = 2 * targetClusterSize;
        availableSlots.clear();
        for (int i = 0;i <poolSize;i++) {availableSlots.offer(i);}
        var initialNodes = new ArrayList<NodeInfo>();
        for (int i = 1;i <= initialClusterSize;i++) {
            var slot = availableSlots.poll();
            var nodeId = nodeId(nodeIdPrefix + "-" + i).unwrap();
            var port = basePort + slot;
            var info = NodeInfo.nodeInfo(nodeId, nodeAddress("localhost", port).unwrap());
            initialNodes.add(info);
            nodeInfos.put(nodeId.id(), info);
            slotsByNodeId.put(nodeId.id(), slot);
        }
        nodeCounter.set(initialClusterSize);
        var startPromises = new ArrayList<Promise<NodeStartResult>>();
        for (int i = 0;i <initialClusterSize;i++) {
            var nodeInfo = initialNodes.get(i);
            var nodeIdStr = nodeInfo.id().id();
            var slot = slotsByNodeId.get(nodeIdStr);
            var port = basePort + slot;
            var mgmtPort = baseMgmtPort + slot;
            var appHttpPort = baseAppHttpPort + slot;
            var node = createNode(nodeInfo.id(), port, mgmtPort, appHttpPort, initialNodes, false);
            nodes.put(nodeIdStr, node);
            startPromises.add(node.start().map(_ -> NodeStartResult.nodeStartResult(nodeIdStr,
                                                                                    port,
                                                                                    mgmtPort,
                                                                                    Option.none()))
                                        .recover(cause -> NodeStartResult.nodeStartResult(nodeIdStr,
                                                                                          port,
                                                                                          mgmtPort,
                                                                                          Option.some(cause))));
        }
        return Promise.allOf(startPromises).flatMap(this::handleStartResults);
    }

    private record NodeStartResult(String nodeId, int port, int mgmtPort, Option<Cause> failure) {
        static NodeStartResult nodeStartResult(String nodeId, int port, int mgmtPort, Option<Cause> failure) {
            return new NodeStartResult(nodeId, port, mgmtPort, failure);
        }

        boolean succeeded() {
            return failure.isEmpty();
        }
    }

    private Promise<Unit> handleStartResults(List<Result<NodeStartResult>> results) {
        var nodeResults = results.stream().flatMap(Result::stream)
                                        .toList();
        var failed = nodeResults.stream().filter(r -> !r.succeeded())
                                       .toList();
        var succeeded = nodeResults.stream().filter(NodeStartResult::succeeded)
                                          .toList();
        if (failed.isEmpty()) {
            log.info("All nodes started, waiting for cluster stabilization...");
            return Promise.promise(timeSpan(2).seconds(),
                                   () -> Result.success(Unit.unit()))
            .onSuccess(_ -> log.info("Ember cluster started with {} nodes", initialClusterSize));
        }
        for (var f : failed) {f.failure()
                                       .onPresent(cause -> log.error("Node {} failed to start on port {} (mgmt: {}): {}",
                                                                     f.nodeId(),
                                                                     f.port(),
                                                                     f.mgmtPort(),
                                                                     cause.message()));}
        log.error("Cluster startup failed: {} of {} nodes failed to start", failed.size(), initialClusterSize);
        var stopPromises = succeeded.stream().map(r -> Option.option(nodes.get(r.nodeId())).map(node -> node.stop().timeout(NODE_TIMEOUT)
                                                                                                                 .recover(_ -> Unit.unit()))
                                                                    .or(Promise.success(Unit.unit())))
                                           .toList();
        return Promise.allOf(stopPromises).mapToUnit()
                            .onSuccess(this::clearClusterStateOnFailure)
                            .flatMap(_ -> failed.getFirst().failure()
                                                         .<Promise<Unit>>map(Cause::promise)
                                                         .or(Promise.success(Unit.unit())));
    }

    private void clearClusterStateOnFailure(Unit unit) {
        nodes.clear();
        nodeInfos.clear();
        slotsByNodeId.clear();
        availableSlots.clear();
        nodeCounter.set(0);
    }

    public Promise<Unit> stop() {
        log.info("Stopping Ember cluster");
        rollingRestartTask.cancel();
        rollingRestartActive.set(false);
        var stopPromises = nodes.values().stream()
                                       .map(node -> node.stop().timeout(NODE_TIMEOUT))
                                       .toList();
        return Promise.allOf(stopPromises).map(_ -> Unit.unit())
                            .onSuccess(this::clearClusterState);
    }

    private void clearClusterState(Unit unit) {
        nodes.clear();
        nodeInfos.clear();
        slotsByNodeId.clear();
        availableSlots.clear();
        log.info("Ember cluster stopped");
    }

    public Promise<NodeId> addNode() {
        var slotOpt = Option.option(availableSlots.poll());
        if (slotOpt.isEmpty()) {
            log.warn("Slot pool exhausted — no available ports for new node");
            return EnvironmentError.operationNotSupported("No available port slots for new node").promise();
        }
        var slot = slotOpt.unwrap();
        var nodeNum = nodeCounter.incrementAndGet();
        var nodeId = nodeId(nodeIdPrefix + "-" + nodeNum).unwrap();
        var port = basePort + slot;
        var mgmtPort = baseMgmtPort + slot;
        var appHttpPort = baseAppHttpPort + slot;
        var info = NodeInfo.nodeInfo(nodeId, nodeAddress("localhost", port).unwrap());
        log.info("Adding new node {} on port {}", nodeId.id(), port);
        slotsByNodeId.put(nodeId.id(), slot);
        nodeInfos.put(nodeId.id(), info);
        var allNodes = new ArrayList<>(nodeInfos.values());
        var node = createNode(nodeId, port, mgmtPort, appHttpPort, allNodes, false);
        nodes.put(nodeId.id(), node);
        return node.start().map(_ -> nodeId)
                         .onSuccess(_ -> log.info("Node {} joined the cluster",
                                                  nodeId.id()));
    }

    public Promise<Unit> killNode(String nodeIdStr) {
        return killNode(nodeIdStr, true);
    }

    public Promise<Unit> killNode(String nodeIdStr, boolean graceful) {
        return Option.option(nodes.get(nodeIdStr)).map(node -> killNodeInternal(nodeIdStr, node, graceful))
                            .or(() -> nodeNotFound(nodeIdStr));
    }

    private Promise<Unit> nodeNotFound(String nodeIdStr) {
        log.warn("Node {} not found", nodeIdStr);
        return Promise.success(Unit.unit());
    }

    private Promise<Unit> killNodeInternal(String nodeIdStr, AetherNode node, boolean graceful) {
        var timeout = graceful
                     ? NODE_TIMEOUT
                     : TimeSpan.timeSpan(1).seconds();
        log.info("{} node {}", graceful
                              ? "Stopping"
                              : "Force-killing", nodeIdStr);
        nodes.remove(nodeIdStr);
        nodeInfos.remove(nodeIdStr);
        var slotOpt = Option.option(slotsByNodeId.remove(nodeIdStr));
        return node.stop().timeout(timeout)
                        .recover(_ -> Unit.unit())
                        .onSuccess(_ -> slotOpt.onPresent(availableSlots::offer))
                        .onSuccess(_ -> log.info("Node {} removed from cluster", nodeIdStr));
    }

    public int targetClusterSize() {
        return targetClusterSize;
    }

    public void setClusterSize(int newSize) {
        effectiveSize.set(newSize);
        var message = new TopologyManagementMessage.SetClusterSize(newSize);
        nodes.values().forEach(node -> node.route(message));
        log.info("SetClusterSize({}) routed to {} nodes", newSize, nodes.size());
    }

    public int effectiveClusterSize() {
        return effectiveSize.get();
    }

    public Option<String> currentLeader() {
        return Option.option(nodes.values().stream()
                                         .findFirst()
                                         .orElse(null)).flatMap(AetherNode::leader)
                            .map(NodeId::id);
    }

    public ClusterStatus status() {
        var nodeStatuses = nodes.entrySet().stream()
                                         .map(this::toNodeStatus)
                                         .toList();
        return new ClusterStatus(nodeStatuses, currentLeader().or("none"));
    }

    private NodeStatus toNodeStatus(Map.Entry<String, AetherNode> entry) {
        var clusterPort = nodeInfos.get(entry.getKey()).address()
                                       .port();
        return new NodeStatus(entry.getKey(),
                              clusterPort,
                              baseMgmtPort + (clusterPort - basePort),
                              "healthy",
                              currentLeader().map(leaderId -> leaderId.equals(entry.getKey())).or(false));
    }

    public Option<AetherNode> getNode(String nodeIdStr) {
        return Option.option(nodes.get(nodeIdStr));
    }

    public List<AetherNode> allNodes() {
        return new ArrayList<>(nodes.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public Option<Integer> getLeaderManagementPort() {
        return currentLeader().flatMap(leaderId -> Option.option(nodeInfos.get(leaderId)))
                            .map(info -> baseMgmtPort + (info.address().port() - basePort));
    }

    public int getAppHttpPort() {
        return baseAppHttpPort;
    }

    public List<NodeInfo> getNodeInfos() {
        return List.copyOf(nodeInfos.values());
    }

    public List<Integer> getAvailableAppHttpPorts() {
        return nodes.entrySet().stream()
                             .filter(entry -> entry.getValue().appHttpServer()
                                                            .isRouteReady())
                             .map(entry -> slotsByNodeId.get(entry.getKey()))
                             .filter(slot -> slot != null)
                             .map(slot -> baseAppHttpPort + slot)
                             .sorted()
                             .toList();
    }

    private AetherNode createNode(NodeId nodeId,
                                  int port,
                                  int mgmtPort,
                                  int appHttpPort,
                                  List<NodeInfo> coreNodes,
                                  boolean activationGated) {
        var topology = new TopologyConfig(nodeId,
                                          targetClusterSize,
                                          timeSpan(1).seconds(),
                                          timeSpan(10).seconds(),
                                          TopologyConfig.DEFAULT_HELLO_TIMEOUT,
                                          coreNodes,
                                          Option.empty(),
                                          org.pragmatica.consensus.topology.BackoffConfig.DEFAULT,
                                          coreMax,
                                          targetClusterSize);
        var config = new AetherNodeConfig(topology,
                                          ProtocolConfig.testConfig(),
                                          defaultSliceActionConfig(),
                                          org.pragmatica.aether.config.SliceConfig.sliceConfig(),
                                          mgmtPort,
                                          DHTConfig.FULL,
                                          DHTConfig.CACHE_DEFAULT,
                                          Option.empty(),
                                          org.pragmatica.aether.config.TtmConfig.ttmConfig(),
                                          RollbackConfig.rollbackConfig(),
                                          AppHttpConfig.appHttpConfig(appHttpPort),
                                          ControllerConfig.forgeDefaults(),
                                          configProvider,
                                          Option.some(emberEnvironment),
                                          AutoHealConfig.DEFAULT,
                                          observability,
                                          ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                          activationGated,
                                          org.pragmatica.aether.config.TimeoutsConfig.timeoutsConfig(),
                                          Option.empty(),
                                          Option.empty(),
                                          AetherNodeConfig.DeploymentDefaults.DEFAULT,
                                          org.pragmatica.aether.config.HttpProtocol.H1,
                                          java.util.Map.of(),
                                          Option.empty());
        return AetherNode.aetherNode(config).unwrap();
    }

    public List<NodeMetrics> nodeMetrics() {
        var leaderId = currentLeader().or("");
        var leaderNode = nodes.get(leaderId);
        if (leaderNode == null) {
            if (nodes.isEmpty()) {return List.of();}
            leaderNode = nodes.values().iterator()
                                     .next();
        }
        var allMetrics = leaderNode.metricsCollector().allMetrics();
        return allMetrics.entrySet().stream()
                                  .map(entry -> toNodeMetrics(entry.getKey().id(),
                                                              entry.getValue(),
                                                              leaderId))
                                  .toList();
    }

    public AetherAggregates aetherAggregates() {
        var leaderId = currentLeader().or("");
        var leaderNode = nodes.get(leaderId);
        if (leaderNode == null) {
            if (nodes.isEmpty()) {return new AetherAggregates(0, 1.0, 0, 0, 0, 0);}
            leaderNode = nodes.values().iterator()
                                     .next();
        }
        var allNodeMetrics = leaderNode.metricsCollector().allMetrics();
        long totalInvocations = 0;
        long totalSuccess = 0;
        long totalFailure = 0;
        double totalDurationNs = 0.0;
        for (var nodeMetrics : allNodeMetrics.values()) {for (var entry : nodeMetrics.entrySet()) {
            var key = entry.getKey();
            if (!key.startsWith("inv|")) {continue;}
            if (key.endsWith("|count")) {totalInvocations += entry.getValue().longValue();} else if (key.endsWith("|success")) {totalSuccess += entry.getValue()
                                                                                                                                                              .longValue();} else if (key.endsWith("|failure")) {totalFailure += entry.getValue()
                                                                                                                                                                                                                                               .longValue();} else if (key.endsWith("|totalNs")) {totalDurationNs += entry.getValue();}
        }}
        long deltaInvocations = Math.max(0, totalInvocations - lastTotalInvocations);
        long deltaSuccess = Math.max(0, totalSuccess - lastTotalSuccess);
        double instantRps = deltaInvocations;
        double instantSuccessRate = deltaInvocations > 0
                                   ? (double) deltaSuccess / deltaInvocations
                                   : 1.0;
        double avgLatencyMs = totalInvocations > 0
                             ? totalDurationNs / totalInvocations / 1_000_000.0
                             : 0.0;
        emaRps = EMA_ALPHA * instantRps + (1 - EMA_ALPHA) * emaRps;
        emaSuccessRate = EMA_ALPHA * instantSuccessRate + (1 - EMA_ALPHA) * emaSuccessRate;
        emaAvgLatencyMs = EMA_ALPHA * avgLatencyMs + (1 - EMA_ALPHA) * emaAvgLatencyMs;
        lastTotalInvocations = totalInvocations;
        lastTotalSuccess = totalSuccess;
        return new AetherAggregates(emaRps,
                                    emaSuccessRate * 100.0,
                                    emaAvgLatencyMs,
                                    totalInvocations,
                                    totalSuccess,
                                    totalFailure);
    }

    public List<InvocationDetail> invocationDetails() {
        var allNodeMetrics = leaderOrFirstNodeMetrics();
        if (allNodeMetrics.isEmpty()) {return List.of();}
        var aggregated = new HashMap<String, long[]>();
        for (var nodeMetrics : allNodeMetrics.values()) {for (var entry : nodeMetrics.entrySet()) {
            var key = entry.getKey();
            if (!key.startsWith("inv|")) {continue;}
            var parts = key.split("\\|");
            if (parts.length != 4) {continue;}
            var compositeKey = parts[1] + "|" + parts[2];
            var values = aggregated.computeIfAbsent(compositeKey, _ -> new long[4]);
            accumulateInvocationMetric(values, parts[3], entry.getValue());
        }}
        return aggregated.entrySet().stream()
                                  .map(EmberCluster::toInvocationDetail)
                                  .toList();
    }

    private static void accumulateInvocationMetric(long[] values, String suffix, double value) {
        switch (suffix){
            case "count" -> values[0] += (long) value;
            case "success" -> values[1] += (long) value;
            case "failure" -> values[2] += (long) value;
            case "totalNs" -> values[3] += (long) value;
            default -> {}
        }
    }

    private static InvocationDetail toInvocationDetail(Map.Entry<String, long[]> entry) {
        var parts = entry.getKey().split("\\|", 2);
        var values = entry.getValue();
        var count = values[0];
        var avgMs = count > 0
                   ? (double) values[3] / count / 1_000_000.0
                   : 0.0;
        return new InvocationDetail(parts[0], parts[1], count, values[1], values[2], avgMs);
    }

    private Map<org.pragmatica.consensus.NodeId, Map<String, Double>> leaderOrFirstNodeMetrics() {
        var leaderId = currentLeader().or("");
        var leaderNode = nodes.get(leaderId);
        if (leaderNode == null) {
            if (nodes.isEmpty()) {return Map.of();}
            leaderNode = nodes.values().iterator()
                                     .next();
        }
        return leaderNode.metricsCollector().allMetrics();
    }

    private NodeMetrics toNodeMetrics(String nodeId, Map<String, Double> metrics, String leaderId) {
        var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
        var heapUsed = metrics.getOrDefault("heap.used", 0.0);
        var heapMax = metrics.getOrDefault("heap.max", 1.0);
        return new NodeMetrics(nodeId,
                               leaderId.equals(nodeId),
                               cpuUsage,
                               (long)(heapUsed / 1024 / 1024),
                               (long)(heapMax / 1024 / 1024));
    }

    public record NodeStatus(String id, int port, int mgmtPort, String state, boolean isLeader){}

    public record ClusterStatus(List<NodeStatus> nodes, String leaderId){}

    public record NodeMetrics(String nodeId, boolean isLeader, double cpuUsage, long heapUsedMb, long heapMaxMb){}

    public record SliceStatus(String artifact, String state, List<SliceInstanceStatus> instances){}

    public record SliceInstanceStatus(String nodeId, String state, String health){}

    public record EventLogEntry(String type, String message){}

    public record RollingRestartResponse(boolean success, String message){}

    public record RollingRestartStatusResponse(boolean active){}

    public record AetherAggregates(double rps,
                                   double successRate,
                                   double avgLatencyMs,
                                   long totalInvocations,
                                   long totalSuccess,
                                   long totalFailures){}

    public record InvocationDetail(String artifact,
                                   String method,
                                   long count,
                                   long successCount,
                                   long failureCount,
                                   double avgLatencyMs){}

    public List<SliceStatus> slicesStatus() {
        if (nodes.isEmpty()) {return List.of();}
        var node = nodes.values().iterator()
                               .next();
        return node.deploymentMap().allDeployments()
                                 .stream()
                                 .map(info -> new SliceStatus(info.artifact(),
                                                              info.aggregateState().name(),
                                                              info.instances().stream()
                                                                            .map(i -> new SliceInstanceStatus(i.nodeId(),
                                                                                                              i.state()
                                                                                                                     .name(),
                                                                                                              i.state() == SliceState.ACTIVE
                                                                                                              ? "HEALTHY"
                                                                                                              : "UNHEALTHY"))
                                                                            .toList()))
                                 .toList();
    }

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
        if (!rollingRestartActive.get()) {return;}
        rollingRestartTask.set(rollingRestartExecutor.schedule(() -> performRollingRestartCycle(eventLogger),
                                                               ROLLING_RESTART_DELAY_MS,
                                                               TimeUnit.MILLISECONDS));
    }

    private void performRollingRestartCycle(Consumer<EventLogEntry> eventLogger) {
        if (!rollingRestartActive.get() || nodes.isEmpty()) {return;}
        var nodeIds = new ArrayList<>(nodes.keySet());
        var targetNodeId = nodeIds.get(random.nextInt(nodeIds.size()));
        log.info("Rolling restart: killing node {}", targetNodeId);
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Killing node " + targetNodeId));
        killNode(targetNodeId).onSuccess(_ -> {
                                             eventLogger.accept(new EventLogEntry("ROLLING_RESTART",
                                                                                  "CDM auto-heal will replace node"));
                                             scheduleNextCycleWithDelay(eventLogger, ROLLING_RESTART_DELAY_MS * 2);
                                         })
                .onFailure(cause -> handleRollingRestartFailure(eventLogger, "kill node", cause));
    }

    private void scheduleNextCycleWithDelay(Consumer<EventLogEntry> eventLogger, long delayMs) {
        if (!rollingRestartActive.get()) {return;}
        rollingRestartTask.set(rollingRestartExecutor.schedule(() -> performRollingRestartCycle(eventLogger),
                                                               delayMs,
                                                               TimeUnit.MILLISECONDS));
    }

    private void handleRollingRestartFailure(Consumer<EventLogEntry> eventLogger, String operation, Cause cause) {
        log.error("Rolling restart: failed to {}: {}", operation, cause.message());
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART_ERROR", "Failed to " + operation + ": " + cause.message()));
        scheduleNextCycle(eventLogger);
    }

    public Promise<RollingRestartResponse> stopRollingRestart(Consumer<EventLogEntry> eventLogger) {
        if (rollingRestartActive.compareAndSet(true, false)) {
            rollingRestartTask.cancel();
            eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Rolling restart stopped"));
            log.info("Rolling restart stopped");
            return Promise.success(new RollingRestartResponse(true, "Rolling restart stopped"));
        }
        return Promise.success(new RollingRestartResponse(false, "Rolling restart not active"));
    }

    public RollingRestartStatusResponse rollingRestartStatus() {
        return new RollingRestartStatusResponse(rollingRestartActive.get());
    }
}
