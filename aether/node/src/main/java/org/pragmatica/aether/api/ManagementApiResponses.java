// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;


public sealed interface ManagementApiResponses {
    record unused() implements ManagementApiResponses{}

    record SuccessResponse(String status){}

    record ErrorResponse(String error){}

    record StatusResponse(long uptimeSeconds,
                          ClusterInfo cluster,
                          int sliceCount,
                          MetricsSummary metrics,
                          String nodeId,
                          String status,
                          String runtimeState,
                          String lifecycleState,
                          String clusterPhase,
                          boolean isLeader,
                          String leader,
                          String buildTimestamp,
                          String buildVersion){}

    record ClusterInfo(int nodeCount, String leaderId, boolean quorate, List<NodeInfo> nodes){}

    record NodeInfo(String id, boolean isLeader, String kvState, String derivedStatus){}

    record MetricsSummary(double requestsPerSecond, double successRate, double avgLatencyMs){}

    record NodesResponse(List<EnrichedNodeInfo> nodes){}

    record EnrichedNodeInfo(String nodeId, String role, boolean isLeader){}

    record HealthResponse(String status,
                          boolean ready,
                          boolean quorum,
                          int nodeCount,
                          int connectedPeers,
                          int metricsNodeCount,
                          int sliceCount,
                          String buildTimestamp){}

    record LivenessResponse(String status, String nodeId, String state, boolean ready){}

    record ReadinessResponse(String status,
                             String nodeId,
                             String state,
                             boolean ready,
                             List<ComponentHealth> components){}

    record ComponentHealth(String name, String status, String detail){}

    record CertificateStatusResponse(String expiresAt,
                                     long secondsUntilExpiry,
                                     String lastRenewalAt,
                                     String renewalStatus){}

    record SlicesResponse(List<String> slices){}

    record ClusterSlicesResponse(List<ClusterSliceInfo> slices){}

    record ClusterSliceInfo(String artifact,
                            int targetInstances,
                            int minInstances,
                            String version,
                            List<ClusterSliceInstance> instances){}

    record ClusterSliceInstance(String nodeId, String state, String failureReason){}

    record SlicesStatusResponse(List<SliceStatus> slices){}

    record SliceStatus(String artifact, String state, List<SliceInstanceInfo> instances){}

    record SliceInstanceInfo(String nodeId, String state, String health){}

    record RoutesResponse(List<RouteInfo> routes){}

    record RouteInfo(String method, String path, List<String> nodes, String security){}

    record ScaleResponse(String status, String artifact, int instances){}

    record BlueprintResponse(String status, String blueprint, int slices){}

    record BlueprintListResponse(List<BlueprintSummary> blueprints){}

    record BlueprintSummary(String id, int sliceCount){}

    record BlueprintDetailResponse(String id, List<BlueprintSliceInfo> slices, List<String> dependencies){}

    record BlueprintSliceInfo(String artifact, int instances, boolean isDependency, List<String> dependencies){}

    record BlueprintStatusResponse(String id, String overallStatus, List<BlueprintSliceStatus> slices){}

    record BlueprintSliceStatus(String artifact, int targetInstances, int activeInstances, String status){}

    record BlueprintDeleteResponse(String status, String id){}

    record BlueprintValidationResponse(boolean valid,
                                       String id,
                                       int sliceCount,
                                       List<String> errors,
                                       List<String> warnings){}

    record MetricsFullResponse(Map<String, Map<String, Double>> load,
                               Map<String, List<DeploymentMetrics>> deployments){}

