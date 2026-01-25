package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Routes for metrics endpoints: node metrics, artifact metrics, invocation metrics, prometheus.
 */
public final class MetricsRoutes implements RouteHandler {
    private static final ContentType PROMETHEUS_CONTENT_TYPE = ContentType.contentType("text/plain; version=0.0.4; charset=utf-8",
                                                                                       ContentCategory.TEXT);
    private static final Pattern STRATEGY_TYPE_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");

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
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = extractPath(ctx.path());
        var method = ctx.method();
        // GET endpoints
        if (method == HttpMethod.GET) {
            return switch (path) {
                case "/api/metrics" -> {
                    response.ok(buildMetricsResponse());
                    yield true;
                }
                case "/api/metrics/comprehensive" -> {
                    response.ok(buildComprehensiveMetricsResponse());
                    yield true;
                }
                case "/api/metrics/derived" -> {
                    response.ok(buildDerivedMetricsResponse());
                    yield true;
                }
                case "/api/metrics/prometheus" -> {
                    response.write(HttpStatus.OK,
                                   observability.scrape()
                                                .getBytes(StandardCharsets.UTF_8),
                                   PROMETHEUS_CONTENT_TYPE);
                    yield true;
                }
                case "/api/node-metrics" -> {
                    response.ok(buildNodeMetricsResponse());
                    yield true;
                }
                case "/api/artifact-metrics" -> {
                    response.ok(buildArtifactMetricsResponse());
                    yield true;
                }
                case "/api/invocation-metrics" -> {
                    var artifactFilter = extractQueryParam(ctx.path(), "artifact");
                    var methodFilter = extractQueryParam(ctx.path(), "method");
                    response.ok(buildInvocationMetricsResponse(artifactFilter, methodFilter));
                    yield true;
                }
                case "/api/invocation-metrics/slow" -> {
                    response.ok(buildSlowInvocationsResponse());
                    yield true;
                }
                case "/api/invocation-metrics/strategy" -> {
                    response.ok(buildStrategyResponse());
                    yield true;
                }
                default -> false;
            };
        }
        // POST endpoints
        if (method == HttpMethod.POST && path.equals("/api/invocation-metrics/strategy")) {
            response.error(HttpStatus.NOT_IMPLEMENTED,
                           "Runtime strategy change not supported. Strategy must be set at node startup.");
            return true;
        }
        return false;
    }

    private String buildMetricsResponse() {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        sb.append("{");
        // Load metrics section
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
        // Deployment metrics section
        sb.append("\"deployments\":{");
        var deploymentMetrics = node.deploymentMetricsCollector()
                                    .allDeploymentMetrics();
        boolean firstArtifact = true;
        for (var entry : deploymentMetrics.entrySet()) {
            if (!firstArtifact) sb.append(",");
            sb.append("\"")
              .append(entry.getKey()
                           .asString())
              .append("\":[");
            boolean firstDeployment = true;
            for (var metrics : entry.getValue()) {
                if (!firstDeployment) sb.append(",");
                sb.append("{");
                sb.append("\"nodeId\":\"")
                  .append(metrics.nodeId()
                                 .id())
                  .append("\",");
                sb.append("\"status\":\"")
                  .append(metrics.status()
                                 .name())
                  .append("\",");
                sb.append("\"fullDeploymentMs\":")
                  .append(metrics.fullDeploymentTime())
                  .append(",");
                sb.append("\"netDeploymentMs\":")
                  .append(metrics.netDeploymentTime())
                  .append(",");
                sb.append("\"transitions\":{");
                var latencies = metrics.transitionLatencies();
                boolean firstLatency = true;
                for (var latency : latencies.entrySet()) {
                    if (!firstLatency) sb.append(",");
                    sb.append("\"")
                      .append(latency.getKey())
                      .append("\":")
                      .append(latency.getValue());
                    firstLatency = false;
                }
                sb.append("},");
                sb.append("\"startTime\":")
                  .append(metrics.startTime())
                  .append(",");
                sb.append("\"activeTime\":")
                  .append(metrics.activeTime());
                sb.append("}");
                firstDeployment = false;
            }
            sb.append("]");
            firstArtifact = false;
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private String buildComprehensiveMetricsResponse() {
        var node = nodeSupplier.get();
        var recent = node.snapshotCollector()
                         .minuteAggregator()
                         .recent(1);
        if (recent.isEmpty()) {
            return "{\"error\":\"No comprehensive metrics available yet\"}";
        }
        var agg = recent.get(0);
        return "{\"minuteTimestamp\":" + agg.minuteTimestamp() + ",\"avgCpuUsage\":" + agg.avgCpuUsage()
               + ",\"avgHeapUsage\":" + agg.avgHeapUsage() + ",\"avgEventLoopLagMs\":" + agg.avgEventLoopLagMs()
               + ",\"avgLatencyMs\":" + agg.avgLatencyMs() + ",\"totalInvocations\":" + agg.totalInvocations()
               + ",\"totalGcPauseMs\":" + agg.totalGcPauseMs() + ",\"latencyP50\":" + agg.latencyP50()
               + ",\"latencyP95\":" + agg.latencyP95() + ",\"latencyP99\":" + agg.latencyP99() + ",\"errorRate\":" + agg.errorRate()
               + ",\"eventCount\":" + agg.eventCount() + ",\"sampleCount\":" + agg.sampleCount() + "}";
    }

    private String buildDerivedMetricsResponse() {
        var node = nodeSupplier.get();
        var derived = node.snapshotCollector()
                          .derivedMetrics();
        return "{\"requestRate\":" + derived.requestRate() + ",\"errorRate\":" + derived.errorRate() + ",\"gcRate\":" + derived.gcRate()
               + ",\"latencyP50\":" + derived.latencyP50() + ",\"latencyP95\":" + derived.latencyP95()
               + ",\"latencyP99\":" + derived.latencyP99() + ",\"eventLoopSaturation\":" + derived.eventLoopSaturation()
               + ",\"heapSaturation\":" + derived.heapSaturation() + ",\"cpuTrend\":" + derived.cpuTrend()
               + ",\"latencyTrend\":" + derived.latencyTrend() + ",\"errorTrend\":" + derived.errorTrend()
               + ",\"healthScore\":" + derived.healthScore() + ",\"stressed\":" + derived.stressed()
               + ",\"hasCapacity\":" + derived.hasCapacity() + "}";
    }

    private String buildNodeMetricsResponse() {
        var node = nodeSupplier.get();
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (var entry : allMetrics.entrySet()) {
            if (!first) sb.append(",");
            var nodeId = entry.getKey();
            var metrics = entry.getValue();
            sb.append("{\"nodeId\":\"")
              .append(nodeId.id())
              .append("\",");
            sb.append("\"cpuUsage\":")
              .append(metrics.getOrDefault("cpuUsage", 0.0))
              .append(",");
            sb.append("\"heapUsedMb\":")
              .append(metrics.getOrDefault("heapUsedMb", 0.0)
                             .longValue())
              .append(",");
            sb.append("\"heapMaxMb\":")
              .append(metrics.getOrDefault("heapMaxMb", 0.0)
                             .longValue());
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildArtifactMetricsResponse() {
        var node = nodeSupplier.get();
        var collector = node.artifactMetricsCollector();
        var storeMetrics = collector.storeMetrics();
        var deployedArtifacts = collector.deployedArtifacts();
        var memoryMB = storeMetrics.memoryBytes() / (1024.0 * 1024.0);
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"artifactCount\":")
          .append(storeMetrics.artifactCount())
          .append(",");
        sb.append("\"chunkCount\":")
          .append(storeMetrics.chunkCount())
          .append(",");
        sb.append("\"memoryBytes\":")
          .append(storeMetrics.memoryBytes())
          .append(",");
        sb.append("\"memoryMB\":")
          .append(String.format("%.2f", memoryMB))
          .append(",");
        sb.append("\"deployedCount\":")
          .append(deployedArtifacts.size())
          .append(",");
        sb.append("\"deployedArtifacts\":[");
        boolean first = true;
        for (var artifact : deployedArtifacts) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(artifact.asString())
              .append("\"");
            first = false;
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private String buildInvocationMetricsResponse(Option<String> artifactFilter, Option<String> methodFilter) {
        var node = nodeSupplier.get();
        var snapshots = node.invocationMetrics()
                            .snapshot();
        var sb = new StringBuilder();
        sb.append("{\"snapshots\":[");
        boolean first = true;
        for (var snapshot : snapshots) {
            var skipByArtifact = artifactFilter.map(filter -> !snapshot.artifact()
                                                                       .asString()
                                                                       .contains(filter))
                                               .or(false);
            if (skipByArtifact) continue;
            var skipByMethod = methodFilter.map(filter -> !snapshot.methodName()
                                                                   .name()
                                                                   .equals(filter))
                                           .or(false);
            if (skipByMethod) continue;
            if (!first) sb.append(",");
            sb.append("{\"artifact\":\"")
              .append(snapshot.artifact()
                              .asString())
              .append("\",");
            sb.append("\"method\":\"")
              .append(snapshot.methodName()
                              .name())
              .append("\",");
            sb.append("\"count\":")
              .append(snapshot.metrics()
                              .count())
              .append(",");
            sb.append("\"successCount\":")
              .append(snapshot.metrics()
                              .successCount())
              .append(",");
            sb.append("\"failureCount\":")
              .append(snapshot.metrics()
                              .failureCount())
              .append(",");
            sb.append("\"totalDurationNs\":")
              .append(snapshot.metrics()
                              .totalDurationNs())
              .append(",");
            sb.append("\"p50DurationNs\":")
              .append(snapshot.metrics()
                              .estimatePercentileNs(50))
              .append(",");
            sb.append("\"p95DurationNs\":")
              .append(snapshot.metrics()
                              .estimatePercentileNs(95))
              .append(",");
            sb.append("\"avgDurationMs\":")
              .append(snapshot.metrics()
                              .count() > 0
                      ? snapshot.metrics()
                                .totalDurationNs() / snapshot.metrics()
                                                            .count() / 1_000_000.0
                      : 0)
              .append(",");
            sb.append("\"slowInvocations\":")
              .append(snapshot.slowInvocations()
                              .size());
            sb.append("}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildSlowInvocationsResponse() {
        var node = nodeSupplier.get();
        var snapshots = node.invocationMetrics()
                            .snapshot();
        var sb = new StringBuilder();
        sb.append("{\"slowInvocations\":[");
        boolean first = true;
        for (var snapshot : snapshots) {
            for (var slow : snapshot.slowInvocations()) {
                if (!first) sb.append(",");
                sb.append("{\"artifact\":\"")
                  .append(snapshot.artifact()
                                  .asString())
                  .append("\",");
                sb.append("\"method\":\"")
                  .append(snapshot.methodName()
                                  .name())
                  .append("\",");
                sb.append("\"durationNs\":")
                  .append(slow.durationNs())
                  .append(",");
                sb.append("\"durationMs\":")
                  .append(slow.durationMs())
                  .append(",");
                sb.append("\"timestampNs\":")
                  .append(slow.timestampNs())
                  .append(",");
                sb.append("\"success\":")
                  .append(slow.success())
                  .append(",");
                sb.append("\"error\":")
                  .append(slow.errorType()
                              .map(err -> "\"" + err.replace("\"", "\\\"") + "\"")
                              .or("null"));
                sb.append("}");
                first = false;
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildStrategyResponse() {
        var node = nodeSupplier.get();
        var strategy = node.invocationMetrics()
                           .thresholdStrategy();
        return switch (strategy) {
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.Fixed f ->
            "{\"type\":\"fixed\",\"thresholdMs\":" + (f.thresholdNs() / 1_000_000) + "}";
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.Adaptive a ->
            "{\"type\":\"adaptive\",\"minMs\":" + (a.minThresholdNs() / 1_000_000) + ",\"maxMs\":" + (a.maxThresholdNs() / 1_000_000)
            + ",\"multiplier\":" + a.multiplier() + "}";
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.PerMethod p ->
            "{\"type\":\"perMethod\",\"defaultMs\":" + (p.defaultThresholdNs() / 1_000_000) + "}";
            case org.pragmatica.aether.metrics.invocation.ThresholdStrategy.Composite _ ->
            "{\"type\":\"composite\"}";
        };
    }
}
