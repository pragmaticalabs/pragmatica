package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ArtifactMetricsResponse;
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
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.metrics.invocation.MetricsError;
import org.pragmatica.aether.metrics.invocation.ThresholdStrategy;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.routing.ContentCategory;
import org.pragmatica.http.routing.ContentType;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.QueryParameter.aString;

/**
 * Routes for metrics endpoints: node metrics, artifact metrics, invocation metrics, prometheus.
 */
public final class MetricsRoutes implements RouteSource {
    private static final ContentType PROMETHEUS_CONTENT_TYPE = ContentType.contentType("text/plain; version=0.0.4; charset=utf-8",
                                                                                       ContentCategory.PLAIN_TEXT);

    private final Supplier<AetherNode> nodeSupplier;
    private final ObservabilityRegistry observability;

    private MetricsRoutes(Supplier<AetherNode> nodeSupplier, ObservabilityRegistry observability) {
        this.nodeSupplier = nodeSupplier;
        this.observability = observability;
    }

    public static MetricsRoutes metricsRoutes(Supplier<AetherNode> nodeSupplier, ObservabilityRegistry observability) {
        return new MetricsRoutes(nodeSupplier, observability);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<MetricsFullResponse> get("/api/metrics")
                              .toJson(this::buildMetricsResponse),
                         Route.<ComprehensiveMetricsResponse> get("/api/metrics/comprehensive")
                              .toJson(this::buildComprehensiveMetricsResponse),
                         Route.<DerivedMetricsResponse> get("/api/metrics/derived")
                              .toJson(this::buildDerivedMetricsResponse),
                         Route.<String> get("/api/metrics/prometheus")
                              .to(_ -> Promise.success(observability.scrape()))
                              .as(PROMETHEUS_CONTENT_TYPE),
                         Route.<List<NodeMetric>> get("/api/node-metrics")
                              .toJson(this::buildNodeMetricsResponse),
                         Route.<ArtifactMetricsResponse> get("/api/artifact-metrics")
                              .toJson(this::buildArtifactMetricsResponse),
                         Route.<InvocationMetricsResponse> get("/api/invocation-metrics")
                              .withQuery(aString("artifact"),
                                         aString("method"))
                              .toValue(this::buildInvocationMetricsResponse)
                              .asJson(),
                         Route.<SlowInvocationsResponse> get("/api/invocation-metrics/slow")
                              .toJson(this::buildSlowInvocationsResponse),
                         Route.<StrategyResponse> get("/api/invocation-metrics/strategy")
                              .toJson(this::buildStrategyResponse),
                         Route.<ErrorResponse> post("/api/invocation-metrics/strategy")
                              .to(_ -> MetricsError.StrategyChangeNotSupported.INSTANCE.<ErrorResponse>promise())
                              .asJson());
    }

    private MetricsFullResponse buildMetricsResponse() {
        var node = nodeSupplier.get();
        return new MetricsFullResponse(buildLoadMetrics(node), buildDeploymentMetrics(node));
    }

    private Map<String, Map<String, Double>> buildLoadMetrics(AetherNode node) {
        Map<String, Map<String, Double>> load = new HashMap<>();
        for (var entry : node.metricsCollector()
                             .allMetrics()
                             .entrySet()) {
            load.put(entry.getKey()
                          .id(),
                     entry.getValue());
        }
        return load;
    }

    private Map<String, List<DeploymentMetrics>> buildDeploymentMetrics(AetherNode node) {
        Map<String, List<DeploymentMetrics>> deployments = new HashMap<>();
        for (var entry : node.deploymentMetricsCollector()
                             .allDeploymentMetrics()
                             .entrySet()) {
            var metricsList = entry.getValue()
                                   .stream()
                                   .map(this::toDeploymentMetrics)
                                   .toList();
            deployments.put(entry.getKey()
                                 .asString(),
                            metricsList);
        }
        return deployments;
    }

    private DeploymentMetrics toDeploymentMetrics(org.pragmatica.aether.metrics.deployment.DeploymentMetrics m) {
        return new DeploymentMetrics(m.nodeId()
                                      .id(),
                                     m.status()
                                      .name(),
                                     m.fullDeploymentTime(),
                                     m.netDeploymentTime(),
                                     m.transitionLatencies(),
                                     m.startTime(),
                                     m.activeTime());
    }

    private ComprehensiveMetricsResponse buildComprehensiveMetricsResponse() {
        var node = nodeSupplier.get();
        var recent = node.snapshotCollector()
                         .minuteAggregator()
                         .recent(1);
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
        var derived = node.snapshotCollector()
                          .derivedMetrics();
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
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var result = new ArrayList<NodeMetric>();
        for (var entry : allMetrics.entrySet()) {
            var nodeId = entry.getKey();
            var metrics = entry.getValue();
            result.add(new NodeMetric(nodeId.id(),
                                      metrics.getOrDefault("cpuUsage", 0.0),
                                      metrics.getOrDefault("heapUsedMb", 0.0)
                                             .longValue(),
                                      metrics.getOrDefault("heapMaxMb", 0.0)
                                             .longValue()));
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
                                           deployedArtifacts.stream()
                                                            .map(a -> a.asString())
                                                            .toList());
    }

    private InvocationMetricsResponse buildInvocationMetricsResponse(Option<String> artifactFilter,
                                                                     Option<String> methodFilter) {
        var snapshots = nodeSupplier.get()
                                    .invocationMetrics()
                                    .snapshot()
                                    .stream()
                                    .filter(snapshot -> matchesFilters(snapshot, artifactFilter, methodFilter))
                                    .map(this::toInvocationSnapshot)
                                    .toList();
        return new InvocationMetricsResponse(snapshots);
    }

    private boolean matchesFilters(InvocationMetricsCollector.MethodSnapshot snapshot,
                                   Option<String> artifactFilter,
                                   Option<String> methodFilter) {
        boolean matchesArtifact = artifactFilter.map(filter -> snapshot.artifact()
                                                                       .asString()
                                                                       .contains(filter))
                                                .or(true);
        boolean matchesMethod = methodFilter.map(filter -> snapshot.methodName()
                                                                   .name()
                                                                   .equals(filter))
                                            .or(true);
        return matchesArtifact && matchesMethod;
    }

    private InvocationSnapshot toInvocationSnapshot(InvocationMetricsCollector.MethodSnapshot snapshot) {
        var metrics = snapshot.metrics();
        var avgDurationMs = metrics.count() > 0
                            ? metrics.totalDurationNs() / metrics.count() / 1_000_000.0
                            : 0;
        return new InvocationSnapshot(snapshot.artifact()
                                              .asString(),
                                      snapshot.methodName()
                                              .name(),
                                      metrics.count(),
                                      metrics.successCount(),
                                      metrics.failureCount(),
                                      metrics.totalDurationNs(),
                                      metrics.estimatePercentileNs(50),
                                      metrics.estimatePercentileNs(95),
                                      avgDurationMs,
                                      snapshot.slowInvocations()
                                              .size());
    }

    private SlowInvocationsResponse buildSlowInvocationsResponse() {
        var slowInvocations = nodeSupplier.get()
                                          .invocationMetrics()
                                          .snapshot()
                                          .stream()
                                          .flatMap(snapshot -> snapshot.slowInvocations()
                                                                       .stream()
                                                                       .map(slow -> toSlowInvocation(snapshot, slow)))
                                          .toList();
        return new SlowInvocationsResponse(slowInvocations);
    }

    private SlowInvocation toSlowInvocation(InvocationMetricsCollector.MethodSnapshot snapshot,
                                            org.pragmatica.aether.metrics.invocation.SlowInvocation slow) {
        return new SlowInvocation(snapshot.artifact()
                                          .asString(),
                                  snapshot.methodName()
                                          .name(),
                                  slow.durationNs(),
                                  slow.durationMs(),
                                  slow.timestampNs(),
                                  slow.success(),
                                  slow.errorType());
    }

    private StrategyResponse buildStrategyResponse() {
        var node = nodeSupplier.get();
        var strategy = node.invocationMetrics()
                           .thresholdStrategy();
        return switch (strategy) {
            case ThresholdStrategy.Fixed f ->
            new StrategyResponse.Fixed("fixed", f.thresholdNs() / 1_000_000);
            case ThresholdStrategy.Adaptive a ->
            new StrategyResponse.Adaptive("adaptive",
                                          a.minThresholdNs() / 1_000_000,
                                          a.maxThresholdNs() / 1_000_000,
                                          a.multiplier());
            case ThresholdStrategy.PerMethod p ->
            new StrategyResponse.PerMethod("perMethod", p.defaultThresholdNs() / 1_000_000);
            case ThresholdStrategy.Composite _ ->
            new StrategyResponse.Composite("composite");
        };
    }
}