    record DeploymentMetrics(String nodeId,
                             String status,
                             long fullDeploymentMs,
                             long netDeploymentMs,
                             Map<String, Long> transitions,
                             long startTime,
                             long activeTime){}

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
                                        long sampleCount){}

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
                                  boolean hasCapacity){}

    record NodeMetricsResponse(List<NodeMetric> metrics){}

    record NodeMetric(String nodeId, double cpuUsage, long heapUsedMb, long heapMaxMb){}

    record ArtifactMetricsResponse(int artifactCount,
                                   int chunkCount,
                                   long memoryBytes,
                                   String memoryMB,
                                   int deployedCount,
                                   List<String> deployedArtifacts){}

    record InvocationMetricsResponse(List<InvocationSnapshot> snapshots){}

    record InvocationSnapshot(String artifact,
                              String method,
                              long count,
                              long successCount,
                              long failureCount,
                              long totalDurationNs,
                              long p50DurationNs,
                              long p95DurationNs,
                              double avgDurationMs,
                              int slowInvocations){}

    record SlowInvocationsResponse(List<SlowInvocation> slowInvocations){}

    record SlowInvocation(String artifact,
                          String method,
                          long durationNs,
                          double durationMs,
                          long timestampNs,
                          boolean success,
                          Option<String> error){}

    sealed interface StrategyResponse {
        record Fixed(String type, long thresholdMs) implements StrategyResponse{}

        record Adaptive(String type, long minMs, long maxMs, double multiplier) implements StrategyResponse{}

        record PerMethod(String type, long defaultMs) implements StrategyResponse{}

        record Composite(String type) implements StrategyResponse{}
    }

    record ThresholdSetResponse(String status, String metric, double warning, double critical){}

    record ThresholdRemovedResponse(String status, String metric){}

    record AlertsClearedResponse(String status){}

    record AlertsResponse(List<AlertManager.AlertView> active,
                          List<AlertManager.AlertHistoryView> history){}

    record AlertInjectResponse(String alertId, String name, String severity, String message, long timestamp){}

    record TraceInjectResponse(String traceId,
                               String requestId,
                               String operation,
                               long durationMs,
                               int depth,
                               String timestamp){}

    record LogLevelSetResponse(String status, String logger, String level){}

    record LogLevelResetResponse(String status, String logger){}

    record ControllerStatusResponse(boolean enabled, long evaluationIntervalMs, Object config){}

    record TtmStatusResponse(boolean enabled,
                             boolean active,
                             String state,
                             String modelPath,
                             int inputWindowMinutes,
                             long evaluationIntervalMs,
                             double confidenceThreshold,
                             boolean hasForecast,
                             Option<TtmForecast> lastForecast){}

    record TtmForecast(long timestamp, double confidence, String recommendation){}

    record ControllerConfigUpdatedResponse(String status, Object config){}

    record EvaluationTriggeredResponse(String status){}

    record RollingUpdatesResponse(List<RollingUpdateInfo> updates){}

    record RollingUpdateInfo(String updateId,
                             String artifactBase,
                             String oldVersion,
                             String newVersion,
                             String state,
                             String routing,
                             int newInstances,
                             long createdAt,
                             long updatedAt){}

    record RollingUpdateHealthResponse(String updateId,
                                       VersionHealth oldVersion,
                                       VersionHealth newVersion,
                                       long collectedAt){}

    record VersionHealth(String version, long requestCount, double errorRate, double avgLatencyMs){}

    record RollingUpdateErrorResponse(String error, String updateId){}

    record CanaryListResponse(List<CanaryInfo> canaries){}

    record CanaryInfo(String canaryId,
                      String artifactBase,
                      String oldVersion,
                      String newVersion,
                      String state,
                      String routing,
                      int currentStage,
                      int totalStages,
                      int newInstances,
                      long createdAt,
                      long updatedAt){}

    record CanaryHealthResponse(String canaryId,
                                String verdict,
                                CanaryVersionHealth baseline,
                                CanaryVersionHealth canary,
                                long collectedAt){}

    record CanaryVersionHealth(String version, long requestCount, double errorRate, long p99LatencyMs){}

    record BlueGreenListResponse(List<BlueGreenInfo> deployments){}

    record BlueGreenInfo(String deploymentId,
                         String artifactBase,
                         String blueVersion,
                         String greenVersion,
                         String state,
                         String activeEnvironment,
                         String routing,
                         int blueInstances,
                         int greenInstances,
                         long createdAt,
                         long updatedAt){}

    record AbTestListResponse(List<AbTestInfo> tests){}

    record AbTestInfo(String testId,
                      String artifactBase,
                      String baselineVersion,
                      String state,
                      int variantCount,
                      long createdAt,
                      long updatedAt){}

    record AbTestMetricsResponse(String testId, Map<String, AbTestVariantMetrics> variants, long collectedAt){}

    record AbTestVariantMetrics(String variant,
                                String version,
                                long requestCount,
                                double errorRate,
                                long avgLatencyMs){}

    record ConfigSetResponse(String status, String key, String value){}

    record ConfigRemovedResponse(String status, String key){}

    record TopologyResponse(List<TopologyNodeInfo> nodes, List<TopologyEdgeInfo> edges){}

    record TopologyNodeInfo(String id, String type, String label, String sliceArtifact){}

    record TopologyEdgeInfo(String from, String to, String style, String topicConfig){}

    record ClusterTopologyStatusResponse(int coreCount,
                                         int coreMax,
                                         int coreMin,
                                         int workerCount,
                                         int clusterSize,
                                         List<String> coreNodes,
                                         int connectedPeerCount,
                                         List<TopologyNodeDetail> nodeDetails,
                                         Option<String> epoch,
                                         String mode){}

    record TopologyNodeDetail(String nodeId, String role, String health, String hostname, String zone, String address){}

    record ClusterGenerationResponse(Option<EpochInfo> epoch,
                                     long rabiaTerm,
                                     String mode,
                                     String quiescence,
                                     String quiescenceDetail,
                                     ClusterGenerationCore core,
                                     List<ClusterGenerationCommunity> communities,
                                     List<ClusterGenerationPartition> partitions){}

    record EpochInfo(long rabiaTerm, long localCounter){}

    record ClusterGenerationCore(int desiredSize, List<ClusterGenerationMember> members){}

    record ClusterGenerationMember(String nodeId,
                                   String host,
                                   int port,
                                   String lifecycle,
                                   String healthHint,
                                   EpochInfo joinedEpoch,
                                   EpochInfo lastSeenEpoch){}

    record ClusterGenerationCommunity(String communityId,
                                      String governorNodeId,
                                      long communityTerm,
                                      EpochInfo communityEpoch,
                                      int memberCount,
                                      ClusterGenerationHealth health,
                                      List<String> partitions,
                                      EpochInfo lastAckAtCore,
                                      String quiescence,
                                      String quiescenceDetail){}

    record ClusterGenerationHealth(int healthy, int suspected, int faulty){}

    record ClusterGenerationPartition(String partitionId,
                                      String ownerNodeId,
                                      String ownerCommunityId,
                                      EpochInfo ownerEpoch,
                                      long ownershipTerm){}

    record AwaitQuiescedResponse(String epoch, String quiescence, long waitedMs){}

    record GovernorsResponse(List<GovernorInfo> governors){}

    record GovernorInfo(String governorId, String community, int memberCount, List<String> members){}

    record CircuitBreakerStatusResponse(int consecutiveFailures, int trippedAt, long nextAllowedMs, boolean tripped){}

    record CircuitBreakerResetResponse(String status, int priorFailureCount){}

    record AutoHealStatusResponse(boolean enabled){}

    record AutoHealToggleResponse(boolean enabled, boolean previousState){}

    record ClusterConfigResponse(String tomlContent,
                                 String clusterName,
                                 String version,
                                 int coreCount,
                                 int coreMin,
                                 int coreMax,
                                 String deploymentType,
                                 long configVersion,
                                 long updatedAt){}

    record LoadBalancerStatusInfo(String type, String nodeId, String appEndpoint, String mgmtEndpoint){}

    record ClusterStatusResponse(String clusterName,
                                 String desiredVersion,
                                 int desiredCoreCount,
                                 int actualCoreCount,
                                 String state,
                                 String leaderId,
                                 List<ClusterStatusNodeInfo> nodes,
                                 int slicesDeployed,
                                 int sliceInstances,
                                 String certificateExpiresAt,
                                 long certificateDaysRemaining,
                                 long configVersion,
                                 long uptimeSeconds,
                                 Option<LoadBalancerStatusInfo> loadBalancer){}

    record ClusterStatusNodeInfo(String nodeId, String role, String lifecycleState, String version, boolean isLeader){}

    record ApplyConfigRequest(String tomlContent, long expectedVersion){}

    record ApplyConfigResponse(long configVersion, String clusterName, int coreCount, long updatedAt){}

    record DryRunResponse(String clusterName,
                          long fromVersion,
                          long toVersion,
                          List<String> plannedChanges,
                          int changeCount,
                          int rejectedCount){}

    record ScaleRequest(int coreCount, long expectedVersion){}

    record ScaleClusterResponse(boolean success, int previousCount, int newCount, long configVersion){}

    record UpgradeRequest(String targetVersion){}

    record UpgradeResponse(String status, String from, String to){}

    record ArtifactInfoResponse(String artifact,
                                long size,
                                int chunkCount,
                                String md5,
                                String sha1,
                                long deployedAt,
                                boolean isDeployed){}
}
