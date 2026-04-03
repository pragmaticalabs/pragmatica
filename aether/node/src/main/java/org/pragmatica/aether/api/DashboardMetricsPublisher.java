package org.pragmatica.aether.api;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.DeploymentMap.SliceDeploymentInfo;
import org.pragmatica.aether.deployment.DeploymentMap.SliceInstanceInfo;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.topology.TopologyGraph;
import org.pragmatica.aether.slice.topology.TopologyParser;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Publishes dashboard metrics via WebSocket at regular intervals.
///
///
/// Aggregates metrics from various collectors and broadcasts to all connected clients.
@SuppressWarnings("JBCT-RET-01") public class DashboardMetricsPublisher {
    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsPublisher.class);

    private static final long DEFAULT_BROADCAST_INTERVAL_MS = 1000;

    private static final double EMA_ALPHA = 0.2;

    private final long broadcastIntervalMs;
    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;

    private final AtomicReference<Option<ScheduledFuture<?>>> scheduledTask = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicReference<EmaState> emaState = new AtomicReference<>(EmaState.INITIAL);

    private record EmaState(long lastTotalInvocations,
                            long lastTotalSuccess,
                            long lastTotalFailure,
                            double emaRps,
                            double emaSuccessRate,
                            double emaErrorRate,
                            double emaAvgLatencyMs) {
        static final EmaState INITIAL = new EmaState(0, 0, 0, 0.0, 1.0, 0.0, 0.0);
    }

    private DashboardMetricsPublisher(Supplier<AetherNode> nodeSupplier,
                                      AlertManager alertManager,
                                      long broadcastIntervalMs) {
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.broadcastIntervalMs = broadcastIntervalMs;
    }

    public static DashboardMetricsPublisher dashboardMetricsPublisher(Supplier<AetherNode> nodeSupplier,
                                                                      AlertManager alertManager) {
        return new DashboardMetricsPublisher(nodeSupplier, alertManager, DEFAULT_BROADCAST_INTERVAL_MS);
    }

    public static DashboardMetricsPublisher dashboardMetricsPublisher(Supplier<AetherNode> nodeSupplier,
                                                                      AlertManager alertManager,
                                                                      long broadcastIntervalMs) {
        return new DashboardMetricsPublisher(nodeSupplier, alertManager, broadcastIntervalMs);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {return;}
        scheduledTask.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::publishMetrics,
                                                                          TimeSpan.timeSpan(broadcastIntervalMs)
                                                                                           .millis())));
        log.info("Dashboard metrics publisher started");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {return;}
        scheduledTask.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        log.info("Dashboard metrics publisher stopped");
    }

    @SuppressWarnings("JBCT-EX-01") private void publishMetrics() {
        if (DashboardWebSocketHandler.connectedClients() == 0) {return;}
        try {
            var message = buildMetricsUpdate();
            DashboardWebSocketHandler.broadcast(message);
            checkAndBroadcastAlerts();
        } catch (Exception e) {
            log.error("Error publishing metrics", e);
        }
    }

    private void checkAndBroadcastAlerts() {
        var node = nodeSupplier.get();
        var allMetrics = node.metricsCollector().allMetrics();
        for (var entry : allMetrics.entrySet()) {
            var nodeId = entry.getKey();
            var metrics = entry.getValue();
            for (var metric : metrics.entrySet()) {alertManager.checkThreshold(metric.getKey(),
                                                                               nodeId,
                                                                               metric.getValue())
            .onPresent(DashboardWebSocketHandler::broadcast);}
        }
    }

    public String buildInitialState() {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        sb.append("{\"type\":\"INITIAL_STATE\",\"timestamp\":").append(System.currentTimeMillis())
                 .append(",\"data\":{");
        var allMetrics = node.metricsCollector().allMetrics();
        var currentTopology = new HashSet<>(node.topologyManager().topology());
        var sortedNodes = allMetrics.keySet().stream()
                                           .filter(currentTopology::contains)
                                           .sorted((a, b) -> a.id().compareTo(b.id()))
                                           .collect(Collectors.toList());
        var leaderId = sortedNodes.isEmpty()
                      ? ""
                      : sortedNodes.get(0).id();
        var coreNodeIds = node.initialTopology();
        sb.append("\"nodes\":[");
        boolean firstNode = true;
        for (var nodeId : sortedNodes) {
            if (!firstNode) sb.append(",");
            sb.append("{\"id\":\"").append(nodeId.id())
                     .append("\",");
            sb.append("\"isLeader\":").append(nodeId.id().equals(leaderId))
                     .append("}");
            firstNode = false;
        }
        sb.append("],");
        appendNodeMetrics(sb, node, allMetrics, sortedNodes, leaderId, coreNodeIds);
        sb.append(",");
        var deployments = node.deploymentMap().allDeployments();
        sb.append("\"slices\":[");
        boolean firstSlice = true;
        for (var deployment : deployments) {
            if (!firstSlice) sb.append(",");
            sb.append("\"").append(deployment.artifact())
                     .append("\"");
            firstSlice = false;
        }
        sb.append("],");
        appendDeployments(sb, deployments);
        sb.append(",");
        sb.append("\"thresholds\":").append(alertManager.thresholdsAsJson())
                 .append(",");
        appendTopology(sb, node);
        sb.append(",");
        appendSchema(sb, node);
        sb.append(",");
        appendGovernors(sb, node);
        sb.append(",");
        appendClusterTopology(sb, node);
        sb.append(",");
        appendStrategies(sb, node);
        sb.append(",");
        appendStreams(sb, node);
        sb.append(",");
        appendRoutes(sb, node);
        sb.append(",");
        sb.append("\"metrics\":").append(buildMetricsData());
        sb.append("}}");
        return sb.toString();
    }

    private String buildMetricsUpdate() {
        var sb = new StringBuilder();
        sb.append("{\"type\":\"METRICS_UPDATE\",\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"data\":").append(buildMetricsData())
                 .append("}");
        return sb.toString();
    }

    private String buildMetricsData() {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        sb.append("{");
        var allMetrics = node.metricsCollector().allMetrics();
        var coreNodeIdSet = new HashSet<>(node.initialTopology());
        var perNodeInvocations = aggregateInvocationsByNode(node);
        sb.append("\"load\":{");
        boolean firstNode = true;
        for (var entry : allMetrics.entrySet()) {
            if (!firstNode) sb.append(",");
            var nodeId = entry.getKey();
            var role = coreNodeIdSet.contains(nodeId)
                      ? "CORE"
                      : "WORKER";
            var invocData = perNodeInvocations.getOrDefault(nodeId.id(), PerNodeInvocationData.EMPTY);
            sb.append("\"").append(nodeId.id())
                     .append("\":{");
            boolean firstMetric = true;
            for (var metric : entry.getValue().entrySet()) {
                if (!firstMetric) sb.append(",");
                sb.append("\"").append(metric.getKey())
                         .append("\":")
                         .append(metric.getValue());
                firstMetric = false;
            }
            sb.append(",\"role\":\"").append(role)
                     .append("\"");
            sb.append(",\"rps\":").append(String.format("%.2f", invocData.rps));
            sb.append(",\"avgLatencyMs\":").append(String.format("%.2f", invocData.avgLatencyMs));
            sb.append(",\"successRate\":").append(String.format("%.4f", invocData.successRate));
            sb.append("}");
            firstNode = false;
        }
        sb.append("},");
        sb.append("\"invocations\":").append(buildInvocationMetrics())
                 .append(",");
        appendDeployments(sb,
                          node.deploymentMap().allDeployments());
        sb.append(",\"aggregates\":").append(buildAggregates())
                 .append(",");
        appendTopology(sb, node);
        sb.append(",");
        appendSchema(sb, node);
        sb.append(",");
        appendGovernors(sb, node);
        sb.append(",");
        appendClusterTopology(sb, node);
        sb.append(",");
        appendStrategies(sb, node);
        sb.append(",");
        appendStreams(sb, node);
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("JBCT-PAT-01") private String buildAggregates() {
        var rawTotals = computeRawTotals();
        var percentiles = computePercentiles(rawTotals.allSamples, rawTotals.totalSamples);
        var newEma = computeEma(rawTotals);
        emaState.set(newEma);
        return formatAggregates(newEma, percentiles);
    }

    private record RawTotals(long totalInvocations,
                             long totalSuccess,
                             long totalFailure,
                             double weightedLatency,
                             long totalSamples,
                             List<long[]> allSamples){}

    private RawTotals computeRawTotals() {
        var node = nodeSupplier.get();
        var snapshots = node.invocationMetrics().snapshot();
        long totalInvocations = 0;
        long totalSuccess = 0;
        long totalFailure = 0;
        double weightedLatency = 0.0;
        long totalSamples = 0;
        var allSamples = new ArrayList<long[]>();
        for (var snapshot : snapshots) {
            var metrics = snapshot.metrics();
            totalInvocations += metrics.count();
            totalSuccess += metrics.successCount();
            totalFailure += metrics.failureCount();
            weightedLatency += metrics.averageLatencyNs() / 1_000_000.0 * metrics.count();
            if (metrics.latencySamples().length > 0) {
                allSamples.add(metrics.latencySamples());
                totalSamples += metrics.latencySamples().length;
            }
        }
        return new RawTotals(totalInvocations, totalSuccess, totalFailure, weightedLatency, totalSamples, allSamples);
    }

    private EmaState computeEma(RawTotals totals) {
        var prev = emaState.get();
        long deltaInvocations = Math.max(0, totals.totalInvocations - prev.lastTotalInvocations);
        long deltaSuccess = Math.max(0, totals.totalSuccess - prev.lastTotalSuccess);
        double instantRps = deltaInvocations / (broadcastIntervalMs / 1000.0);
        double instantSuccessRate = deltaInvocations > 0
                                   ? (double) deltaSuccess / deltaInvocations
                                   : 1.0;
        double instantErrorRate = 1.0 - instantSuccessRate;
        double avgLatencyMs = totals.totalInvocations > 0
                             ? totals.weightedLatency / totals.totalInvocations
                             : 0.0;
        return new EmaState(totals.totalInvocations,
                            totals.totalSuccess,
                            totals.totalFailure,
                            EMA_ALPHA * instantRps + (1 - EMA_ALPHA) * prev.emaRps,
                            EMA_ALPHA * instantSuccessRate + (1 - EMA_ALPHA) * prev.emaSuccessRate,
                            EMA_ALPHA * instantErrorRate + (1 - EMA_ALPHA) * prev.emaErrorRate,
                            EMA_ALPHA * avgLatencyMs + (1 - EMA_ALPHA) * prev.emaAvgLatencyMs);
    }

    private static String formatAggregates(EmaState ema, double[] percentiles) {
        return String.format("{\"rps\":%.2f,\"successRate\":%.4f,\"errorRate\":%.4f,\"avgLatencyMs\":%.2f," + "\"p50\":%.2f,\"p95\":%.2f,\"p99\":%.2f}",
                             ema.emaRps,
                             ema.emaSuccessRate,
                             ema.emaErrorRate,
                             ema.emaAvgLatencyMs,
                             percentiles[0],
                             percentiles[1],
                             percentiles[2]);
    }

    private double[] computePercentiles(List<long[]> allSamples, long totalSamples) {
        if (totalSamples == 0) {return new double[]{0.0, 0.0, 0.0};}
        var merged = new long[(int) totalSamples];
        int offset = 0;
        for (var samples : allSamples) {
            System.arraycopy(samples, 0, merged, offset, samples.length);
            offset += samples.length;
        }
        java.util.Arrays.sort(merged);
        var p50Ns = merged[Math.min((int)(0.50 * merged.length), merged.length - 1)];
        var p95Ns = merged[Math.min((int)(0.95 * merged.length), merged.length - 1)];
        var p99Ns = merged[Math.min((int)(0.99 * merged.length), merged.length - 1)];
        return new double[]{p50Ns / 1_000_000.0, p95Ns / 1_000_000.0, p99Ns / 1_000_000.0};
    }

    private String buildInvocationMetrics() {
        var node = nodeSupplier.get();
        var snapshots = node.invocationMetrics().snapshot();
        if (snapshots.isEmpty()) {return "[]";}
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var snapshot : snapshots) {
            if (!first) sb.append(",");
            first = false;
            var metrics = snapshot.metrics();
            sb.append("{\"artifact\":\"").append(snapshot.artifact().asString())
                     .append("\",\"method\":\"")
                     .append(snapshot.methodName().name())
                     .append("\",\"count\":")
                     .append(metrics.count())
                     .append(",\"successCount\":")
                     .append(metrics.successCount())
                     .append(",\"failureCount\":")
                     .append(metrics.failureCount())
                     .append(",\"avgDurationMs\":")
                     .append(String.format("%.2f",
                                           metrics.averageLatencyNs() / 1_000_000.0))
                     .append(",\"errorRate\":")
                     .append(String.format("%.4f",
                                           1.0 - metrics.successRate()))
                     .append(",\"slowCalls\":")
                     .append(snapshot.slowInvocations().size())
                     .append(",\"p50\":")
                     .append(String.format("%.2f",
                                           metrics.p50() / 1_000_000.0))
                     .append(",\"p95\":")
                     .append(String.format("%.2f",
                                           metrics.p95() / 1_000_000.0))
                     .append(",\"p99\":")
                     .append(String.format("%.2f",
                                           metrics.p99() / 1_000_000.0))
                     .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("JBCT-PAT-01") private void appendNodeMetrics(StringBuilder sb,
                                                                    AetherNode node,
                                                                    Map<NodeId, Map<String, Double>> allMetrics,
                                                                    List<NodeId> sortedNodes,
                                                                    String leaderId,
                                                                    List<NodeId> coreNodeIds) {
        var coreNodeIdSet = new HashSet<>(coreNodeIds);
        var perNodeInvocations = aggregateInvocationsByNode(node);
        sb.append("\"nodeMetrics\":[");
        boolean first = true;
        for (var nodeId : sortedNodes) {
            if (!first) sb.append(",");
            var metrics = allMetrics.getOrDefault(nodeId, Map.of());
            var role = coreNodeIdSet.contains(nodeId)
                      ? "CORE"
                      : "WORKER";
            appendSingleNodeMetrics(sb,
                                    nodeId,
                                    metrics,
                                    leaderId,
                                    role,
                                    perNodeInvocations.getOrDefault(nodeId.id(), PerNodeInvocationData.EMPTY));
            first = false;
        }
        sb.append("]");
    }

    private void appendSingleNodeMetrics(StringBuilder sb,
                                         NodeId nodeId,
                                         Map<String, Double> metrics,
                                         String leaderId,
                                         String role,
                                         PerNodeInvocationData invocData) {
        var cpuUsage = metrics.getOrDefault("cpu.usage", 0.0);
        var heapUsed = metrics.getOrDefault("heap.used", 0.0);
        var heapMax = metrics.getOrDefault("heap.max", 0.0);
        var heapUsedMb = heapUsed / (1024 * 1024);
        var heapMaxMb = heapMax / (1024 * 1024);
        sb.append("{\"id\":\"").append(nodeId.id())
                 .append("\",\"isLeader\":")
                 .append(nodeId.id().equals(leaderId))
                 .append(",\"cpuUsage\":")
                 .append(String.format("%.4f", cpuUsage))
                 .append(",\"heapUsedMb\":")
                 .append(String.format("%.1f", heapUsedMb))
                 .append(",\"heapMaxMb\":")
                 .append(String.format("%.1f", heapMaxMb))
                 .append(",\"role\":\"")
                 .append(role)
                 .append("\"")
                 .append(",\"rps\":")
                 .append(String.format("%.2f", invocData.rps))
                 .append(",\"avgLatencyMs\":")
                 .append(String.format("%.2f", invocData.avgLatencyMs))
                 .append(",\"successRate\":")
                 .append(String.format("%.4f", invocData.successRate))
                 .append("}");
    }

    private Map<String, PerNodeInvocationData> aggregateInvocationsByNode(AetherNode node) {
        var result = new java.util.HashMap<String, PerNodeInvocationData>();
        var deployments = node.deploymentMap().allDeployments();
        var snapshots = node.invocationMetrics().snapshot();
        var artifactToNodes = new java.util.HashMap<String, Set<String>>();
        for (var deployment : deployments) {
            var nodeIds = new HashSet<String>();
            for (var instance : deployment.instances()) {nodeIds.add(instance.nodeId());}
            artifactToNodes.put(deployment.artifact(), nodeIds);
        }
        for (var snapshot : snapshots) {
            var artifactName = snapshot.artifact().asString();
            var nodeIds = artifactToNodes.getOrDefault(artifactName, Set.of());
            if (nodeIds.isEmpty()) continue;
            var metrics = snapshot.metrics();
            var perNodeCount = metrics.count() / (double) nodeIds.size();
            var perNodeSuccess = metrics.successCount() / (double) nodeIds.size();
            var avgLatencyMs = metrics.averageLatencyNs() / 1_000_000.0;
            var successRate = metrics.successRate();
            for (var nid : nodeIds) {
                var existing = result.getOrDefault(nid, PerNodeInvocationData.EMPTY);
                var newData = new PerNodeInvocationData(existing.totalCount + perNodeCount,
                                                        existing.totalSuccess + perNodeSuccess,
                                                        avgLatencyMs > 0
                                                        ? (existing.avgLatencyMs * existing.weight + avgLatencyMs) / (existing.weight + 1)
                                                        : existing.avgLatencyMs,
                                                        existing.weight + 1);
                result.put(nid, newData);
            }
        }
        var finalResult = new java.util.HashMap<String, PerNodeInvocationData>();
        for (var entry : result.entrySet()) {
            var data = entry.getValue();
            var rps = data.totalCount / (broadcastIntervalMs / 1000.0);
            var sr = data.totalCount > 0
                    ? data.totalSuccess / data.totalCount
                    : 1.0;
            finalResult.put(entry.getKey(),
                            new PerNodeInvocationData(data.totalCount,
                                                      data.totalSuccess,
                                                      data.avgLatencyMs,
                                                      data.weight,
                                                      rps,
                                                      sr));
        }
        return finalResult;
    }

    private record PerNodeInvocationData(double totalCount,
                                         double totalSuccess,
                                         double avgLatencyMs,
                                         int weight,
                                         double rps,
                                         double successRate) {
        static final PerNodeInvocationData EMPTY = new PerNodeInvocationData(0, 0, 0, 0, 0, 1.0);

        PerNodeInvocationData(double totalCount, double totalSuccess, double avgLatencyMs, int weight) {
            this(totalCount, totalSuccess, avgLatencyMs, weight, 0, 1.0);
        }
    }

    private static void appendDeployments(StringBuilder sb, List<SliceDeploymentInfo> deployments) {
        sb.append("\"deployments\":[");
        boolean firstDeployment = true;
        for (var deployment : deployments) {
            if (!firstDeployment) sb.append(",");
            sb.append("{\"artifact\":\"").append(deployment.artifact())
                     .append("\",\"state\":\"")
                     .append(deployment.aggregateState().name())
                     .append("\",\"instances\":[");
            boolean firstInstance = true;
            for (var instance : deployment.instances()) {
                if (!firstInstance) sb.append(",");
                sb.append("{\"nodeId\":\"").append(instance.nodeId())
                         .append("\",\"state\":\"")
                         .append(instance.state().name())
                         .append("\"}");
                firstInstance = false;
            }
            sb.append("]}");
            firstDeployment = false;
        }
        sb.append("]");
    }

    @SuppressWarnings("JBCT-PAT-01") private void appendTopology(StringBuilder sb, AetherNode node) {
        var loaded = node.sliceStore().loaded();
        log.debug("appendTopology: loaded slices count={}", loaded.size());
        var sliceTopologies = loaded.stream().flatMap(ls -> TopologyParser.parse(ls.slice(),
                                                                                 ls.artifact().asString())
        .stream())
                                           .toList();
        log.debug("appendTopology: parsed topologies={}, building graph", sliceTopologies.size());
        var graph = TopologyGraph.build(sliceTopologies);
        log.debug("appendTopology: graph nodes={}, edges={}",
                  graph.nodes().size(),
                  graph.edges().size());
        sb.append("\"topology\":{\"nodes\":[");
        boolean firstNode = true;
        for (var n : graph.nodes()) {
            if (!firstNode) sb.append(",");
            sb.append("{\"id\":\"").append(escapeJson(n.id()))
                     .append("\",\"type\":\"")
                     .append(n.type().name())
                     .append("\",\"label\":\"")
                     .append(escapeJson(n.label()))
                     .append("\",\"sliceArtifact\":\"")
                     .append(escapeJson(n.sliceArtifact()))
                     .append("\"}");
            firstNode = false;
        }
        sb.append("],\"edges\":[");
        boolean firstEdge = true;
        for (var e : graph.edges()) {
            if (!firstEdge) sb.append(",");
            sb.append("{\"from\":\"").append(escapeJson(e.from()))
                     .append("\",\"to\":\"")
                     .append(escapeJson(e.to()))
                     .append("\",\"style\":\"")
                     .append(e.style().name())
                     .append("\",\"topicConfig\":\"")
                     .append(escapeJson(e.topicConfig()))
                     .append("\"}");
            firstEdge = false;
        }
        sb.append("]}");
    }

    private void appendSchema(StringBuilder sb, AetherNode node) {
        var entries = new ArrayList<SchemaVersionValue>();
        node.kvStore().forEach(SchemaVersionKey.class, SchemaVersionValue.class, (_, value) -> entries.add(value));
        sb.append("\"schema\":{\"datasources\":[");
        boolean first = true;
        for (var entry : entries) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(escapeJson(entry.datasourceName()))
                     .append("\",\"status\":\"")
                     .append(entry.status().name())
                     .append("\",\"currentVersion\":")
                     .append(entry.currentVersion())
                     .append(",\"lastMigration\":\"")
                     .append(escapeJson(entry.lastMigration()))
                     .append("\",\"attemptCount\":")
                     .append(entry.attemptCount())
                     .append("}");
            first = false;
        }
        sb.append("]}");
    }

    private void appendGovernors(StringBuilder sb, AetherNode node) {
        sb.append("\"governors\":[");
        var governors = new ArrayList<String>();
        node.kvStore()
                    .forEach(GovernorAnnouncementKey.class,
                             GovernorAnnouncementValue.class,
                             (key, value) -> governors.add(formatGovernor(key, value)));
        sb.append(String.join(",", governors));
        sb.append("]");
    }

    private String formatGovernor(GovernorAnnouncementKey key, GovernorAnnouncementValue value) {
        var sb = new StringBuilder();
        sb.append("{\"governorId\":\"").append(escapeJson(value.governorId().id()))
                 .append("\",\"community\":\"")
                 .append(escapeJson(key.communityId()))
                 .append("\",\"memberCount\":")
                 .append(value.memberCount())
                 .append(",\"members\":[");
        boolean first = true;
        for (var member : value.members()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(member.id()))
                     .append("\"");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private void appendClusterTopology(StringBuilder sb, AetherNode node) {
        var topologyConfig = node.topologyConfig();
        var coreNodeIds = node.initialTopology();
        var connectedCount = node.connectedNodeCount();
        var coreCount = coreNodeIds.size();
        var workerCount = Math.max(0, connectedCount - coreCount);
        sb.append("\"clusterTopology\":{\"coreCount\":").append(coreCount)
                 .append(",\"workerCount\":")
                 .append(workerCount)
                 .append(",\"coreMax\":")
                 .append(topologyConfig.coreMax())
                 .append(",\"clusterSize\":")
                 .append(topologyConfig.clusterSize())
                 .append("}");
    }

    private void appendStrategies(StringBuilder sb, AetherNode node) {
        sb.append("\"strategies\":{");
        sb.append("\"deployments\":[");
        var deployments = node.deploymentManager().list();
        boolean first = true;
        for (var deployment : deployments) {
            if (!first) sb.append(",");
            sb.append("{\"deploymentId\":\"").append(escapeJson(deployment.deploymentId()))
                     .append("\",\"blueprintId\":\"")
                     .append(escapeJson(deployment.blueprintId()))
                     .append("\",\"oldVersion\":\"")
                     .append(escapeJson(deployment.oldVersion().toString()))
                     .append("\",\"newVersion\":\"")
                     .append(escapeJson(deployment.newVersion().toString()))
                     .append("\",\"state\":\"")
                     .append(deployment.state().name())
                     .append("\",\"strategy\":\"")
                     .append(deployment.strategy().name())
                     .append("\",\"routing\":\"")
                     .append(escapeJson(deployment.routing().toString()))
                     .append("\",\"newInstances\":")
                     .append(deployment.newInstances())
                     .append("}");
            first = false;
        }
        sb.append("],");
        sb.append("\"abTests\":[");
        var abTests = node.abTestManager().allTests();
        first = true;
        for (var test : abTests) {
            if (!first) sb.append(",");
            sb.append("{\"testId\":\"").append(escapeJson(test.testId()))
                     .append("\",\"artifactBase\":\"")
                     .append(escapeJson(test.artifactBase().asString()))
                     .append("\",\"baselineVersion\":\"")
                     .append(escapeJson(test.baselineVersion().toString()))
                     .append("\",\"state\":\"")
                     .append(test.state().name())
                     .append("\",\"variantCount\":")
                     .append(test.variantVersions().size())
                     .append("}");
            first = false;
        }
        sb.append("]}");
    }

    private void appendStreams(StringBuilder sb, AetherNode node) {
        sb.append("\"streams\":[");
        var streams = node.streamPartitionManager().listStreams();
        boolean first = true;
        for (var stream : streams) {
            if (!first) sb.append(",");
            sb.append("{\"name\":\"").append(escapeJson(stream.name()))
                     .append("\",\"partitions\":")
                     .append(stream.partitions())
                     .append(",\"totalEvents\":")
                     .append(stream.totalEvents())
                     .append(",\"totalBytes\":")
                     .append(stream.totalBytes())
                     .append("}");
            first = false;
        }
        sb.append("]");
    }

    private void appendRoutes(StringBuilder sb, AetherNode node) {
        sb.append("\"routes\":[");
        var routes = node.httpRouteRegistry().allRoutes();
        boolean first = true;
        for (var route : routes) {
            if (!first) sb.append(",");
            sb.append("{\"method\":\"").append(escapeJson(route.httpMethod()))
                     .append("\",\"path\":\"")
                     .append(escapeJson(route.pathPrefix()))
                     .append("\",\"security\":\"")
                     .append(escapeJson(route.security()))
                     .append("\",\"nodes\":[");
            boolean firstNode = true;
            for (var nodeId : route.nodes()) {
                if (!firstNode) sb.append(",");
                sb.append("\"").append(escapeJson(nodeId.id()))
                         .append("\"");
                firstNode = false;
            }
            sb.append("]}");
            first = false;
        }
        sb.append("]");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t");
    }

    public void handleSetThreshold(String message) {
        var metricPattern = Pattern.compile("\"metric\"\\s*:\\s*\"([^\"]+)\"");
        var warningPattern = Pattern.compile("\"warning\"\\s*:\\s*([\\d.]+)");
        var criticalPattern = Pattern.compile("\"critical\"\\s*:\\s*([\\d.]+)");
        var metricMatch = metricPattern.matcher(message);
        var warningMatch = warningPattern.matcher(message);
        var criticalMatch = criticalPattern.matcher(message);
        if (metricMatch.find() && warningMatch.find() && criticalMatch.find()) {
            var metric = metricMatch.group(1);
            var warning = Double.parseDouble(warningMatch.group(1));
            var critical = Double.parseDouble(criticalMatch.group(1));
            alertManager.setThreshold(metric, warning, critical);
            log.info("Updated threshold for {}: warning={}, critical={}", metric, warning, critical);
        }
    }

    public String buildHistoryResponse(String message) {
        var rangePattern = Pattern.compile("\"timeRange\"\\s*:\\s*\"([^\"]+)\"");
        var rangeMatch = rangePattern.matcher(message);
        var range = "1h";
        if (rangeMatch.find()) {range = rangeMatch.group(1);}
        var node = nodeSupplier.get();
        var historicalData = node.metricsCollector().historicalMetrics();
        var cutoff = System.currentTimeMillis() - parseTimeRange(range);
        var sb = new StringBuilder();
        sb.append("{\"type\":\"HISTORY\",\"timeRange\":\"").append(range)
                 .append("\",\"nodes\":{");
        boolean firstNode = true;
        for (var nodeEntry : historicalData.entrySet()) {
            if (!firstNode) sb.append(",");
            sb.append("\"").append(nodeEntry.getKey().id())
                     .append("\":[");
            boolean firstSnapshot = true;
            for (var snapshot : nodeEntry.getValue()) {
                if (snapshot.timestamp() <cutoff) continue;
                if (!firstSnapshot) sb.append(",");
                sb.append("{\"timestamp\":").append(snapshot.timestamp())
                         .append(",\"metrics\":{");
                boolean firstMetric = true;
                for (var metric : snapshot.metrics().entrySet()) {
                    if (!firstMetric) sb.append(",");
                    sb.append("\"").append(metric.getKey())
                             .append("\":")
                             .append(metric.getValue());
                    firstMetric = false;
                }
                sb.append("}}");
                firstSnapshot = false;
            }
            sb.append("]");
            firstNode = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    private long parseTimeRange(String range) {
        return switch (range){
            case "5m" -> 5 * 60 * 1000L;
            case "15m" -> 15 * 60 * 1000L;
            case "1h" -> 60 * 60 * 1000L;
            case "2h" -> 2 * 60 * 60 * 1000L;
            default -> 60 * 60 * 1000L;
        };
    }
}
