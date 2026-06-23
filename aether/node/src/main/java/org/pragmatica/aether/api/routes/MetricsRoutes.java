// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ArtifactMetricsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.BackfillMetricsRequest;
import org.pragmatica.aether.api.ManagementApiResponses.BackfillMetricsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ComprehensiveMetricsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.DeploymentMetrics;
import org.pragmatica.aether.api.ManagementApiResponses.DerivedMetricsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ErrorResponse;
import org.pragmatica.aether.api.ManagementApiResponses.InvocationMetricsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.InvocationSnapshot;
import org.pragmatica.aether.api.ManagementApiResponses.MetricsFullResponse;
import org.pragmatica.aether.api.ManagementApiResponses.NodeMetric;
import org.pragmatica.aether.api.ManagementApiResponses.SlowInvocation;
import org.pragmatica.aether.api.ManagementApiResponses.SlowInvocationsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.StrategyResponse;
import org.pragmatica.aether.api.ManagementApiResponses.SubsystemTimeoutCount;
import org.pragmatica.aether.api.ManagementApiResponses.TimeoutMetricsResponse;
import org.pragmatica.aether.metrics.ClusterSyncCollector.MetricsSnapshot;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.invocation.MetricsError;
import org.pragmatica.aether.metrics.invocation.ThresholdStrategy;
import org.pragmatica.aether.metrics.timeout.TimeoutMetricsRegistry;
import org.pragmatica.aether.metrics.timeout.TimeoutSubsystem;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.QueryParameter.aString;


public final class MetricsRoutes implements RouteSource {
    private static final ContentType PROMETHEUS_CONTENT_TYPE = ContentType.contentType("text/plain; version=0.0.4; charset=utf-8",
                                                                                       ContentCategory.TEXT);

    private static final String DEV_MODE_ENV = "AETHER_INSECURE_DEV_MODE";

    private final Supplier<ManageableNode> nodeSupplier;
    private final ObservabilityRegistry observability;
    private final TimeoutMetricsRegistry timeoutMetrics;
    private final BooleanSupplier devModeEnabled;

    private MetricsRoutes(Supplier<ManageableNode> nodeSupplier,
                          ObservabilityRegistry observability,
                          TimeoutMetricsRegistry timeoutMetrics,
                          BooleanSupplier devModeEnabled) {
        this.nodeSupplier = nodeSupplier;
        this.observability = observability;
        this.timeoutMetrics = timeoutMetrics;
        this.devModeEnabled = devModeEnabled;
    }

    public static MetricsRoutes metricsRoutes(Supplier<ManageableNode> nodeSupplier,
                                              ObservabilityRegistry observability) {
        return new MetricsRoutes(nodeSupplier,
                                 observability,
                                 TimeoutMetricsRegistry.timeoutMetricsRegistry(),
                                 MetricsRoutes::devModeFromEnv);
    }

    /// Wiring-friendly factory that lets `ManagementServer` inject the
    /// node-level `TimeoutMetricsRegistry` singleton — the same instance the
    /// subsystem timeout-fire paths increment (P-NEW-A, 2026-05-21).
    public static MetricsRoutes metricsRoutes(Supplier<ManageableNode> nodeSupplier,
                                              ObservabilityRegistry observability,
                                              TimeoutMetricsRegistry timeoutMetrics) {
        return new MetricsRoutes(nodeSupplier, observability, timeoutMetrics, MetricsRoutes::devModeFromEnv);
    }

    /// Test-friendly factory: callers inject both the registry and the
    /// dev-mode flag directly rather than mutating the JVM-wide environment.
    public static MetricsRoutes metricsRoutes(Supplier<ManageableNode> nodeSupplier,
                                              ObservabilityRegistry observability,
                                              TimeoutMetricsRegistry timeoutMetrics,
                                              BooleanSupplier devModeEnabled) {
        return new MetricsRoutes(nodeSupplier, observability, timeoutMetrics, devModeEnabled);
    }

    private static boolean devModeFromEnv() {
        return "true".equalsIgnoreCase(System.getenv(DEV_MODE_ENV));
    }

    /// Package-private accessor for unit tests that exercise the backfill
    /// handler without standing up the full HTTP layer.
    Promise<BackfillMetricsResponse> handleBackfillForTest(BackfillMetricsRequest req) {
        return handleBackfill(req);
    }

