package org.pragmatica.aether.api;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes dashboard metrics via WebSocket at regular intervals.
 *
 * <p>Aggregates metrics from various collectors and broadcasts to all connected clients.
 */
public class DashboardMetricsPublisher {
    private static final Logger log = LoggerFactory.getLogger(DashboardMetricsPublisher.class);
    private static final long BROADCAST_INTERVAL_MS = 1000;

    private final Supplier<AetherNode> nodeSupplier;
    private final AlertManager alertManager;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public DashboardMetricsPublisher(Supplier<AetherNode> nodeSupplier, AlertManager alertManager) {
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                        var thread = new Thread(r, "dashboard-publisher");
                                                                        thread.setDaemon(true);
                                                                        return thread;
                                                                    });
    }

    public void start() {
        if (running) return;
        running = true;
        scheduler.scheduleAtFixedRate(this::publishMetrics,
                                      BROADCAST_INTERVAL_MS,
                                      BROADCAST_INTERVAL_MS,
                                      TimeUnit.MILLISECONDS);
        log.info("Dashboard metrics publisher started");
    }

    public void stop() {
        running = false;
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
                var alert = alertManager.checkThreshold(metric.getKey(), nodeId, metric.getValue());
                if (alert != null) {
                    DashboardWebSocketHandler.broadcast(alert);
                }
            }
        }
    }

    /**
     * Build initial state snapshot for newly connected clients.
     */
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
        // Slices
        sb.append("\"slices\":[");
        var slices = node.sliceStore()
                         .loaded();
        boolean firstSlice = true;
        for (var slice : slices) {
            if (!firstSlice) sb.append(",");
            sb.append("\"")
              .append(slice.artifact()
                           .asString())
              .append("\"");
            firstSlice = false;
        }
        sb.append("],");
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

    /**
     * Build periodic metrics update message.
     */
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
          .append(buildInvocationMetrics());
        sb.append("}");
        return sb.toString();
    }

    private String buildInvocationMetrics() {
        // TODO: expose invocationMetricsCollector via AetherNode interface
        return "[]";
    }

    /**
     * Handle threshold configuration from client.
     */
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

    /**
     * Build history response for GET_HISTORY request.
     */
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
            case"5m" -> 5 * 60 * 1000L;
            case"15m" -> 15 * 60 * 1000L;
            case"1h" -> 60 * 60 * 1000L;
            case"2h" -> 2 * 60 * 60 * 1000L;
            default -> 60 * 60 * 1000L;
        };
    }
}
