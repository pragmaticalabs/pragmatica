package org.pragmatica.aether.api;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.DeploymentMap.SliceDeploymentInfo;
import org.pragmatica.aether.deployment.DeploymentMap.SliceInstanceInfo;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Publishes dashboard metrics via WebSocket at regular intervals.
///
///
/// Aggregates metrics from various collectors and broadcasts to all connected clients.
@SuppressWarnings("JBCT-RET-01")
public class DashboardMetricsPublisher {
    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsPublisher.class);
    private static final long BROADCAST_INTERVAL_MS = 1000;

    private static final double EMA_ALPHA = 0.2;

    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private long lastTotalInvocations = 0;
    private long lastTotalSuccess = 0;
    private long lastTotalFailure = 0;
    private double emaRps = 0.0;
    private double emaSuccessRate = 1.0;
    private double emaErrorRate = 0.0;
    private double emaAvgLatencyMs = 0.0;

    public DashboardMetricsPublisher(Supplier<AetherNode> nodeSupplier,
                                     AlertManager alertManager) {
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                        var thread = new Thread(r, "dashboard-publisher");
                                                                        thread.setDaemon(true);
                                                                        return thread;
                                                                    });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::publishMetrics,
                                      BROADCAST_INTERVAL_MS,
                                      BROADCAST_INTERVAL_MS,
                                      TimeUnit.MILLISECONDS);
        log.info("Dashboard metrics publisher started");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        log.info("Dashboard metrics publisher stopped");
    }

    private void publishMetrics() {
        if (DashboardWebSocketHandler.connectedClients() == 0) {
            return;
        }
        try{
            var message = buildMetricsUpdate();
            DashboardWebSocketHandler.broadcast(message);
            // Check thresholds and broadcast alerts
            checkAndBroadcastAlerts();
        } catch (Exception e) {
            log.error("Error publishing metrics", e);
        }
    }

    private void checkAndBroadcastAlerts() {
        var node = nodeSupplier.get();
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        for (var entry : allMetrics.entrySet()) {
            var nodeId = entry.getKey();
            var metrics = entry.getValue();
            for (var metric : metrics.entrySet()) {
                alertManager.checkThreshold(metric.getKey(),
                                            nodeId,
                                            metric.getValue())
                            .onPresent(DashboardWebSocketHandler::broadcast);
            }
        }
    }

    /// Build initial state snapshot for newly connected clients.
    public String buildInitialState() {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        sb.append("{\"type\":\"INITIAL_STATE\",\"timestamp\":")
          .append(System.currentTimeMillis())
          .append(",\"data\":{");
        // Nodes (first node in sorted list is typically the leader in Rabia)
        sb.append("\"nodes\":[");
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var sortedNodes = allMetrics.keySet()
                                    .stream()
                                    .sorted((a, b) -> a.id()
                                                       .compareTo(b.id()))
                                    .collect(Collectors.toList());
        var leaderId = sortedNodes.isEmpty()
                       ? ""
                       : sortedNodes.get(0)
                                    .id();
        boolean firstNode = true;
        for (var nodeId : sortedNodes) {
            if (!firstNode) sb.append(",");
            sb.append("{\"id\":\"")
              .append(nodeId.id())
              .append("\",");
            sb.append("\"isLeader\":")
              .append(nodeId.id()
                            .equals(leaderId))
              .append("}");
            firstNode = false;
        }
        sb.append("],");
        // Slices (backward compatibility - artifact names only)
        var deployments = node.deploymentMap()
                              .allDeployments();
        sb.append("\"slices\":[");
        boolean firstSlice = true;
        for (var deployment : deployments) {
            if (!firstSlice) sb.append(",");
            sb.append("\"")
              .append(deployment.artifact())
              .append("\"");
            firstSlice = false;
        }
        sb.append("],");
        // Deployments (cluster-wide with state and instances)
        appendDeployments(sb, deployments);
        sb.append(",");
        // Thresholds
        sb.append("\"thresholds\":")
          .append(alertManager.thresholdsAsJson())
          .append(",");
        // Current metrics snapshot
        sb.append("\"metrics\":")
          .append(buildMetricsData());
        sb.append("}}");
        return sb.toString();
    }

    /// Build periodic metrics update message.
    private String buildMetricsUpdate() {
        var sb = new StringBuilder();
        sb.append("{\"type\":\"METRICS_UPDATE\",\"timestamp\":")
          .append(System.currentTimeMillis());
        sb.append(",\"data\":")
          .append(buildMetricsData())
          .append("}");
        return sb.toString();
    }

    private String buildMetricsData() {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        sb.append("{");
        // Load metrics
        sb.append("\"load\":{");
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        boolean firstNode = true;
        for (var entry : allMetrics.entrySet()) {
            if (!firstNode) sb.append(",");
            sb.append("\"")
              .append(entry.getKey()
                           .id())
              .append("\":{");
            boolean firstMetric = true;
            for (var metric : entry.getValue()
                                   .entrySet()) {
                if (!firstMetric) sb.append(",");
                sb.append("\"")
                  .append(metric.getKey())
                  .append("\":")
                  .append(metric.getValue());
                firstMetric = false;
            }
            sb.append("}");
            firstNode = false;
        }
        sb.append("},");
        // Invocation metrics (if available)
        sb.append("\"invocations\":")
          .append(buildInvocationMetrics())
          .append(",");
        // Deployments (cluster-wide)
        appendDeployments(sb,
                          node.deploymentMap()
                              .allDeployments());
        sb.append(",\"aggregates\":")
          .append(buildAggregates());
        sb.append("}");
        return sb.toString();
    }

    private String buildAggregates() {
        var node = nodeSupplier.get();
        var snapshots = node.invocationMetrics()
                            .snapshot();
        long totalInvocations = 0;
        long totalSuccess = 0;
        long totalFailure = 0;
        double weightedLatency = 0.0;
        for (var snapshot : snapshots) {
            var metrics = snapshot.metrics();
            totalInvocations += metrics.count();
            totalSuccess += metrics.successCount();
            totalFailure += metrics.failureCount();
            weightedLatency += metrics.averageLatencyNs() / 1_000_000.0 * metrics.count();
        }
        long deltaInvocations = totalInvocations - lastTotalInvocations;
        long deltaSuccess = totalSuccess - lastTotalSuccess;
        double instantRps = deltaInvocations / (BROADCAST_INTERVAL_MS / 1000.0);
        double instantSuccessRate = deltaInvocations > 0
                                    ? (double) deltaSuccess / deltaInvocations
                                    : 1.0;
        double instantErrorRate = 1.0 - instantSuccessRate;
        double avgLatencyMs = totalInvocations > 0
                              ? weightedLatency / totalInvocations
                              : 0.0;
        emaRps = EMA_ALPHA * instantRps + (1 - EMA_ALPHA) * emaRps;
        emaSuccessRate = EMA_ALPHA * instantSuccessRate + (1 - EMA_ALPHA) * emaSuccessRate;
        emaErrorRate = EMA_ALPHA * instantErrorRate + (1 - EMA_ALPHA) * emaErrorRate;
        emaAvgLatencyMs = EMA_ALPHA * avgLatencyMs + (1 - EMA_ALPHA) * emaAvgLatencyMs;
        lastTotalInvocations = totalInvocations;
        lastTotalSuccess = totalSuccess;
        lastTotalFailure = totalFailure;
        return String.format("{\"rps\":%.2f,\"successRate\":%.4f,\"errorRate\":%.4f,\"avgLatencyMs\":%.2f}",
                             emaRps,
                             emaSuccessRate,
                             emaErrorRate,
                             emaAvgLatencyMs);
    }

    private String buildInvocationMetrics() {
        var node = nodeSupplier.get();
        var snapshots = node.invocationMetrics()
                            .snapshot();
        if (snapshots.isEmpty()) {
            return "[]";
        }
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var snapshot : snapshots) {
            if (!first) sb.append(",");
            first = false;
            var metrics = snapshot.metrics();
            sb.append("{\"artifact\":\"")
              .append(snapshot.artifact()
                              .asString())
              .append("\",\"method\":\"")
              .append(snapshot.methodName()
                              .name())
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
              .append(snapshot.slowInvocations()
                              .size())
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void appendDeployments(StringBuilder sb, List<SliceDeploymentInfo> deployments) {
        sb.append("\"deployments\":[");
        boolean firstDeployment = true;
        for (var deployment : deployments) {
            if (!firstDeployment) sb.append(",");
            sb.append("{\"artifact\":\"")
              .append(deployment.artifact())
              .append("\",\"state\":\"")
              .append(deployment.aggregateState()
                                .name())
              .append("\",\"instances\":[");
            boolean firstInstance = true;
            for (var instance : deployment.instances()) {
                if (!firstInstance) sb.append(",");
                sb.append("{\"nodeId\":\"")
                  .append(instance.nodeId())
                  .append("\",\"state\":\"")
                  .append(instance.state()
                                  .name())
                  .append("\"}");
                firstInstance = false;
            }
            sb.append("]}");
            firstDeployment = false;
        }
        sb.append("]");
    }

    /// Handle threshold configuration from client.
    public void handleSetThreshold(String message) {
        // Parse: {"type":"SET_THRESHOLD","metric":"cpu.usage","warning":0.7,"critical":0.9}
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

    /// Build history response for GET_HISTORY request.
    public String buildHistoryResponse(String message) {
        // Parse: {"type":"GET_HISTORY","timeRange":"1h"}
        var rangePattern = Pattern.compile("\"timeRange\"\\s*:\\s*\"([^\"]+)\"");
        var rangeMatch = rangePattern.matcher(message);
        var range = "1h";
        if (rangeMatch.find()) {
            range = rangeMatch.group(1);
        }
        var node = nodeSupplier.get();
        var historicalData = node.metricsCollector()
                                 .historicalMetrics();
        var cutoff = System.currentTimeMillis() - parseTimeRange(range);
        var sb = new StringBuilder();
        sb.append("{\"type\":\"HISTORY\",\"timeRange\":\"")
          .append(range)
          .append("\",\"nodes\":{");
        // Build node-centric history: {"nodes": {"node-1": [{"timestamp": ..., "metrics": {...}}, ...]}}
        boolean firstNode = true;
        for (var nodeEntry : historicalData.entrySet()) {
            if (!firstNode) sb.append(",");
            sb.append("\"")
              .append(nodeEntry.getKey()
                               .id())
              .append("\":[");
            boolean firstSnapshot = true;
            for (var snapshot : nodeEntry.getValue()) {
                if (snapshot.timestamp() < cutoff) continue;
                if (!firstSnapshot) sb.append(",");
                sb.append("{\"timestamp\":")
                  .append(snapshot.timestamp())
                  .append(",\"metrics\":{");
                boolean firstMetric = true;
                for (var metric : snapshot.metrics()
                                          .entrySet()) {
                    if (!firstMetric) sb.append(",");
                    sb.append("\"")
                      .append(metric.getKey())
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
        return switch (range) {
            case "5m" -> 5 * 60 * 1000L;
            case "15m" -> 15 * 60 * 1000L;
            case "1h" -> 60 * 60 * 1000L;
            case "2h" -> 2 * 60 * 60 * 1000L;
            default -> 60 * 60 * 1000L;
        };
    }
}