    /// Package-private accessor for unit tests that exercise the timeout-
    /// metrics handler synchronously.
    TimeoutMetricsResponse buildTimeoutMetricsForTest() {
        return buildTimeoutMetricsResponse();
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<MetricsFullResponse> route(ManagementRoute.METRICS).toJson(this::buildMetricsResponse),
                         ManagementRoutes.<ComprehensiveMetricsResponse> route(ManagementRoute.METRICS_COMPREHENSIVE).toJson(this::buildComprehensiveMetricsResponse),
                         ManagementRoutes.<DerivedMetricsResponse> route(ManagementRoute.METRICS_DERIVED).toJson(this::buildDerivedMetricsResponse),
                         ManagementRoutes.<String> route(ManagementRoute.METRICS_PROMETHEUS)
                                         .to(_ -> Promise.success(observability.scrape()))
                                         .as(PROMETHEUS_CONTENT_TYPE),
                         ManagementRoutes.<List<NodeMetric>> route(ManagementRoute.NODE_METRICS).toJson(this::buildNodeMetricsResponse),
                         ManagementRoutes.<List<NodeMetric>> route(ManagementRoute.NODE_METRICS_GET)
                                         .withPath(org.pragmatica.http.routing.PathParameter.aString())
                                         .to(__ -> org.pragmatica.lang.Promise.success(buildNodeMetricsResponse()))
                                         .asJson(),
                         ManagementRoutes.<ArtifactMetricsResponse> route(ManagementRoute.ARTIFACT_METRICS).toJson(this::buildArtifactMetricsResponse),
                         ManagementRoutes.<InvocationMetricsResponse> route(ManagementRoute.INVOCATION_METRICS)
                                         .withQuery(aString("artifact"),
                                                    aString("method"))
                                         .toValue(this::buildInvocationMetricsResponse)
                                         .asJson(),
                         ManagementRoutes.<SlowInvocationsResponse> route(ManagementRoute.INVOCATION_METRICS_SLOW).toJson(this::buildSlowInvocationsResponse),
                         ManagementRoutes.<StrategyResponse> route(ManagementRoute.INVOCATION_METRICS_STRATEGY_GET).toJson(this::buildStrategyResponse),
                         ManagementRoutes.<ErrorResponse> route(ManagementRoute.INVOCATION_METRICS_STRATEGY_SET)
                                         .to(_ -> HttpError.httpError(HttpStatus.NOT_IMPLEMENTED,
                                                                      MetricsError.StrategyChangeNotSupported.INSTANCE).<ErrorResponse> promise())
                                         .asJson(),
                         ManagementRoutes.<Object> route(ManagementRoute.METRICS_HISTORY)
                                         .withQuery(aString("range"))
                                         .toValue(this::buildHistoryResponse)
                                         .asJson(),
                         ManagementRoutes.<Map<String, Number>> route(ManagementRoute.METRICS_TRANSPORT).toJson(this::buildTransportMetricsResponse),
                         ManagementRoutes.<TimeoutMetricsResponse> route(ManagementRoute.METRICS_TIMEOUTS).toJson(this::buildTimeoutMetricsResponse),
                         ManagementRoutes.<BackfillMetricsResponse> route(ManagementRoute.METRICS_BACKFILL)
                                         .withBody(BackfillMetricsRequest.class)
                                         .toJson(this::handleBackfill));
    }

    private Map<String, Number> buildTransportMetricsResponse() {
        return nodeSupplier.get()
                           .transportMetrics();
    }

    private MetricsFullResponse buildMetricsResponse() {
        var node = nodeSupplier.get();

        return new MetricsFullResponse(buildLoadMetrics(node), buildDeploymentMetrics(node));
    }

    private Map<String, Map<String, Double>> buildLoadMetrics(ManageableNode node) {
        Map<String, Map<String, Double>> load = new HashMap<>();

        for (var entry : node.metricsCollector().allMetrics().entrySet()) {
            load.put(entry.getKey().id(),
                     entry.getValue());
        }

        return load;
    }

    private Map<String, List<DeploymentMetrics>> buildDeploymentMetrics(ManageableNode node) {
        Map<String, List<DeploymentMetrics>> deployments = new HashMap<>();

        for (var entry : node.deploymentMetricsCollector().allDeploymentMetrics().entrySet()) {
            var metricsList = entry.getValue().stream().map(this::toDeploymentMetrics).toList();

            deployments.put(entry.getKey().asString(),
                            metricsList);
        }

        return deployments;
    }

    private DeploymentMetrics toDeploymentMetrics(org.pragmatica.aether.metrics.deployment.DeploymentMetrics m) {
        return new DeploymentMetrics(m.nodeId().id(),
                                     m.status().name(),
                                     m.fullDeploymentTime(),
                                     m.netDeploymentTime(),
                                     m.transitionLatencies(),
                                     m.startTime(),
                                     m.activeTime());
    }

    private ComprehensiveMetricsResponse buildComprehensiveMetricsResponse() {
        var node = nodeSupplier.get();
        var recent = node.snapshotCollector().minuteAggregator().recent(1);

        if (recent.isEmpty()) {
            return new ComprehensiveMetricsResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        var agg = recent.getFirst();

        return new ComprehensiveMetricsResponse(agg.minuteTimestamp(),
                                                agg.avgCpuUsage(),
                                                agg.avgHeapUsage(),
                                                agg.avgEventLoopLagMs(),
                                                agg.avgLatencyMs(),
                                                agg.totalInvocations(),
                                                agg.totalGcPauseMs(),
                                                agg.latencyP50(),
                                                agg.latencyP95(),
                                                agg.latencyP99(),
                                                agg.errorRate(),
                                                agg.eventCount(),
                                                agg.sampleCount());
    }

    private DerivedMetricsResponse buildDerivedMetricsResponse() {
        var node = nodeSupplier.get();
        var derived = node.snapshotCollector().derivedMetrics();

        return new DerivedMetricsResponse(derived.requestRate(),
                                          derived.errorRate(),
                                          derived.gcRate(),
                                          derived.latencyP50(),
                                          derived.latencyP95(),
                                          derived.latencyP99(),
                                          derived.eventLoopSaturation(),
                                          derived.heapSaturation(),
                                          derived.cpuTrend(),
                                          derived.latencyTrend(),
                                          derived.errorTrend(),
                                          derived.healthScore(),
                                          derived.stressed(),
                                          derived.hasCapacity());
    }

    private List<NodeMetric> buildNodeMetricsResponse() {
        var node = nodeSupplier.get();
        var allMetrics = node.metricsCollector().allMetrics();
        var result = new ArrayList<NodeMetric>();

        for (var entry : allMetrics.entrySet()) {
            var nodeId = entry.getKey();
            var metrics = entry.getValue();

            result.add(new NodeMetric(nodeId.id(),
                                      metrics.getOrDefault("cpuUsage", 0.0),
                                      metrics.getOrDefault("heapUsedMb", 0.0).longValue(),
                                      metrics.getOrDefault("heapMaxMb", 0.0).longValue()));
        }

        return result;
    }

    private ArtifactMetricsResponse buildArtifactMetricsResponse() {
        var node = nodeSupplier.get();
        var collector = node.artifactMetricsCollector();
        var storeMetrics = collector.storeMetrics();
        var deployedArtifacts = collector.deployedArtifacts();
        var memoryMB = storeMetrics.memoryBytes() / (1024.0 * 1024.0);

        return new ArtifactMetricsResponse(storeMetrics.artifactCount(),
                                           storeMetrics.chunkCount(),
                                           storeMetrics.memoryBytes(),
                                           String.format("%.2f", memoryMB),
                                           deployedArtifacts.size(),
                                           deployedArtifacts.stream().map(a -> a.asString()).toList());
    }

    private InvocationMetricsResponse buildInvocationMetricsResponse(Option<String> artifactFilter,
                                                                     Option<String> methodFilter) {
        var snapshots = nodeSupplier.get().invocationMetrics().snapshot().stream().filter(snapshot -> matchesFilters(snapshot,
                                                                                                                     artifactFilter,
                                                                                                                     methodFilter)).map(this::toInvocationSnapshot).toList();

        return new InvocationMetricsResponse(snapshots);
    }

    private boolean matchesFilters(InvocationMetricsCollector.MethodSnapshot snapshot,
                                   Option<String> artifactFilter,
                                   Option<String> methodFilter) {
        boolean matchesArtifact = artifactFilter.map(filter -> snapshot.artifact()
                                                                       .asString()
                                                                       .contains(filter)).or(true);
        boolean matchesMethod = methodFilter.map(filter -> snapshot.methodName()
                                                                   .name()
                                                                   .equals(filter)).or(true);

        return matchesArtifact && matchesMethod;
    }

    private InvocationSnapshot toInvocationSnapshot(InvocationMetricsCollector.MethodSnapshot snapshot) {
        var metrics = snapshot.metrics();
        var avgDurationMs = metrics.count() > 0
                            ? metrics.totalDurationNs() / metrics.count() / 1_000_000.0
                            : 0;

        return new InvocationSnapshot(snapshot.artifact().asString(),
                                      snapshot.methodName().name(),
                                      metrics.count(),
                                      metrics.successCount(),
                                      metrics.failureCount(),
                                      metrics.totalDurationNs(),
                                      metrics.estimatePercentileNs(50),
                                      metrics.estimatePercentileNs(95),
                                      avgDurationMs,
                                      snapshot.slowInvocations().size());
    }

    private SlowInvocationsResponse buildSlowInvocationsResponse() {
        var slowInvocations = nodeSupplier.get().invocationMetrics().snapshot().stream().flatMap(snapshot -> snapshot.slowInvocations()
                                                                                                                     .stream()
                                                                                                                     .map(slow -> toSlowInvocation(snapshot,
                                                                                                                                                   slow))).toList();

        return new SlowInvocationsResponse(slowInvocations);
    }

    private SlowInvocation toSlowInvocation(InvocationMetricsCollector.MethodSnapshot snapshot,
                                            org.pragmatica.aether.metrics.invocation.SlowInvocation slow) {
        return new SlowInvocation(snapshot.artifact().asString(),
                                  snapshot.methodName().name(),
                                  slow.durationNs(),
                                  slow.durationMs(),
                                  slow.timestampNs(),
                                  slow.success(),
                                  slow.errorType());
    }

    private Object buildHistoryResponse(Option<String> rangeOpt) {
        var range = rangeOpt.or("1h");
        var node = nodeSupplier.get();
        var historicalData = node.metricsCollector().historicalMetrics();
        var cutoff = System.currentTimeMillis() - parseTimeRange(range);
        Map<String, List<Map<String, Object>>> nodes = new HashMap<>();

        for (var nodeEntry : historicalData.entrySet()) {
            var snapshots = new ArrayList<Map<String, Object>>();

            for (var snapshot : nodeEntry.getValue()) {
                if (snapshot.timestamp() < cutoff) continue;

                var point = new HashMap<String, Object>();

                point.put("timestamp", snapshot.timestamp());
                point.put("metrics", snapshot.metrics());
                snapshots.add(point);
            }

            if (!snapshots.isEmpty()) {
                nodes.put(nodeEntry.getKey().id(),
                          snapshots);
            }
        }

        return Map.of("timeRange", range, "nodes", nodes);
    }

    private static long parseTimeRange(String range) {
        return switch (range) {
            case "5m" -> 5 * 60 * 1000L;
            case "15m" -> 15 * 60 * 1000L;
            case "1h" -> 60 * 60 * 1000L;
            case "2h" -> 2 * 60 * 60 * 1000L;
            default -> 60 * 60 * 1000L;
        };
    }

    private StrategyResponse buildStrategyResponse() {
        var node = nodeSupplier.get();
        var strategy = node.invocationMetrics().thresholdStrategy();

        return switch (strategy) {
            case ThresholdStrategy.Fixed f -> new StrategyResponse.Fixed("fixed", f.thresholdNs() / 1_000_000);
            case ThresholdStrategy.Adaptive a -> new StrategyResponse.Adaptive("adaptive",
                                                                               a.minThresholdNs() / 1_000_000,
                                                                               a.maxThresholdNs() / 1_000_000,
                                                                               a.multiplier());
            case ThresholdStrategy.PerMethod p -> new StrategyResponse.PerMethod("perMethod",
                                                                                 p.defaultThresholdNs() / 1_000_000);
            case ThresholdStrategy.Composite _ -> new StrategyResponse.Composite("composite");
            case ThresholdStrategy.unused _ -> new StrategyResponse.Fixed("none", 0);
        };
    }

    /// `GET /api/metrics/timeouts` (P-NEW-A) — emits one entry per
    /// `TimeoutSubsystem` regardless of count (presence guarantee for tests).
    /// Iteration order follows `TimeoutSubsystem.values()` (declaration order)
    /// via a `LinkedHashMap` so JSON output is stable across calls.
    private TimeoutMetricsResponse buildTimeoutMetricsResponse() {
        var snapshot = timeoutMetrics.snapshot();
        Map<String, SubsystemTimeoutCount> subsystems = new LinkedHashMap<>();

        for (var subsystem : TimeoutSubsystem.values()) {
            var count = snapshot.getOrDefault(subsystem, 0L);

            subsystems.put(subsystem.id(), new SubsystemTimeoutCount(count));
        }

        return new TimeoutMetricsResponse(subsystems);
    }

    /// `POST /api/metrics/backfill` (P-NEW-D) — dev-mode-only synthetic
    /// historical-metric injection. Validates the request, computes the
    /// number of samples that fit in the window, and appends them to the
    /// local `ClusterSyncCollector`'s historical ring buffer via the
    /// test-only `injectHistoricalSnapshot(...)` interface method.
    /// Dev-mode gate copied from `/api/scheduled-tasks/inject`.
    private Promise<BackfillMetricsResponse> handleBackfill(BackfillMetricsRequest req) {
        if (!devModeEnabled.getAsBoolean()) {
            return BackfillError.DEV_MODE_DISABLED.promise();
        }

        return validateBackfillRequest(req).flatMap(this::executeBackfill);
    }

    private Promise<BackfillMetricsRequest> validateBackfillRequest(BackfillMetricsRequest req) {
        if (req == null) {
            return BackfillError.MISSING_BODY.promise();
        }

        if (req.metric() == null || req.metric().isBlank()) {
            return BackfillError.MISSING_METRIC.promise();
        }

        if (req.startTimeMs() >= req.endTimeMs()) {
            return BackfillError.INVALID_WINDOW.promise();
        }

        if (req.intervalMs() <= 0) {
            return BackfillError.INVALID_INTERVAL.promise();
        }

        return Promise.success(req);
    }

    private Promise<BackfillMetricsResponse> executeBackfill(BackfillMetricsRequest req) {
        var node = nodeSupplier.get();
        var nodeId = node.self();
        var collector = node.metricsCollector();
        var generator = parseValueFn(req.valueFn());
        var windowMs = req.endTimeMs() - req.startTimeMs();
        long samplesWritten = 0;

        for (long t = req.startTimeMs(); t <= req.endTimeMs(); t += req.intervalMs()) {
            var progress = windowMs == 0
                           ? 0.0
                           : (double)(t - req.startTimeMs()) / (double) windowMs;
            var value = generator.valueAt(progress);

            collector.injectHistoricalSnapshot(nodeId,
                                               new MetricsSnapshot(t,
                                                                   Map.of(req.metric(), value)));
            samplesWritten++;
        }

        return Promise.success(new BackfillMetricsResponse(nodeId.id(),
                                                           req.metric(),
                                                           samplesWritten,
                                                           req.startTimeMs(),
                                                           req.endTimeMs()));
    }

    private static ValueGenerator parseValueFn(String valueFn) {
        if (valueFn == null || valueFn.isBlank()) {
            return ValueGenerator.constant(0.0);
        }

        if (valueFn.startsWith("constant:")) {
            return ValueGenerator.constant(parseDoubleOrZero(valueFn.substring("constant:".length())));
        }

        if ("linear".equalsIgnoreCase(valueFn)) {
            return ValueGenerator.linear();
        }

        if ("sine".equalsIgnoreCase(valueFn)) {
            return ValueGenerator.sine();
        }

        return ValueGenerator.constant(0.0);
    }

    private static double parseDoubleOrZero(String raw) {
        return Result.lift1(Double::parseDouble,
                            raw.trim())
                     .or(0.0);
    }

    /// Synthetic-value generator used by the backfill route. `progress` is in
    /// [0.0, 1.0] — 0 at `startTimeMs`, 1 at `endTimeMs`. Implementations are
    /// pure functions of progress so the same request emits a deterministic
    /// sequence (critical for integration tests asserting expected sample
    /// counts and shapes).
    @FunctionalInterface
    private interface ValueGenerator {
        double valueAt(double progress);

        static ValueGenerator constant(double v) {
            return _ -> v;
        }

        static ValueGenerator linear() {
            return p -> p;
        }

        static ValueGenerator sine() {
            return p -> 0.5 + 0.5 * Math.sin(2 * Math.PI * p);
        }
    }

    private enum BackfillError implements Cause {
        DEV_MODE_DISABLED("metrics backfill requires AETHER_INSECURE_DEV_MODE=true"),
        MISSING_BODY("Request body is required"),
        MISSING_METRIC("metric field is required"),
        INVALID_WINDOW("startTimeMs must be strictly less than endTimeMs"),
        INVALID_INTERVAL("intervalMs must be greater than 0");
        private final String message;
        BackfillError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
