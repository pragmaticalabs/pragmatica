package org.pragmatica.aether.api;

import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages alert thresholds and tracks active alerts.
 *
 * <p>In-memory only - thresholds reset on node restart.
 */
public class AlertManager {
    private static final Logger log = LoggerFactory.getLogger(AlertManager.class);
    private static final int MAX_ALERT_HISTORY = 100;

    private final Map<String, Threshold> thresholds = new ConcurrentHashMap<>();
    private final Map<String, ActiveAlert> activeAlerts = new ConcurrentHashMap<>();
    private final List<AlertHistoryEntry> alertHistory = new ArrayList<>();

    public AlertManager() {
        // Default thresholds
        thresholds.put("cpu.usage", new Threshold(0.7, 0.9));
        thresholds.put("heap.usage", new Threshold(0.7, 0.85));
    }

    /**
     * Set threshold for a metric.
     */
    public void setThreshold(String metric, double warning, double critical) {
        thresholds.put(metric, new Threshold(warning, critical));
        log.info("Threshold set for {}: warning={}, critical={}", metric, warning, critical);
    }

    /**
     * Remove threshold for a metric.
     */
    public void removeThreshold(String metric) {
        var removed = thresholds.remove(metric);
        if (removed != null) {
            log.info("Threshold removed for {}", metric);
        }
    }

    /**
     * Get all configured thresholds.
     */
    public Map<String, double[] > getAllThresholds() {
        Map<String, double[] > result = new ConcurrentHashMap<>();
        thresholds.forEach((k, v) -> result.put(k, new double[]{v.warning, v.critical}));
        return result;
    }

    /**
     * Clear all active alerts.
     */
    public void clearAlerts() {
        activeAlerts.clear();
        log.info("All active alerts cleared");
    }

    /**
     * Get count of active alerts.
     */
    public int activeAlertCount() {
        return activeAlerts.size();
    }

    /**
     * Check if a metric value exceeds threshold and return alert JSON if triggered.
     */
    public String checkThreshold(String metric, NodeId nodeId, double value) {
        var threshold = thresholds.get(metric);
        if (threshold == null) {
            return null;
        }
        var alertKey = metric + ":" + nodeId.id();
        var existing = activeAlerts.get(alertKey);
        var severity = threshold.severity(value);
        if (severity == null) {
            // Value is normal, clear any existing alert
            if (existing != null) {
                activeAlerts.remove(alertKey);
                addToHistory(metric, nodeId, value, existing.severity, "RESOLVED");
            }
            return null;
        }
        // Check if this is a new alert or severity change
        if (existing == null || !existing.severity.equals(severity)) {
            var alert = new ActiveAlert(metric,
                                        nodeId,
                                        value,
                                        threshold.forSeverity(severity),
                                        severity,
                                        System.currentTimeMillis());
            activeAlerts.put(alertKey, alert);
            addToHistory(metric, nodeId, value, severity, "TRIGGERED");
            // Return alert message to broadcast
            return buildAlertMessage(alert);
        }
        return null;
    }

    private String buildAlertMessage(ActiveAlert alert) {
        return "{\"type\":\"ALERT\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{" + "\"metric\":\"" + alert.metric
               + "\"," + "\"nodeId\":\"" + alert.nodeId.id() + "\"," + "\"value\":" + alert.value + ","
               + "\"threshold\":" + alert.threshold + "," + "\"severity\":\"" + alert.severity + "\"}}";
    }

    private void addToHistory(String metric, NodeId nodeId, double value, String severity, String status) {
        synchronized (alertHistory) {
            alertHistory.add(new AlertHistoryEntry(
            System.currentTimeMillis(), metric, nodeId.id(), value, severity, status));
            while (alertHistory.size() > MAX_ALERT_HISTORY) {
                alertHistory.remove(0);
            }
        }
    }

    /**
     * Get all thresholds as JSON.
     */
    public String thresholdsAsJson() {
        var sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (var entry : thresholds.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(entry.getKey())
              .append("\":{");
            sb.append("\"warning\":")
              .append(entry.getValue().warning)
              .append(",");
            sb.append("\"critical\":")
              .append(entry.getValue().critical);
            sb.append("}");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Get active alerts as JSON.
     */
    public String activeAlertsAsJson() {
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var alert : activeAlerts.values()) {
            if (!first) sb.append(",");
            sb.append("{");
            sb.append("\"metric\":\"")
              .append(alert.metric)
              .append("\",");
            sb.append("\"nodeId\":\"")
              .append(alert.nodeId.id())
              .append("\",");
            sb.append("\"value\":")
              .append(alert.value)
              .append(",");
            sb.append("\"threshold\":")
              .append(alert.threshold)
              .append(",");
            sb.append("\"severity\":\"")
              .append(alert.severity)
              .append("\",");
            sb.append("\"triggeredAt\":")
              .append(alert.triggeredAt);
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Get alert history as JSON.
     */
    public String alertHistoryAsJson() {
        var sb = new StringBuilder();
        sb.append("[");
        synchronized (alertHistory) {
            boolean first = true;
            for (var entry : alertHistory) {
                if (!first) sb.append(",");
                sb.append("{");
                sb.append("\"timestamp\":")
                  .append(entry.timestamp)
                  .append(",");
                sb.append("\"metric\":\"")
                  .append(entry.metric)
                  .append("\",");
                sb.append("\"nodeId\":\"")
                  .append(entry.nodeId)
                  .append("\",");
                sb.append("\"value\":")
                  .append(entry.value)
                  .append(",");
                sb.append("\"severity\":\"")
                  .append(entry.severity)
                  .append("\",");
                sb.append("\"status\":\"")
                  .append(entry.status)
                  .append("\"");
                sb.append("}");
                first = false;
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private record Threshold(double warning, double critical) {
        String severity(double value) {
            if (value >= critical) return "CRITICAL";
            if (value >= warning) return "WARNING";
            return null;
        }

        double forSeverity(String severity) {
            return "CRITICAL".equals(severity)
                   ? critical
                   : warning;
        }
    }

    private record ActiveAlert(String metric,
                               NodeId nodeId,
                               double value,
                               double threshold,
                               String severity,
                               long triggeredAt) {}

    private record AlertHistoryEntry(long timestamp,
                                     String metric,
                                     String nodeId,
                                     double value,
                                     String severity,
                                     String status) {}
}
