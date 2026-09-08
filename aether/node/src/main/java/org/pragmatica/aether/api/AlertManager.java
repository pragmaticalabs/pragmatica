// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent.AlertInjected;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ManagementApiResponses.AlertInjectResponse;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public class AlertManager {
    private static final Logger log = LoggerFactory.getLogger(AlertManager.class);
    private static final int MAX_ALERT_HISTORY = 100;

    private final RabiaNode<KVCommand<AetherKey>> clusterNode;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final Map<String, Threshold> thresholds = new ConcurrentHashMap<>();
    private final Map<String, ActiveAlert> activeAlerts = new ConcurrentHashMap<>();

    private final LinkedBlockingDeque<AlertHistoryEntry> alertHistory = new LinkedBlockingDeque<>(MAX_ALERT_HISTORY);

    private final Map<String, InjectedAlert> injectedAlerts = new ConcurrentHashMap<>();
    private final AtomicLong injectionSequence = new AtomicLong();

    /// Narrow event-sink shape for publishing operator-injected alerts as
    /// `ClusterEvent.AlertInjected` variants through the framework events stream. Tests can bind
    /// a no-op or recording sink; production binds `ClusterEventAggregator::emit`.
    @FunctionalInterface
    public interface EventSink {
        void emit(ClusterEvent event);
    }

    /// Optional sink for emitting `AlertInjected` events into the cluster-wide events stream.
    /// Bound post-construction because the aggregator's publisher is wired after `AlertManager`
    /// in `AetherNode`. When unbound (`readOnly` factory, unit tests without consensus), inject
    /// paths fall back to the legacy node-local map only — preserving the prior contract.
    private volatile Option<EventSink> eventSink = Option.none();
    /// Optional HLC clock for stamping emitted events. Bound alongside `eventSink`. When
    /// `eventSink` is unbound, this is unused.
    private volatile Option<HlcClock> hlcClock = Option.none();
    /// Optional cluster-wide read source for cross-node visibility on `/api/alerts`. Returns a
    /// Promise because the underlying namespace-stream consumer is async.
    private volatile Option<Supplier<Promise<List<ClusterEvent>>>> clusterEventsSource = Option.none();

    private AlertManager(RabiaNode<KVCommand<AetherKey>> clusterNode, KVStore<AetherKey, AetherValue> kvStore) {
        this.clusterNode = clusterNode;
        this.kvStore = kvStore;
    }

    /// Bind the events-stream sink + HLC clock for emitting `AlertInjected` variants. Idempotent.
    /// Called from `AetherNode` once `ClusterEventAggregator` is wired.
    public void bindEventSink(EventSink sink, HlcClock clock) {
        this.eventSink = Option.option(sink);
        this.hlcClock = Option.option(clock);
    }

    /// Bind the cross-node cluster events reader. The supplier should expose the full,
    /// up-to-date events stream (typically `ClusterEventAggregator::events`). Filtering by
    /// `AlertInjected` variant happens in `activeAlertsAsList()` to keep the binding
    /// projection-agnostic.
    public void bindClusterEventsSource(Supplier<Promise<List<ClusterEvent>>> source) {
        this.clusterEventsSource = Option.option(source);
    }

    public static AlertManager alertManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            KVStore<AetherKey, AetherValue> kvStore) {
        var manager = new AlertManager(clusterNode, kvStore);

        manager.loadThresholdsFromKvStore();
        manager.ensureDefaultThresholds();

        return manager;
    }

    // JBCT-RET-08: standalone (no-cluster) construction — clusterNode absent by design
    @SuppressWarnings("JBCT-RET-08")
    public static AlertManager readOnly(KVStore<AetherKey, AetherValue> kvStore) {
        var manager = new AlertManager(null, kvStore);

        manager.loadThresholdsFromKvStore();

        return manager;
    }

    private void loadThresholdsFromKvStore() {
        kvStore.forEach(AlertThresholdKey.class, AlertThresholdValue.class, this::loadThreshold);
        log.info("Loaded {} thresholds from KV-Store", thresholds.size());
    }

    private void loadThreshold(AlertThresholdKey thresholdKey, AlertThresholdValue thresholdValue) {
        thresholds.put(thresholdKey.metricName(),
                       new Threshold(thresholdValue.warningThreshold(), thresholdValue.criticalThreshold()));
        log.debug("Loaded threshold from KV-Store: {} warning={}, critical={}",
                  thresholdKey.metricName(),
                  thresholdValue.warningThreshold(),
                  thresholdValue.criticalThreshold());
    }

    private void ensureDefaultThresholds() {
        if (thresholds.isEmpty()) {
            thresholds.put("cpu.usage", new Threshold(0.7, 0.9));
            thresholds.put("heap.usage", new Threshold(0.7, 0.85));
            log.info("Initialized default thresholds (in-memory only until explicitly set)");
        }
    }

    @SuppressWarnings("unchecked")
    public Promise<Unit> setThreshold(String metric, double warning, double critical) {
        var key = new AetherKey.AlertThresholdKey(metric);
        var value = AetherValue.AlertThresholdValue.alertThresholdValue(metric, warning, critical);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);

        return clusterNode.<Unit> apply(List.of(command))
                          .mapToUnit()
                          .onSuccess(_ -> applyThreshold(metric, warning, critical))
                          .onFailure(cause -> log.error("Failed to persist threshold for {}: {}",
                                                        metric,
                                                        cause.message()));
    }

    private void applyThreshold(String metric, double warning, double critical) {
        thresholds.put(metric, new Threshold(warning, critical));
        log.info("Threshold set and persisted for {}: warning={}, critical={}", metric, warning, critical);
    }

    @SuppressWarnings("unchecked")
    public Promise<Unit> removeThreshold(String metric) {
        var key = new AetherKey.AlertThresholdKey(metric);
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(key);

        return clusterNode.<Unit> apply(List.of(command))
                          .mapToUnit()
                          .onSuccess(_ -> applyThresholdRemoval(metric))
                          .onFailure(cause -> log.error("Failed to persist threshold removal for {}: {}",
                                                        metric,
                                                        cause.message()));
    }

    private void applyThresholdRemoval(String metric) {
        Option.option(thresholds.remove(metric)).onPresent(_ -> log.info("Threshold removed and persisted for {}",
                                                                         metric));
    }

    public Map<String, double[]> getAllThresholds() {
        Map<String, double[]> result = new ConcurrentHashMap<>();

        thresholds.forEach((k, v) -> result.put(k, new double[]{v.warning, v.critical}));

        return result;
    }

    public void clearAlerts() {
        activeAlerts.clear();
        injectedAlerts.clear();
        log.info("All active alerts cleared");
    }

    public Promise<AlertInjectResponse> inject(String name,
                                               String severity,
                                               String message,
                                               Option<String> metric,
                                               Option<Double> value) {
        return validateInjectionInput(name, severity, message).async()
                                     .map(_ -> stampAndStoreInjection(name, severity, message, metric, value));
    }

    // RET-06: `name`/`message` are raw injection-request fields; the null/blank checks ARE the
    // parse-don't-validate entry validation.
    @SuppressWarnings("JBCT-RET-06")
    private Result<Unit> validateInjectionInput(String name, String severity, String message) {
        if (name == null || name.isBlank()) {
            return InjectionError.NAME_REQUIRED.result();
        }

        if (message == null || message.isBlank()) {
            return InjectionError.MESSAGE_REQUIRED.result();
        }

        if (!isValidSeverity(severity)) {
            return InjectionError.INVALID_SEVERITY.result();
        }

        return Result.unitResult();
    }

    private static boolean isValidSeverity(String severity) {
        return "INFO".equals(severity) || "WARNING".equals(severity) || "CRITICAL".equals(severity);
    }

    private AlertInjectResponse stampAndStoreInjection(String name,
                                                       String severity,
                                                       String message,
                                                       Option<String> metric,
                                                       Option<Double> value) {
        var timestamp = System.currentTimeMillis();
        var alertId = "injected-" + timestamp + "-" + injectionSequence.incrementAndGet();
        var alert = new InjectedAlert(alertId, name, severity, message, metric, value, timestamp);

        injectedAlerts.put(alertId, alert);
        addInjectedToHistory(alert);
        publishInjectionToClusterLog(alert);
        log.info("Injected synthetic alert id={} name={} severity={}", alertId, name, severity);

        return new AlertInjectResponse(alertId, name, severity, message, timestamp);
    }

    /// Replicate the injected alert via the cluster-wide events stream so peer nodes can return
    /// it on their `/api/alerts` reads. Failures are swallowed — the local map already holds
    /// the injection, so the originating node remains correct even if the stream publisher is
    /// briefly unavailable.
    private void publishInjectionToClusterLog(InjectedAlert alert) {
        eventSink.onPresent(sink -> hlcClock.onPresent(clock -> sink.emit(new AlertInjected(clock.now(),
                                                                                            severityFor(alert.severity),
                                                                                            alert.message,
                                                                                            buildAlertInjectMetadata(alert)))));
    }

    private static Severity severityFor(String severity) {
        return switch (severity) {
            case "CRITICAL" -> Severity.CRITICAL;
            case "WARNING" -> Severity.WARNING;
            default -> Severity.INFO;
        };
    }

    private static Map<String, String> buildAlertInjectMetadata(InjectedAlert alert) {
        var metadata = new LinkedHashMap<String, String>();

        metadata.put("alertId", alert.alertId);
        metadata.put("name", alert.name);
        metadata.put("severity", alert.severity);
        metadata.put("message", alert.message);
        metadata.put("timestamp", Long.toString(alert.timestamp));
        alert.metric.onPresent(m -> metadata.put("metric", m));
        alert.value.onPresent(v -> metadata.put("value", Double.toString(v)));

        return Map.copyOf(metadata);
    }

    private void addInjectedToHistory(InjectedAlert alert) {
        var nodeIdMarker = "@operator";
        var entry = new AlertHistoryEntry(alert.timestamp,
                                          alert.metric.or(alert.name),
                                          nodeIdMarker,
                                          alert.value.or(0.0),
                                          alert.severity,
                                          "INJECTED");

        while (!alertHistory.offerLast(entry)) {
            alertHistory.pollFirst();
        }
    }

    private enum InjectionError implements Cause {
        NAME_REQUIRED("Injected alert requires a non-blank name"),
        MESSAGE_REQUIRED("Injected alert requires a non-blank message"),
        INVALID_SEVERITY("Injected alert severity must be one of INFO, WARNING, CRITICAL");
        private final String message;
        InjectionError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    public int activeAlertCount() {
        return activeAlerts.size();
    }

    public Option<String> checkThreshold(String metric, NodeId nodeId, double value) {
        return Option.option(thresholds.get(metric)).flatMap(threshold -> evaluateThreshold(threshold,
                                                                                            metric,
                                                                                            nodeId,
                                                                                            value));
    }

    private Option<String> evaluateThreshold(Threshold threshold, String metric, NodeId nodeId, double value) {
        var alertKey = metric + ":" + nodeId.id();
        var existing = Option.option(activeAlerts.get(alertKey));

        return threshold.severity(value)
                        .onEmpty(() -> resolveExistingAlert(alertKey, existing, metric, nodeId, value))
                        .flatMap(severity -> handleAlertValue(alertKey,
                                                              existing,
                                                              severity,
                                                              metric,
                                                              nodeId,
                                                              value,
                                                              threshold));
    }

    private void resolveExistingAlert(String alertKey,
                                      Option<ActiveAlert> existing,
                                      String metric,
                                      NodeId nodeId,
                                      double value) {
        existing.onPresent(alert -> resolveAlert(alertKey, metric, nodeId, value, alert));
    }

    private void resolveAlert(String alertKey, String metric, NodeId nodeId, double value, ActiveAlert alert) {
        activeAlerts.remove(alertKey);
        addToHistory(metric, nodeId, value, alert.severity, "RESOLVED");
        broadcastAlertResolved(metric, nodeId);
    }

    private void broadcastAlertResolved(String metric, NodeId nodeId) {
        var message = "{\"type\":\"ALERT_RESOLVED\",\"timestamp\":" + System.currentTimeMillis()
                    + ",\"data\":{\"metric\":\"" + escapeJson(metric)
                    + "\",\"nodeId\":\"" + escapeJson(nodeId.id())
                    + "\",\"resolvedAt\":" + System.currentTimeMillis()
                    + "}}";

        DashboardWebSocketHandler.broadcast(message);
    }

    private Option<String> handleAlertValue(String alertKey,
                                            Option<ActiveAlert> existing,
                                            String severity,
                                            String metric,
                                            NodeId nodeId,
                                            double value,
                                            Threshold threshold) {
        var shouldTrigger = existing.filter(alert -> alert.severity.equals(severity)).isEmpty();

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

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onAlertThresholdPut(ValuePut<AlertThresholdKey, AlertThresholdValue> valuePut) {
        var thresholdKey = valuePut.cause().key();
        var thresholdValue = valuePut.cause().value();

        thresholds.put(thresholdKey.metricName(),
                       new Threshold(thresholdValue.warningThreshold(), thresholdValue.criticalThreshold()));
        log.debug("Threshold updated from cluster: {} warning={}, critical={}",
                  thresholdKey.metricName(),
                  thresholdValue.warningThreshold(),
                  thresholdValue.criticalThreshold());
    }

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01")
    public void onAlertThresholdRemove(ValueRemove<AlertThresholdKey, AlertThresholdValue> valueRemove) {
        var thresholdKey = valueRemove.cause().key();

        thresholds.remove(thresholdKey.metricName());
        log.debug("Threshold removed from cluster: {}", thresholdKey.metricName());
    }

    private String buildAlertMessage(ActiveAlert alert) {
        return "{\"type\":\"ALERT\",\"timestamp\":" + System.currentTimeMillis()
             + ",\"data\":{"
             + "\"metric\":\"" + escapeJson(alert.metric)
             + "\","
             + "\"nodeId\":\"" + escapeJson(alert.nodeId.id())
             + "\","
             + "\"value\":" + alert.value
             + ","
             + "\"threshold\":" + alert.threshold
             + ","
             + "\"severity\":\"" + escapeJson(alert.severity)
             + "\"}}";
    }

    private void addToHistory(String metric, NodeId nodeId, double value, String severity, String status) {
        var entry = new AlertHistoryEntry(System.currentTimeMillis(), metric, nodeId.id(), value, severity, status);

        while (!alertHistory.offerLast(entry)) {
            alertHistory.pollFirst();
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    public String thresholdsAsJson() {
        var sb = new StringBuilder();

        sb.append("{");
        boolean first = true;

        for (var entry : thresholds.entrySet()) {
            if (!first) sb.append(",");

            sb.append("\"").append(escapeJson(entry.getKey())).append("\":{");
            sb.append("\"warning\":").append(entry.getValue().warning).append(",");
            sb.append("\"critical\":").append(entry.getValue().critical);
            sb.append("}");
            first = false;
        }

        sb.append("}");

        return sb.toString();
    }

    /// View record for `/api/alerts` and `/api/alerts/active`. Flattens the union of
    /// `ActiveAlert` (threshold-driven, `source="threshold"`) and `InjectedAlert`
    /// (operator-driven via `POST /api/alerts/inject`, `source="injected"`). Fields not
    /// applicable to a given alert kind are emitted as `null` so consumers can discriminate
    /// by presence (e.g. `alertId` ⟹ injected, `nodeId` ⟹ threshold).
    ///
    /// Replaces the previous pre-serialized JSON String pathway (`activeAlertsAsJson` /
    /// `alertHistoryAsJson`): wrapping a String in an Object-typed handler caused Jackson
    /// to double-encode the response, breaking integration assertions on field substrings.
    public record AlertView(String alertId,
                            String name,
                            String severity,
                            String message,
                            String source,
                            String metric,
                            Double value,
                            String nodeId,
                            Double threshold,
                            Long triggeredAt,
                            Long timestamp) {}

    /// View record for `/api/alerts/history`. Mirrors `AlertHistoryEntry` 1:1 as a public
    /// type so Jackson can serialize without the String-double-encoding bug.
    public record AlertHistoryView(long timestamp,
                                   String metric,
                                   String nodeId,
                                   double value,
                                   String severity,
                                   String status) {}

    /// View record for `/api/alerts/thresholds`. Flattens the `Map<String, Threshold>` into
    /// a list. Replaces the JSON-as-String `thresholdsAsJson` path.
    public record ThresholdView(String metric, double warning, double critical) {}

    // JBCT-RET-08: Jackson view DTO — null is the absent-JSON-field representation, wire-contract-fixed
    @SuppressWarnings("JBCT-RET-08")
    public Promise<List<AlertView>> activeAlertsAsList() {
        var list = new java.util.ArrayList<AlertView>(activeAlerts.size() + injectedAlerts.size());
        var seenInjectedIds = new java.util.HashSet<String>();

        for (var alert : activeAlerts.values()) {
            list.add(new AlertView(null,
                                   null,
                                   alert.severity,
                                   null,
                                   "threshold",
                                   alert.metric,
                                   alert.value,
                                   alert.nodeId.id(),
                                   alert.threshold,
                                   alert.triggeredAt,
                                   null));
        }

        for (var alert : injectedAlerts.values()) {
            seenInjectedIds.add(alert.alertId);
            list.add(new AlertView(alert.alertId,
                                   alert.name,
                                   alert.severity,
                                   alert.message,
                                   "injected",
                                   alert.metric.or((String) null),
                                   alert.value.or((Double) null),
                                   null,
                                   null,
                                   null,
                                   alert.timestamp));
        }
        // #926: node-health alerts reach the SAME /api/alerts surface as every other kind. An alert
        // raised but not rendered is not operator-visible, which is the defect this ticket is about one
        // layer up. Discriminated by source="node_health"; nodeId names the FAILED node and message
        // carries the reason.
        for (var alert : activeNodeHealthAlerts.values()) {
            list.add(new AlertView(alert.alertId(),
                                   "node.failed",
                                   alert.severity().name(),
                                   alert.reason(),
                                   "node_health",
                                   null,
                                   null,
                                   alert.nodeId().id(),
                                   null,
                                   alert.timestamp(),
                                   alert.timestamp()));
        }

        return appendClusterWideInjectedAlerts(list, seenInjectedIds).map(_ -> List.copyOf(list));
    }

    private Promise<Unit> appendClusterWideInjectedAlerts(java.util.List<AlertView> sink,
                                                          java.util.Set<String> seenIds) {
        return clusterEventsSource.fold(() -> Promise.success(Unit.unit()),
                                        source -> source.get()
                                                        .map(events -> {
                                                                 for (var event : events) {
                                                                 if (! (event instanceof AlertInjected)) {
                                                                 continue;
                                                             }

                                                                 var view = projectClusterEventToAlertView(event);

                                                                 if (view == null) {
                                                                 continue;
                                                             }

                                                                 if (view.alertId() != null && !seenIds.add(view.alertId())) {
                                                                 continue;
                                                             }

                                                                 sink.add(view);
                                                             }

                                                                 return Unit.unit();
                                                             }));
    }

    // JBCT-RET-08: Jackson view DTO — null is the absent-JSON-field representation, wire-contract-fixed
    @NullReturn
    @SuppressWarnings("JBCT-RET-08")
    private static AlertView projectClusterEventToAlertView(ClusterEvent event) {
        var details = event.details();
        var alertId = details.get("alertId");

        if (alertId == null) {
            return null;
        }

        var name = details.getOrDefault("name", "");
        var severity = details.getOrDefault("severity",
                                            event.severity().name());
        var message = details.getOrDefault("message", event.summary());
        var metric = details.get("metric");
        var value = parseDoubleOrNull(details.get("value"));
        var timestamp = parseLongOrNull(details.get("timestamp"));

        return new AlertView(alertId, name, severity, message, "injected", metric, value, null, null, null, timestamp);
    }

    // RET-06: `raw` is a nullable value from a JDK Map.get; the null guard is a framework boundary.
    @NullReturn
    @SuppressWarnings("JBCT-RET-06")
    private static Double parseDoubleOrNull(String raw) {
        if (raw == null) {
            return null;
        }

        return org.pragmatica.lang.parse.Number.parseDouble(raw)
                                               .option()
                                               .or((Double) null);
    }

    // RET-06: `raw` is a nullable value from a JDK Map.get; the null guard is a framework boundary.
    @NullReturn
    @SuppressWarnings("JBCT-RET-06")
    private static Long parseLongOrNull(String raw) {
        if (raw == null) {
            return null;
        }

        return org.pragmatica.lang.parse.Number.parseLong(raw)
                                               .option()
                                               .or((Long) null);
    }

    public List<AlertHistoryView> alertHistoryAsList() {
        var list = new java.util.ArrayList<AlertHistoryView>(alertHistory.size());

        for (var entry : alertHistory) {
            list.add(new AlertHistoryView(entry.timestamp,
                                          entry.metric,
                                          entry.nodeId,
                                          entry.value,
                                          entry.severity,
                                          entry.status));
        }

        return List.copyOf(list);
    }

    public List<ThresholdView> thresholdsAsList() {
        var list = new java.util.ArrayList<ThresholdView>(thresholds.size());

        for (var entry : thresholds.entrySet()) {
            list.add(new ThresholdView(entry.getKey(), entry.getValue().warning, entry.getValue().critical));
        }

        return List.copyOf(list);
    }

    @SuppressWarnings("JBCT-PAT-01")
    public String activeAlertsAsJson() {
        var sb = new StringBuilder();

        sb.append("[");
        boolean first = true;
        var seenInjectedIds = new java.util.HashSet<String>();

        for (var alert : activeAlerts.values()) {
            if (!first) sb.append(",");

            sb.append("{");
            sb.append("\"metric\":\"").append(escapeJson(alert.metric)).append("\",");
            sb.append("\"nodeId\":\"").append(escapeJson(alert.nodeId.id())).append("\",");
            sb.append("\"value\":").append(alert.value).append(",");
            sb.append("\"threshold\":").append(alert.threshold).append(",");
            sb.append("\"severity\":\"").append(escapeJson(alert.severity)).append("\",");
            sb.append("\"triggeredAt\":").append(alert.triggeredAt);
            sb.append("}");
            first = false;
        }

        for (var alert : injectedAlerts.values()) {
            seenInjectedIds.add(alert.alertId);
            if (!first) sb.append(",");

            sb.append("{");
            sb.append("\"alertId\":\"").append(escapeJson(alert.alertId)).append("\",");
            sb.append("\"name\":\"").append(escapeJson(alert.name)).append("\",");
            sb.append("\"severity\":\"").append(escapeJson(alert.severity)).append("\",");
            sb.append("\"message\":\"").append(escapeJson(alert.message)).append("\",");
            sb.append("\"metric\":\"").append(escapeJson(alert.metric.or(""))).append("\",");
            sb.append("\"value\":").append(alert.value.or(0.0)).append(",");
            sb.append("\"source\":\"injected\",");
            sb.append("\"timestamp\":").append(alert.timestamp);
            sb.append("}");
            first = false;
        }
        // Cross-node UNION: include ALERT_INJECTED events from peer nodes via the replicated
        // log. Dedup by alertId so the originator's local entry is not duplicated.
        for (var injection : clusterWideInjectedAlerts(seenInjectedIds)) {
            if (!first) sb.append(",");

            sb.append("{");
            sb.append("\"alertId\":\"").append(escapeJson(injection.alertId())).append("\",");
            sb.append("\"name\":\"").append(escapeJson(injection.name())).append("\",");
            sb.append("\"severity\":\"").append(escapeJson(injection.severity())).append("\",");
            sb.append("\"message\":\"").append(escapeJson(injection.message())).append("\",");
            sb.append("\"metric\":\"")
              .append(escapeJson(injection.metric() == null
                                 ? ""
                                 : injection.metric()))
              .append("\",");
            sb.append("\"value\":").append(injection.value() == null
                                           ? 0.0
                                           : injection.value()).append(",");
            sb.append("\"source\":\"injected\",");
            sb.append("\"timestamp\":").append(injection.timestamp() == null
                                               ? 0L
                                               : injection.timestamp());
            sb.append("}");
            first = false;
        }

        sb.append("]");

        return sb.toString();
    }

    private List<AlertView> clusterWideInjectedAlerts(java.util.Set<String> seenIds) {
        // Legacy sync path for `activeAlertsAsJson()`. Returns empty — the cluster-wide read
        // requires Promise composition (see `activeAlertsAsList()` which is the async replacement).
        // `activeAlertsAsJson()` is being phased out per the AlertView migration note at line 427.
        return java.util.List.of();
    }

    @SuppressWarnings("JBCT-PAT-01")
    public String alertHistoryAsJson() {
        var sb = new StringBuilder();

        sb.append("[");
        boolean first = true;

        for (var entry : alertHistory) {
            if (!first) sb.append(",");

            sb.append("{");
            sb.append("\"timestamp\":").append(entry.timestamp).append(",");
            sb.append("\"metric\":\"").append(escapeJson(entry.metric)).append("\",");
            sb.append("\"nodeId\":\"").append(escapeJson(entry.nodeId)).append("\",");
            sb.append("\"value\":").append(entry.value).append(",");
            sb.append("\"severity\":\"").append(escapeJson(entry.severity)).append("\",");
            sb.append("\"status\":\"").append(escapeJson(entry.status)).append("\"");
            sb.append("}");
            first = false;
        }

        sb.append("]");

        return sb.toString();
    }

    @MessageReceiver
    public void onAllInstancesFailed(SliceFailureEvent.AllInstancesFailed event) {
        var alertKey = "slice.all_failed:" + event.artifact().asString() + "/" + event.method().name();
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
                  event.attemptedNodes().size(),
                  event.lastError().map(Cause::message).or("unknown error"));
    }

    private final Map<String, SliceFailureAlert> activeSliceFailureAlerts = new ConcurrentHashMap<>();

    private final LinkedBlockingDeque<SliceFailureHistoryEntry> sliceFailureHistory = new LinkedBlockingDeque<>(MAX_ALERT_HISTORY);

    private void addSliceFailureToHistory(SliceFailureEvent.AllInstancesFailed event) {
        var entry = new SliceFailureHistoryEntry(event.timestamp(),
                                                 event.requestId(),
                                                 event.artifact().asString(),
                                                 event.method().name(),
                                                 event.attemptedNodes().stream().map(NodeId::id).toList(),
                                                 event.lastError().map(Cause::message).or("unknown"));

        while (!sliceFailureHistory.offerLast(entry)) {
            sliceFailureHistory.pollFirst();
        }
    }

    public List<SliceFailureAlert> getActiveSliceFailureAlerts() {
        return List.copyOf(activeSliceFailureAlerts.values());
    }

    /// Hard cap on retained node-health alerts. `activeNodeHealthAlerts` is keyed by the FAILED node's
    /// id, and CTM auto-heal mints a FRESH random id for a replacement rather than reusing the departed
    /// one, so an id-exact clear can never match a replaced node. Without a bound, every replacement
    /// under churn would add a permanent entry, growing heap and the `/api/alerts` payload without
    /// limit. Mirrors the existing `MAX_ALERT_HISTORY` bound on `alertHistory`.
    private static final int MAX_NODE_HEALTH_ALERTS = 64;

    /// Membership-event causes that mean the node ANNOUNCED its departure rather than died.
    ///
    /// **`SwimDeparted` is NOT in this set, and putting it here was a blocking regression.** Round 2 of
    /// #926 included it on the strength of `MembershipFsm.onSwimDeparted`'s docstring ("SWIM reported
    /// `id` DEPARTED gracefully") — a comment that contradicts its own producer.
    /// `SwimProtocol.emitFaultyEdgePair` delivers `FaultyObserved` and `DepartedObserved` **as a pair at
    /// the FAULTY edge**, because "FAULTY IS confirmed death (canonical SWIM) … The death broadcast
    /// therefore fires AT the FAULTY edge" — deliberately, to cut `NODE_FAILED` latency inside the 60s
    /// SLO. `DepartedObserved` routes to `onSwimDeparted` (`AetherNode:4968`), which dispatches
    /// `SwimDeparted`. **So `SwimDeparted` is SWIM's death broadcast and is the PRIMARY crash path**,
    /// not a graceful goodbye: with it in this set, `kill -9` raised no CRITICAL alert at all.
    ///
    /// That is precisely the failure [`#onNodeFailed`] warns against one paragraph down — suppressing a
    /// real failure re-creates the defect #926 exists to remove — reached by trusting a docstring
    /// instead of its producer.
    ///
    /// `DrainRequested` alone is sound: it is raised only by the operator/controller drain command, and
    /// nothing in SWIM's failure detection produces it. **Do not add a cause here without tracing it to
    /// the code that RAISES it.** A graceful shutdown that is not operator-driven is currently
    /// indistinguishable from a crash at this layer, so it stays noisy — noisy beats silent on a
    /// failure-detection surface, and no signal SWIM carries can separate them.
    private static final java.util.Set<String> GRACEFUL_DEPARTURE_CAUSES = java.util.Set.of("DrainRequested");

    /// Active node-health alerts, keyed by [`AlertEvent.NodeHealthAlert#alertId`] (derived from the
    /// failed node id). Per-node local state — never replicated, never consensus-backed — which is
    /// precisely why it survives the conditions of #926. Bounded by [`#MAX_NODE_HEALTH_ALERTS`].
    private final Map<String, AlertEvent.NodeHealthAlert> activeNodeHealthAlerts = new ConcurrentHashMap<>();

    /// Insertion order for [`#activeNodeHealthAlerts`], oldest first — the eviction order.
    ///
    /// Round 2 ordered eviction by `min(timestamp)`. That was both unpinned (flipping it to `max` left
    /// the suite green, since the bound test asserted only size) and **unpinnable**: alerts raised in a
    /// tight loop share a `System.currentTimeMillis()` value, so "oldest" was not even well defined.
    /// An insertion queue makes the order deterministic and therefore assertable.
    private final java.util.concurrent.ConcurrentLinkedQueue<String> nodeHealthAlertOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /// Node ids observed announcing a graceful departure, id → wall-clock millis. Bounded by
    /// [`#MAX_NODE_HEALTH_ALERTS`]; when full, new marks are DROPPED rather than evicting, so the
    /// failure mode is a spurious CRITICAL alert and never a suppressed one.
    private final Map<String, Long> announcedDeparture = new ConcurrentHashMap<>();

    /// Feed for `MembershipFsm` transitions (#926 round 2). Records that `nodeId` announced a departure,
    /// so the DEAD edge that follows can be told apart from a crash.
    ///
    /// The DEAD edge itself cannot make that distinction — a graceful `SwimDeparted` and a drain both
    /// reach DEAD through the same `Stopped` transition a failure does — so without this every rolling
    /// restart raised a CRITICAL node-health alert on every surviving node. An alert surface that fires
    /// CRITICAL during routine planned operations gets muted, and a muted alert is the same end state
    /// as the silence #926 exists to fix, reached from the opposite direction.
    ///
    /// Ordering is guaranteed, and by a different mechanism than round 2 claimed. The earlier note said
    /// the transition and confirmed-departure emissions share one `emissions` list; **that is false** —
    /// the two arrive from SEPARATE dispatches (e.g. `DrainRequested` then `Stopped`), each building
    /// its own list. What actually holds the order is `MemberTracking.dispatch`, which runs
    /// `synchronized (transitionGuard) { applyEvent(event).forEach(Runnable::run) }`: every dispatch for
    /// a member is serialised on that member's guard and runs its staged fan-out synchronously before
    /// returning, so an earlier dispatch's transition record has always run before a later dispatch's
    /// DEAD hooks. **Recorded precisely because a guarantee resting on a fictional mechanism cannot be
    /// re-checked when the code moves** — if that fan-out ever becomes asynchronous, this ordering is
    /// what breaks, and the guard is where to look.
    ///
    /// Within a single dispatch the order is also fixed: `applyEvent` stages the calls "in the exact
    /// order the pre-#929 inline code fired them (transition, JOINED delta, DEPARTING hook,
    /// DEPARTING-recovery hook, then the DEAD hooks)".
    @Contract
    public void noteMembershipTransition(NodeId nodeId, String cause) {
        if (!GRACEFUL_DEPARTURE_CAUSES.contains(cause)) {
            return;
        }

        if (announcedDeparture.size() >= MAX_NODE_HEALTH_ALERTS && !announcedDeparture.containsKey(nodeId.id())) {
            return;
        }

        announcedDeparture.put(nodeId.id(), System.currentTimeMillis());
    }

    /// Raise a node-health alert for a confirmed member death (#926).
    ///
    /// Invoked from the ungated `MembershipFsm` DEAD edge on EVERY node that confirms the death, so it
    /// is reachable with no leader and no quorum. Node health previously had no alerting path at all:
    /// `AlertEvent` carried only threshold, slice-failure and resolved variants, and repo-wide
    /// "unhealthy" in `aether/node/src/main` appeared twice, both rendering a status string into an
    /// HTTP response.
    ///
    /// GUARANTEE: **at-most-one active alert per ABRUPTLY departed node, per observing node.** The map
    /// key is derived from the failed node alone, so a repeated observation of the same death replaces
    /// rather than accumulates — idempotent, needing no dedup token and no coordination. Each node keeps
    /// its own map, so there is no cross-node duplication to reconcile: the alert is a local judgment
    /// about a remote peer, exposed on the observing node's own `/api/alerts`.
    ///
    /// A departure this node saw ANNOUNCED (see [`#noteMembershipTransition`]) is logged at INFO and
    /// raises NO alert. The mark is CONSUMED on read, so a node that gracefully departs, rejoins and
    /// later crashes still alerts on the crash. **The bias is deliberate and one-directional:** an
    /// unmarked departure always alerts, so a missed or dropped mark costs a spurious CRITICAL, never a
    /// silent one. Suppressing a real failure would re-create the defect this ticket exists to remove.
    ///
    /// Cleared by [`#clearNodeHealthAlert`] when the node rejoins. Raising without clearing would leave
    /// a permanently red signal, which trains an operator to ignore the surface.
    @Contract
    public void onNodeFailed(NodeId failed, NodeId observedBy) {
        if (announcedDeparture.remove(failed.id()) != null) {
            log.info("Node {} departed gracefully (announced; observed by {}) — no alert raised",
                     failed.id(),
                     observedBy.id());

            return;
        }

        var alert = AlertEvent.NodeHealthAlert.nodeFailed(failed, observedBy);

        if (activeNodeHealthAlerts.put(alert.alertId(), alert) == null) {
            nodeHealthAlertOrder.add(alert.alertId());
        }

        evictOldestNodeHealthAlertIfOverCap();
        log.error("CRITICAL: node {} confirmed failed (observed by {}) — cluster membership degraded",
                  failed.id(),
                  observedBy.id());
    }

    /// Drop the OLDEST-INSERTED alerts once the map exceeds [`#MAX_NODE_HEALTH_ALERTS`]. A replaced
    /// node's alert can never be cleared by id (the replacement carries a new one), so the bound — not
    /// the clear path — is what keeps this map finite under sustained churn. Oldest-first is the
    /// deliberate direction: the most recent failures are the ones an operator is still acting on.
    private void evictOldestNodeHealthAlertIfOverCap() {
        while (activeNodeHealthAlerts.size() > MAX_NODE_HEALTH_ALERTS) {
            var oldest = nodeHealthAlertOrder.poll();

            if (oldest == null) {
                return;
            }

            activeNodeHealthAlerts.remove(oldest);
        }
    }

    /// Resolve the node-health alert for `rejoined` (#926). Wired to the transport `PeerJoined`
    /// handshake — the same ungated surface that sources NODE_JOINED — so recovery is exactly as
    /// reachable as the failure it clears. A no-op when no alert is active for that node.
    ///
    /// This clear is id-exact and therefore CANNOT resolve a CTM-replaced node, whose replacement boots
    /// under a freshly minted random id. That case is handled by the bound above, not here; the honest
    /// statement is that a replaced node's alert ages out under churn rather than being resolved.
    @Contract
    public void clearNodeHealthAlert(NodeId rejoined) {
        announcedDeparture.remove(rejoined.id());
        nodeHealthAlertOrder.remove(AlertEvent.NodeHealthAlert.alertId(rejoined));
        Option.option(activeNodeHealthAlerts.remove(AlertEvent.NodeHealthAlert.alertId(rejoined))).onPresent(cleared -> log.info("Node-health alert resolved for {} — node rejoined (was: {})",
                                                                                                                                 rejoined.id(),
                                                                                                                                 cleared.reason()));
    }

    public List<AlertEvent.NodeHealthAlert> getActiveNodeHealthAlerts() {
        return List.copyOf(activeNodeHealthAlerts.values());
    }

    public void clearSliceFailureAlert(Artifact artifact, MethodName method) {
        var alertKey = "slice.all_failed:" + artifact.asString() + "/" + method.name();

        activeSliceFailureAlerts.remove(alertKey);
        log.info("Cleared slice failure alert for {}.{}", artifact, method);
    }

    @SuppressWarnings("JBCT-PAT-01")
    public String sliceFailureAlertsAsJson() {
        var sb = new StringBuilder();

        sb.append("[");
        boolean first = true;

        for (var alert : activeSliceFailureAlerts.values()) {
            if (!first) sb.append(",");

            sb.append("{");
            sb.append("\"type\":\"SLICE_ALL_INSTANCES_FAILED\",");
            sb.append("\"severity\":\"CRITICAL\",");
            sb.append("\"artifact\":\"").append(escapeJson(alert.artifact.asString())).append("\",");
            sb.append("\"method\":\"").append(escapeJson(alert.method.name())).append("\",");
            sb.append("\"requestId\":\"").append(escapeJson(alert.requestId)).append("\",");
            sb.append("\"attemptedNodes\":[");
            boolean firstNode = true;

            for (var nodeId : alert.attemptedNodes) {
                if (!firstNode) sb.append(",");

                sb.append("\"").append(escapeJson(nodeId.id())).append("\"");
                firstNode = false;
            }

            sb.append("],");
            sb.append("\"lastError\":\"")
              .append(escapeJson(alert.lastError.map(Cause::message).or("unknown")))
              .append("\",");
            sb.append("\"timestamp\":").append(alert.triggeredAt);
            sb.append("}");
            first = false;
        }

        sb.append("]");

        return sb.toString();
    }

    @SuppressWarnings("JBCT-PAT-01")
    public String sliceFailureHistoryAsJson() {
        var sb = new StringBuilder();

        sb.append("[");
        boolean first = true;

        for (var entry : sliceFailureHistory) {
            if (!first) sb.append(",");

            sb.append("{");
            sb.append("\"timestamp\":").append(entry.timestamp).append(",");
            sb.append("\"requestId\":\"").append(escapeJson(entry.requestId)).append("\",");
            sb.append("\"artifact\":\"").append(escapeJson(entry.artifact)).append("\",");
            sb.append("\"method\":\"").append(escapeJson(entry.method)).append("\",");
            sb.append("\"attemptedNodes\":[");
            boolean firstNode = true;

            for (var nodeId : entry.attemptedNodes) {
                if (!firstNode) sb.append(",");

                sb.append("\"").append(escapeJson(nodeId)).append("\"");
                firstNode = false;
            }

            sb.append("],");
            sb.append("\"lastError\":\"").append(escapeJson(entry.lastError)).append("\"");
            sb.append("}");
            first = false;
        }

        sb.append("]");

        return sb.toString();
    }

    private String escapeJson(String s) {
        return Option.option(s)
                     .map(AlertManager::doEscapeJson)
                     .or("");
    }

    private static String doEscapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public record SliceFailureAlert(Artifact artifact,
                                    MethodName method,
                                    Option<Cause> lastError,
                                    List<NodeId> attemptedNodes,
                                    String requestId,
                                    long triggeredAt) {}

    private record SliceFailureHistoryEntry(long timestamp,
                                            String requestId,
                                            String artifact,
                                            String method,
                                            List<String> attemptedNodes,
                                            String lastError) {}

    private record Threshold(double warning, double critical) {
        Option<String> severity(double value) {
            if (value >= critical) return Option.option("CRITICAL");

            if (value >= warning) return Option.option("WARNING");

            return Option.none();
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

    private record InjectedAlert(String alertId,
                                 String name,
                                 String severity,
                                 String message,
                                 Option<String> metric,
                                 Option<Double> value,
                                 long timestamp) {}
}
