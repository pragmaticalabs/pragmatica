package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Typed response records for all Management API endpoints.
 */
public sealed interface ManagementApiResponses {
    record unused() implements ManagementApiResponses {}

    // ===== Common =====
    record SuccessResponse(String status) {}

    record ErrorResponse(String error) {}

    // ===== Status Routes =====
    record StatusResponse(long uptimeSeconds,
                          ClusterInfo cluster,
                          int sliceCount,
                          MetricsSummary metrics,
                          String nodeId,
                          String status,
                          boolean isLeader,
                          String leader) {}

    record ClusterInfo(int nodeCount,
                       String leaderId,
                       List<NodeInfo> nodes) {}

    record NodeInfo(String id,
                    boolean isLeader) {}

    record MetricsSummary(double requestsPerSecond,
                          double successRate,
                          double avgLatencyMs) {}

    record NodesResponse(List<String> nodes) {}

    record HealthResponse(String status,
                          boolean ready,
                          boolean quorum,
                          int nodeCount,
                          int connectedPeers,
                          int metricsNodeCount,
                          int sliceCount) {}

    // ===== Slice Routes =====
    record SlicesResponse(List<String> slices) {}

    record SlicesStatusResponse(List<SliceStatus> slices) {}

    record SliceStatus(String artifact,
                       String state,
                       List<SliceInstanceInfo> instances) {}

    record SliceInstanceInfo(String nodeId,
                             String state,
                             String health) {}

    record RoutesResponse(List<RouteInfo> routes) {}

    record RouteInfo(String method,
                     String path,
                     String artifact,
                     String sliceMethod) {}

    record DeployResponse(String status,
                          String artifact,
                          int instances) {}

    record UndeployResponse(String status,
                            String artifact) {}

    record BlueprintResponse(String status,
                             String blueprint,
                             int slices) {}

    // ===== Metrics Routes =====
    record MetricsFullResponse(Map<String, Map<String, Double>> load,
                               Map<String, List<DeploymentMetrics>> deployments) {}

    record DeploymentMetrics(String nodeId,
                             String status,
                             long fullDeploymentMs,
                             long netDeploymentMs,
                             Map<String, Long> transitions,
                             long startTime,
                             long activeTime) {}

    record ComprehensiveMetricsResponse(long minuteTimestamp,
                                        double avgCpuUsage,
                                        double avgHeapUsage,
                                        double avgEventLoopLagMs,
                                        double avgLatencyMs,
                                        long totalInvocations,
                                        long totalGcPauseMs,
                                        double latencyP50,
                                        double latencyP95,
                                        double latencyP99,
                                        double errorRate,
                                        long eventCount,
                                        long sampleCount) {}

    record DerivedMetricsResponse(double requestRate,
                                  double errorRate,
                                  double gcRate,
                                  double latencyP50,
                                  double latencyP95,
                                  double latencyP99,
                                  double eventLoopSaturation,
                                  double heapSaturation,
                                  double cpuTrend,
                                  double latencyTrend,
                                  double errorTrend,
                                  double healthScore,
                                  boolean stressed,
                                  boolean hasCapacity) {}

    record NodeMetricsResponse(List<NodeMetric> metrics) {}

    record NodeMetric(String nodeId,
                      double cpuUsage,
                      long heapUsedMb,
                      long heapMaxMb) {}

    record ArtifactMetricsResponse(int artifactCount,
                                   int chunkCount,
                                   long memoryBytes,
                                   String memoryMB,
                                   int deployedCount,
                                   List<String> deployedArtifacts) {}

    record InvocationMetricsResponse(List<InvocationSnapshot> snapshots) {}

    record InvocationSnapshot(String artifact,
                              String method,
                              long count,
                              long successCount,
                              long failureCount,
                              long totalDurationNs,
                              long p50DurationNs,
                              long p95DurationNs,
                              double avgDurationMs,
                              int slowInvocations) {}

    record SlowInvocationsResponse(List<SlowInvocation> slowInvocations) {}

    record SlowInvocation(String artifact,
                          String method,
                          long durationNs,
                          double durationMs,
                          long timestampNs,
                          boolean success,
                          @Nullable String error) {}

    sealed interface StrategyResponse {
        record Fixed(String type, long thresholdMs) implements StrategyResponse {}

        record Adaptive(String type, long minMs, long maxMs, double multiplier) implements StrategyResponse {}

        record PerMethod(String type, long defaultMs) implements StrategyResponse {}

        record Composite(String type) implements StrategyResponse {}
    }

    // ===== Alert Routes =====
    record ThresholdSetResponse(String status,
                                String metric,
                                double warning,
                                double critical) {}

    record ThresholdRemovedResponse(String status,
                                    String metric) {}

    record AlertsClearedResponse(String status) {}

    record AlertsResponse(Object active,
                          Object history) {}

    // ===== Controller Routes =====
    record ControllerStatusResponse(boolean enabled,
                                    long evaluationIntervalMs,
                                    Object config) {}

    record TtmStatusResponse(boolean enabled,
                             boolean active,
                             String state,
                             String modelPath,
                             int inputWindowMinutes,
                             long evaluationIntervalMs,
                             double confidenceThreshold,
                             boolean hasForecast,
                             @Nullable TtmForecast lastForecast) {}

    record TtmForecast(long timestamp,
                       double confidence,
                       String recommendation) {}

    record ControllerConfigUpdatedResponse(String status,
                                           Object config) {}

    record EvaluationTriggeredResponse(String status) {}

    // ===== Rolling Update Routes =====
    record RollingUpdatesResponse(List<RollingUpdateInfo> updates) {}

    record RollingUpdateInfo(String updateId,
                             String artifactBase,
                             String oldVersion,
                             String newVersion,
                             String state,
                             String routing,
                             int newInstances,
                             long createdAt,
                             long updatedAt) {}

    record RollingUpdateHealthResponse(String updateId,
                                       VersionHealth oldVersion,
                                       VersionHealth newVersion,
                                       long collectedAt) {}

    record VersionHealth(String version,
                         long requestCount,
                         double errorRate,
                         double avgLatencyMs) {}

    record RollingUpdateErrorResponse(String error,
                                      String updateId) {}

    // ===== Repository Routes =====
    record ArtifactInfoResponse(String artifact,
                                long size,
                                int chunkCount,
                                String md5,
                                String sha1,
                                long deployedAt,
                                boolean isDeployed) {}
}
