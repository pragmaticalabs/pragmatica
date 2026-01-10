package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages alert thresholds and tracks active alerts.
 *
 * <p>Thresholds are persisted to consensus KV-Store for cluster-wide consistency
 * and survival across node restarts.
 */
public class AlertManager {
    private static final Logger log = LoggerFactory.getLogger(AlertManager.class);
    private static final int MAX_ALERT_HISTORY = 100;

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;

    private final Map<String, Threshold> thresholds = new ConcurrentHashMap<>();
    private final Map<String, ActiveAlert> activeAlerts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<AlertHistoryEntry> alertHistory = new ConcurrentLinkedDeque<>();
    private final AtomicInteger historySize = new AtomicInteger(0);

    private AlertManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                         KVStore<AetherKey, AetherValue> kvStore) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static AlertManager alertManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            KVStore<AetherKey, AetherValue> kvStore) {
        var manager = new AlertManager(clusterNode, kvStore);
        manager.loadThresholdsFromKvStore();
        manager.ensureDefaultThresholds();
        return manager;
    }

    /**
     * Load thresholds from KV-Store on startup.
     */
    private void loadThresholdsFromKvStore() {
        var snapshot = kvStore.snapshot();
        for (var entry : snapshot.entrySet()) {
            if (entry.getKey() instanceof AetherKey.AlertThresholdKey thresholdKey &&
            entry.getValue() instanceof AetherValue.AlertThresholdValue thresholdValue) {
                thresholds.put(thresholdKey.metricName(),
                               new Threshold(thresholdValue.warningThreshold(), thresholdValue.criticalThreshold()));
                log.debug("Loaded threshold from KV-Store: {} warning={}, critical={}",
                          thresholdKey.metricName(),
                          thresholdValue.warningThreshold(),
                          thresholdValue.criticalThreshold());
            }
        }
        log.info("Loaded {} thresholds from KV-Store", thresholds.size());
    }

    /**
     * Ensure default thresholds exist if no thresholds were loaded.
     */
    private void ensureDefaultThresholds() {
        if (thresholds.isEmpty()) {
            // Set defaults in-memory, they will be persisted on first explicit setThreshold call
            thresholds.put("cpu.usage", new Threshold(0.7, 0.9));
            thresholds.put("heap.usage", new Threshold(0.7, 0.85));
            log.info("Initialized default thresholds (in-memory only until explicitly set)");
        }
    }

    /**
     * Set threshold for a metric and persist to KV-Store.
     *
     * @return Promise that completes when threshold is persisted across cluster
     */
    @SuppressWarnings("unchecked")
    public Promise<Unit> setThreshold(String metric, double warning, double critical) {
        var key = new AetherKey.AlertThresholdKey(metric);
        var value = AetherValue.AlertThresholdValue.alertThresholdValue(metric, warning, critical);
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Put<>(key, value);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   thresholds.put(metric,
                                                  new Threshold(warning, critical));
                                   log.info("Threshold set and persisted for {}: warning={}, critical={}",
                                            metric,
                                            warning,
                                            critical);
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist threshold for {}: {}",
                                                        metric,
                                                        cause.message()));
    }

    /**
     * Remove threshold for a metric and persist removal to KV-Store.
     *
     * @return Promise that completes when removal is persisted across cluster
     */
    @SuppressWarnings("unchecked")
    public Promise<Unit> removeThreshold(String metric) {
        var key = new AetherKey.AlertThresholdKey(metric);
        var command = (KVCommand<AetherKey>)(KVCommand< ? >) new KVCommand.Remove<>(key);
        return clusterNode.<Unit> apply(List.of(command))
                          .map(_ -> {
                                   var removed = thresholds.remove(metric);
                                   if (removed != null) {
                                       log.info("Threshold removed and persisted for {}", metric);
                                   }
                                   return Unit.unit();
                               })
                          .onFailure(cause -> log.error("Failed to persist threshold removal for {}: {}",
                                                        metric,
                                                        cause.message()));
    }

    /**
     * Get all configured thresholds.
     */
    public Map<String, double[]> getAllThresholds() {
        Map<String, double[]> result = new ConcurrentHashMap<>();
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
    public Option<String> checkThreshold(String metric, NodeId nodeId, double value) {
        return Option.option(thresholds.get(metric))
                     .flatMap(threshold -> {
                                  var alertKey = metric + ":" + nodeId.id();
                                  var existing = Option.option(activeAlerts.get(alertKey));
                                  return threshold.severity(value)
                                                  .fold(() -> handleNormalValue(alertKey,
                                                                                existing,
                                                                                metric,
                                                                                nodeId,
                                                                                value),
                                                        severity -> handleAlertValue(alertKey,
                                                                                     existing,
                                                                                     severity,
                                                                                     metric,
                                                                                     nodeId,
                                                                                     value,
                                                                                     threshold));
                              });
    }

    private Option<String> handleNormalValue(String alertKey,
                                             Option<ActiveAlert> existing,
                                             String metric,
                                             NodeId nodeId,
                                             double value) {
        existing.onPresent(alert -> {
                               activeAlerts.remove(alertKey);
                               addToHistory(metric, nodeId, value, alert.severity, "RESOLVED");
                           });
        return Option.none();
    }

    private Option<String> handleAlertValue(String alertKey,
                                            Option<ActiveAlert> existing,
                                            String severity,
                                            String metric,
                                            NodeId nodeId,
                                            double value,
                                            Threshold threshold) {
        var shouldTrigger = existing.filter(alert -> alert.severity.equals(severity))
                                    .isEmpty();
        if (shouldTrigger) {
            var alert = new ActiveAlert(metric,
                                        nodeId,
                                        value,
                                        threshold.forSeverity(severity),
                                        severity,
                                        System.currentTimeMillis());
            activeAlerts.put(alertKey, alert);
            addToHistory(metric, nodeId, value, severity, "TRIGGERED");
            return Option.option(buildAlertMessage(alert));
        }
        return Option.none();
    }

    /**
     * Handle KV-Store update notification for threshold changes from other nodes.
     *
     * <p>Called by AetherNode when it receives KV-Store value updates.
     */
    public void onKvStoreUpdate(AetherKey key, AetherValue value) {
        if (key instanceof AetherKey.AlertThresholdKey thresholdKey &&
        value instanceof AetherValue.AlertThresholdValue thresholdValue) {
            thresholds.put(thresholdKey.metricName(),
                           new Threshold(thresholdValue.warningThreshold(), thresholdValue.criticalThreshold()));
            log.debug("Threshold updated from cluster: {} warning={}, critical={}",
                      thresholdKey.metricName(),
                      thresholdValue.warningThreshold(),
                      thresholdValue.criticalThreshold());
        }
    }

    /**
     * Handle KV-Store remove notification for threshold deletions from other nodes.
     */
    public void onKvStoreRemove(AetherKey key) {
        if (key instanceof AetherKey.AlertThresholdKey thresholdKey) {
            thresholds.remove(thresholdKey.metricName());
            log.debug("Threshold removed from cluster: {}", thresholdKey.metricName());
        }
    }

    private String buildAlertMessage(ActiveAlert alert) {
        return "{\"type\":\"ALERT\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{" + "\"metric\":\"" + alert.metric
               + "\"," + "\"nodeId\":\"" + alert.nodeId.id() + "\"," + "\"value\":" + alert.value + ","
               + "\"threshold\":" + alert.threshold + "," + "\"severity\":\"" + alert.severity + "\"}}";
    }

    private void addToHistory(String metric, NodeId nodeId, double value, String severity, String status) {
        alertHistory.addLast(new AlertHistoryEntry(System.currentTimeMillis(),
                                                   metric,
                                                   nodeId.id(),
                                                   value,
                                                   severity,
                                                   status));
        // Approximate size tracking - may briefly exceed MAX_ALERT_HISTORY under contention
        if (historySize.incrementAndGet() > MAX_ALERT_HISTORY) {
            if (alertHistory.pollFirst() != null) {
                historySize.decrementAndGet();
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
        sb.append("]");
        return sb.toString();
    }

    // ============================================
    // Slice Failure Alerting
    // ============================================
    /**
     * Handle slice failure event - all instances of a slice have failed.
     *
     * <p>This is a CRITICAL alert that may trigger automatic rollback.
     */
    @MessageReceiver
    public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
        var alertKey = "slice.all_failed:" + event.artifact()
                                                 .asString() + "/" + event.method()
                                                                         .name();
        var alert = new SliceFailureAlert(event.artifact(),
                                          event.method(),
                                          event.lastError(),
                                          event.attemptedNodes(),
                                          event.requestId(),
                                          event.timestamp());
        activeSliceFailureAlerts.put(alertKey, alert);
        addSliceFailureToHistory(event);
        log.error("[requestId={}] CRITICAL: All instances failed for {}.{} - {} nodes attempted: {}",
                  event.requestId(),
                  event.artifact(),
                  event.method(),
                  event.attemptedNodes()
                       .size(),
                  event.lastError() != null
                  ? event.lastError()
                         .message()
                  : "unknown error");
    }

    private final Map<String, SliceFailureAlert> activeSliceFailureAlerts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<SliceFailureHistoryEntry> sliceFailureHistory = new ConcurrentLinkedDeque<>();
    private final AtomicInteger sliceFailureHistorySize = new AtomicInteger(0);

    private void addSliceFailureToHistory(SliceFailureEvent.AllInstancesFailed event) {
        var entry = new SliceFailureHistoryEntry(event.timestamp(),
                                                 event.requestId(),
                                                 event.artifact()
                                                      .asString(),
                                                 event.method()
                                                      .name(),
                                                 event.attemptedNodes()
                                                      .stream()
                                                      .map(NodeId::id)
                                                      .toList(),
                                                 event.lastError() != null
                                                 ? event.lastError()
                                                        .message()
                                                 : "unknown");
        sliceFailureHistory.addLast(entry);
        if (sliceFailureHistorySize.incrementAndGet() > MAX_ALERT_HISTORY) {
            if (sliceFailureHistory.pollFirst() != null) {
                sliceFailureHistorySize.decrementAndGet();
            }
        }
    }

    /**
     * Get active slice failure alerts.
     */
    public List<SliceFailureAlert> getActiveSliceFailureAlerts() {
        return List.copyOf(activeSliceFailureAlerts.values());
    }

    /**
     * Clear a slice failure alert (e.g., after rollback or manual resolution).
     */
    public void clearSliceFailureAlert(Artifact artifact, MethodName method) {
        var alertKey = "slice.all_failed:" + artifact.asString() + "/" + method.name();
        activeSliceFailureAlerts.remove(alertKey);
        log.info("Cleared slice failure alert for {}.{}", artifact, method);
    }

    /**
     * Get slice failure alerts as JSON.
     */
    public String sliceFailureAlertsAsJson() {
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var alert : activeSliceFailureAlerts.values()) {
            if (!first) sb.append(",");
            sb.append("{");
            sb.append("\"type\":\"SLICE_ALL_INSTANCES_FAILED\",");
            sb.append("\"severity\":\"CRITICAL\",");
            sb.append("\"artifact\":\"")
              .append(alert.artifact.asString())
              .append("\",");
            sb.append("\"method\":\"")
              .append(alert.method.name())
              .append("\",");
            sb.append("\"requestId\":\"")
              .append(alert.requestId)
              .append("\",");
            sb.append("\"attemptedNodes\":[");
            boolean firstNode = true;
            for (var nodeId : alert.attemptedNodes) {
                if (!firstNode) sb.append(",");
                sb.append("\"")
                  .append(nodeId.id())
                  .append("\"");
                firstNode = false;
            }
            sb.append("],");
            sb.append("\"lastError\":\"")
              .append(alert.lastError != null
                      ? escapeJson(alert.lastError.message())
                      : "unknown")
              .append("\",");
            sb.append("\"timestamp\":")
              .append(alert.triggeredAt);
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Get slice failure history as JSON.
     */
    public String sliceFailureHistoryAsJson() {
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var entry : sliceFailureHistory) {
            if (!first) sb.append(",");
            sb.append("{");
            sb.append("\"timestamp\":")
              .append(entry.timestamp)
              .append(",");
            sb.append("\"requestId\":\"")
              .append(entry.requestId)
              .append("\",");
            sb.append("\"artifact\":\"")
              .append(entry.artifact)
              .append("\",");
            sb.append("\"method\":\"")
              .append(entry.method)
              .append("\",");
            sb.append("\"attemptedNodes\":[");
            boolean firstNode = true;
            for (var nodeId : entry.attemptedNodes) {
                if (!firstNode) sb.append(",");
                sb.append("\"")
                  .append(nodeId)
                  .append("\"");
                firstNode = false;
            }
            sb.append("],");
            sb.append("\"lastError\":\"")
              .append(escapeJson(entry.lastError))
              .append("\"");
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Record for tracking active slice failure alerts.
     */
    public record SliceFailureAlert(Artifact artifact,
                                    MethodName method,
                                    Cause lastError,
                                    List<NodeId> attemptedNodes,
                                    String requestId,
                                    long triggeredAt) {}

    private record SliceFailureHistoryEntry(long timestamp,
                                            String requestId,
                                            String artifact,
                                            String method,
                                            List<String> attemptedNodes,
                                            String lastError) {}

    // ============================================
    // Threshold-based Alerting (CPU, Heap, etc.)
    // ============================================
    private record Threshold(double warning, double critical) {
        Option<String> severity(double value) {
            if (value >= critical) return Option.option("CRITICAL");
            if (value >= warning) return Option.option("WARNING");
            return Option.none();
        }

        double forSeverity(String severity) {
            return "CRITICAL". equals(severity)
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
