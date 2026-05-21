// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;


public sealed interface ManagementApiResponses {
    record unused() implements ManagementApiResponses {}

    record SuccessResponse(String status) {}

    record ErrorResponse(String error) {}

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
                          String buildVersion) {}

    record ClusterInfo(int nodeCount, String leaderId, boolean quorate, List<NodeInfo> nodes) {}

    record NodeInfo(String id, boolean isLeader, String kvState, String derivedStatus) {}

    record MetricsSummary(double requestsPerSecond, double successRate, double avgLatencyMs) {}

    record NodesResponse(List<EnrichedNodeInfo> nodes) {}

    record EnrichedNodeInfo(String nodeId, String role, boolean isLeader) {}

    record HealthResponse(String status,
                          boolean ready,
                          boolean quorum,
                          int nodeCount,
                          int connectedPeers,
                          int metricsNodeCount,
                          int sliceCount,
                          String buildTimestamp) {}

    record LivenessResponse(String status, String nodeId, String state, boolean ready) {}

    record WhoamiResponse(String principal, String authorizationRole, List<String> roles, boolean authenticated) {}

    record ReadinessResponse(String status,
                             String nodeId,
                             String state,
                             boolean ready,
                             List<ComponentHealth> components) {}

    record ComponentHealth(String name, String status, String detail) {}

    /// Wire shape for `GET /api/certificates`. `tlsEnabled` reflects the node's runtime
    /// TLS posture (the app HTTP server is bound with TLS when `AetherNodeConfig.tls()`
    /// is present). The remaining four fields describe the active cert when a
    /// `CertificateRenewalScheduler` is wired; with TLS off they are placeholders
    /// (`"N/A"` / `0` / `"NOT_CONFIGURED"`) so integration tooling can rely on
    /// `tlsEnabled` alone as the authoritative active-TLS signal.
    /// See `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2.
    record CertificateStatusResponse(boolean tlsEnabled,
                                     String expiresAt,
                                     long secondsUntilExpiry,
                                     String lastRenewalAt,
                                     String renewalStatus) {}

    record SlicesResponse(List<String> slices) {}

    record ClusterSlicesResponse(List<ClusterSliceInfo> slices) {}

    record ClusterSliceInfo(String artifact,
                            int targetInstances,
                            int minInstances,
                            String version,
                            List<ClusterSliceInstance> instances) {}

    record ClusterSliceInstance(String nodeId, String state, String failureReason) {}

    record SlicesStatusResponse(List<SliceStatus> slices) {}

    record SliceStatus(String artifact, String state, List<SliceInstanceInfo> instances) {}

    record SliceInstanceInfo(String nodeId, String state, String health) {}

    record RoutesResponse(List<RouteInfo> routes) {}

    record RouteInfo(String method, String path, List<String> nodes, String security) {}

    record ScaleResponse(String status, String artifact, int instances) {}

    record BlueprintResponse(String status, String blueprint, int slices) {}

    record BlueprintListResponse(List<BlueprintSummary> blueprints) {}

    record BlueprintSummary(String id, int sliceCount) {}

    record BlueprintDetailResponse(String id, List<BlueprintSliceInfo> slices, List<String> dependencies) {}

    record BlueprintSliceInfo(String artifact, int instances, boolean isDependency, List<String> dependencies) {}

    record BlueprintStatusResponse(String id, String overallStatus, List<BlueprintSliceStatus> slices) {}

    record BlueprintSliceStatus(String artifact, int targetInstances, int activeInstances, String status) {}

    record BlueprintDeleteResponse(String status, String id) {}

    record BlueprintValidationResponse(boolean valid,
                                       String id,
                                       int sliceCount,
                                       List<String> errors,
                                       List<String> warnings) {}

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

    record NodeMetric(String nodeId, double cpuUsage, long heapUsedMb, long heapMaxMb) {}

    record ArtifactMetricsResponse(int artifactCount,
                                   int chunkCount,
                                   long memoryBytes,
                                   String memoryMB,
                                   int deployedCount,
                                   List<String> deployedArtifacts) {}

    /// Wire shape for `GET /api/metrics/timeouts` (P-NEW-A, 2026-05-21).
    /// Maps each `TimeoutSubsystem` (14 entries mirroring `TimeoutsConfig`'s
    /// nested records) to its cumulative timeout-fired count for the life of
    /// the node process. Counters are LongAdder-backed and never reset.
    /// Subsystems with zero fires are still emitted (presence guarantee
    /// simplifies integration-test assertions on shape).
    /// See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-A
    /// and TC-07-G3-timeouts-config.
    record TimeoutMetricsResponse(Map<String, SubsystemTimeoutCount> subsystems) {}

    record SubsystemTimeoutCount(long firedCount) {}

    /// Request shape for `POST /api/metrics/backfill` (P-NEW-D, 2026-05-21;
    /// dev-mode-only). Seeds synthetic historical-metric samples into the
    /// local node's `ClusterSyncCollector` ring buffer so TC-11-H1
    /// (historical-metrics range queries: 5m/15m/1h/2h) can assert
    /// deterministically without waiting hours for the sliding window to
    /// accumulate organically. Fields:
    /// - `metric` — metric key written into each synthetic snapshot's map
    ///   (e.g. `cpu.usage`); MUST be non-blank.
    /// - `startTimeMs` / `endTimeMs` — inclusive epoch-millis window;
    ///   `startTimeMs < endTimeMs` required.
    /// - `intervalMs` — spacing between successive samples; MUST be > 0.
    /// - `valueFn` — synthetic-value generator: one of
    ///   `"constant:<double>"` (e.g. `constant:42.5`), `"linear"` (0..1
    ///   ramp across the window), `"sine"` (0.5+0.5·sin(2π·t/window)).
    ///   Unknown values fall back to constant 0.0.
    /// Gated by `AETHER_INSECURE_DEV_MODE=true` — same gate pattern as
    /// `/api/scheduled-tasks/inject` and `/api/alerts/inject`.
    record BackfillMetricsRequest(String metric,
                                  long startTimeMs,
                                  long endTimeMs,
                                  long intervalMs,
                                  String valueFn) {}

    /// Response shape for `POST /api/metrics/backfill`. `samplesWritten` is
    /// the number of synthetic snapshots appended to the ring buffer for the
    /// local node; `nodeId` identifies which node received the backfill (the
    /// route is `LOCAL`, so this is always the entry-point node). Tests can
    /// assert `samplesWritten == floor((endTimeMs - startTimeMs) / intervalMs) + 1`.
    record BackfillMetricsResponse(String nodeId, String metric, long samplesWritten, long startTimeMs, long endTimeMs) {}

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
                          Option<String> error) {}

    sealed interface StrategyResponse {
        record Fixed(String type, long thresholdMs) implements StrategyResponse {}

        record Adaptive(String type, long minMs, long maxMs, double multiplier) implements StrategyResponse {}

        record PerMethod(String type, long defaultMs) implements StrategyResponse {}

        record Composite(String type) implements StrategyResponse {}
    }

    record ThresholdSetResponse(String status, String metric, double warning, double critical) {}

    record ThresholdRemovedResponse(String status, String metric) {}

    record AlertsClearedResponse(String status) {}

    record AlertsResponse(List<AlertManager.AlertView> active, List<AlertManager.AlertHistoryView> history) {}

    record AlertInjectResponse(String alertId, String name, String severity, String message, long timestamp) {}

    record TraceInjectResponse(String traceId,
                               String requestId,
                               String operation,
                               long durationMs,
                               int depth,
                               String timestamp) {}

    /// Request shape for `POST /api/scheduled-tasks/inject` (dev-mode only).
    /// Identifies the task to fire by the same `(configSection, artifact, method)` triple
    /// used by `/api/scheduled-tasks/state/...` and `/api/scheduled-tasks/pause/...`.
    /// See `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2 item 16.
    record ScheduledTaskInjectRequest(String section, String artifact, String method) {}

    /// Response shape for `POST /api/scheduled-tasks/inject`. Surfaces the prior and
    /// freshly-stamped `lastExecutionAt` values so integration tests can assert strict
    /// monotonic advancement without relying on the warn-then-pass demotion the route
    /// was designed to replace. `previousExecutionMs == 0` when the task had no prior
    /// state entry; `currentExecutionMs` is always `> previousExecutionMs` on success.
    record ScheduledTaskInjectResponse(String section,
                                       String artifact,
                                       String method,
                                       long previousExecutionMs,
                                       long currentExecutionMs) {}

    record LogLevelSetResponse(String status, String logger, String level) {}

    record LogLevelResetResponse(String status, String logger) {}

    record ControllerStatusResponse(boolean enabled, long evaluationIntervalMs, Object config) {}

    record TtmStatusResponse(boolean enabled,
                             boolean active,
                             String state,
                             String modelPath,
                             int inputWindowMinutes,
                             long evaluationIntervalMs,
                             double confidenceThreshold,
                             boolean hasForecast,
                             Option<TtmForecast> lastForecast) {}

    record TtmForecast(long timestamp, double confidence, String recommendation) {}

    record ControllerConfigUpdatedResponse(String status, Object config) {}

    record EvaluationTriggeredResponse(String status) {}

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

    record VersionHealth(String version, long requestCount, double errorRate, double avgLatencyMs) {}

    record RollingUpdateErrorResponse(String error, String updateId) {}

    record CanaryListResponse(List<CanaryInfo> canaries) {}

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
                      long updatedAt) {}

    record CanaryHealthResponse(String canaryId,
                                String verdict,
                                CanaryVersionHealth baseline,
                                CanaryVersionHealth canary,
                                long collectedAt) {}

    record CanaryVersionHealth(String version, long requestCount, double errorRate, long p99LatencyMs) {}

    record BlueGreenListResponse(List<BlueGreenInfo> deployments) {}

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
                         long updatedAt) {}

    record AbTestListResponse(List<AbTestInfo> tests) {}

    record AbTestInfo(String testId,
                      String artifactBase,
                      String baselineVersion,
                      String state,
                      int variantCount,
                      long createdAt,
                      long updatedAt) {}

    record AbTestMetricsResponse(String testId, Map<String, AbTestVariantMetrics> variants, long collectedAt) {}

    record AbTestVariantMetrics(String variant,
                                String version,
                                long requestCount,
                                double errorRate,
                                long avgLatencyMs) {}

    record ConfigSetResponse(String status, String key, String value) {}

    record ConfigRemovedResponse(String status, String key) {}

    record TopologyResponse(List<TopologyNodeInfo> nodes, List<TopologyEdgeInfo> edges) {}

    record TopologyNodeInfo(String id, String type, String label, String sliceArtifact) {}

    record TopologyEdgeInfo(String from, String to, String style, String topicConfig) {}

    record ClusterTopologyStatusResponse(int coreCount,
                                         int coreMax,
                                         int coreMin,
                                         int workerCount,
                                         int clusterSize,
                                         List<String> coreNodes,
                                         int connectedPeerCount,
                                         List<TopologyNodeDetail> nodeDetails,
                                         Option<String> epoch,
                                         String mode) {}

    record TopologyNodeDetail(String nodeId, String role, String health, String hostname, String zone, String address) {}

    record ClusterGenerationResponse(Option<EpochInfo> epoch,
                                     long rabiaTerm,
                                     String mode,
                                     String quiescence,
                                     String quiescenceDetail,
                                     ClusterGenerationCore core,
                                     List<ClusterGenerationCommunity> communities,
                                     List<ClusterGenerationPartition> partitions) {}

    record EpochInfo(long rabiaTerm, long localCounter) {}

    record ClusterGenerationCore(int desiredSize, List<ClusterGenerationMember> members) {}

    record ClusterGenerationMember(String nodeId,
                                   String host,
                                   int port,
                                   String lifecycle,
                                   String healthHint,
                                   EpochInfo joinedEpoch,
                                   EpochInfo lastSeenEpoch) {}

    record ClusterGenerationCommunity(String communityId,
                                      String governorNodeId,
                                      long communityTerm,
                                      EpochInfo communityEpoch,
                                      int memberCount,
                                      ClusterGenerationHealth health,
                                      List<String> partitions,
                                      EpochInfo lastAckAtCore,
                                      String quiescence,
                                      String quiescenceDetail) {}

    record ClusterGenerationHealth(int healthy, int suspected, int faulty) {}

    record ClusterGenerationPartition(String partitionId,
                                      String ownerNodeId,
                                      String ownerCommunityId,
                                      EpochInfo ownerEpoch,
                                      long ownershipTerm) {}

    record AwaitQuiescedResponse(String epoch, String quiescence, long waitedMs) {}

    record GovernorsResponse(List<GovernorInfo> governors) {}

    record GovernorInfo(String governorId, String community, int memberCount, List<String> members) {}

    record CircuitBreakerStatusResponse(int consecutiveFailures, int trippedAt, long nextAllowedMs, boolean tripped) {}

    record CircuitBreakerResetResponse(String status, int priorFailureCount) {}

    record AutoHealStatusResponse(boolean enabled) {}

    record AutoHealToggleResponse(boolean enabled, boolean previousState) {}

    record ClusterConfigResponse(String tomlContent,
                                 String clusterName,
                                 String version,
                                 int coreCount,
                                 int coreMin,
                                 int coreMax,
                                 String deploymentType,
                                 long configVersion,
                                 long updatedAt) {}

    record LoadBalancerStatusInfo(String type, String nodeId, String appEndpoint, String mgmtEndpoint) {}

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
                                 Option<LoadBalancerStatusInfo> loadBalancer) {}

    record ClusterStatusNodeInfo(String nodeId,
                                 String role,
                                 String kvState,
                                 String derivedStatus,
                                 String version,
                                 boolean isLeader) {}

    record ApplyConfigRequest(String tomlContent, long expectedVersion) {}

    record ApplyConfigResponse(long configVersion, String clusterName, int coreCount, long updatedAt) {}

    record DryRunResponse(String clusterName,
                          long fromVersion,
                          long toVersion,
                          List<String> plannedChanges,
                          int changeCount,
                          int rejectedCount) {}

    record ScaleRequest(int coreCount, long expectedVersion) {}

    record ScaleClusterResponse(boolean success, int previousCount, int newCount, long configVersion) {}

    record UpgradeRequest(String targetVersion) {}

    record UpgradeResponse(String status, String from, String to) {}

    record ArtifactInfoResponse(String artifact,
                                long size,
                                int chunkCount,
                                String md5,
                                String sha1,
                                long deployedAt,
                                boolean isDeployed) {}

    /// Wire shape for the idempotent artifact PUT response (`PUT /repository/...`).
    /// `status` is `"uploaded"` on a fresh upload, `"already-present"` when the
    /// artifact was already in the store (200 OK in both cases). `coords` is the
    /// canonical `group:artifact:version` triple; `size`/`md5`/`sha1` reflect the
    /// persisted artifact (recomputed on upload, read from KV metadata on duplicate).
    /// The record lives here for documentation/test purposes; the actual JSON is
    /// rendered inline in `MavenProtocolHandlerImpl.renderPushJson` to avoid a
    /// Jackson dependency at the artifact-repo layer.
    record ArtifactPushResponse(String status, String coords, long size, String md5, String sha1) {}

    /// Wire shape for `POST /api/dht/inject` (P-NEW-B, RC1, 2026-05-21; dev-mode-only).
    /// Writes a value into the local DHT storage tier with an operator-supplied HLC
    /// timestamp, bypassing the regular `DHTClient.put` path that always advances the
    /// node's clock to `now()`. Enables TC-10-G2 (DHT versioned writes) to set up
    /// deterministic version-conflict scenarios without racing the live clock.
    ///
    /// Fields:
    /// - `key` — DHT key (UTF-8); MUST be non-blank.
    /// - `value` — DHT value (UTF-8 string; serialized to bytes on the server).
    /// - `hlc.physical` — physical-microseconds component of the explicit timestamp.
    /// - `hlc.logical` — logical-counter component of the explicit timestamp.
    ///
    /// Gated by `AETHER_INSECURE_DEV_MODE=true` — same gate pattern as
    /// `/api/alerts/inject`, `/api/scheduled-tasks/inject`, `/api/metrics/backfill`.
    /// Local-only route (no leader forwarding) — tests POST directly to the node
    /// they wish to mutate. See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-B.
    record DhtInjectRequest(String key, String value, HlcShape hlc) {}

    /// Compact wire form for an `HlcTimestamp` (physical micros + logical counter),
    /// shared by `DhtInjectRequest` (input) and `DhtInjectResponse` (output).
    record HlcShape(long physical, int logical) {}

    /// Response for `POST /api/dht/inject`. `committedHlc` is the HLC actually
    /// recorded for the write — may be advanced relative to the request when the
    /// node's local clock had already moved past the supplied timestamp (HLC merge
    /// rule). `written` is `true` when the storage layer accepted the value as the
    /// newest version, `false` when a stale-version write was suppressed.
    record DhtInjectResponse(String key, HlcShape committedHlc, boolean written) {}

    /// Wire shape for `GET /api/dht/replication-map` (P-NEW-F, RC1, 2026-05-21).
    /// Surfaces the current DHT replication topology — which keys are replicated to
    /// which nodes under the active replication factor. Operator-facing (no dev-mode
    /// gate). Optional query parameters: `limit` (max entries, default 100), `prefix`
    /// (UTF-8 key prefix filter).
    record DhtReplicationMapResponse(int replicationFactor,
                                     int totalKeys,
                                     int returned,
                                     List<DhtReplicationEntry> entries) {}

    /// Per-key replication mapping. `nodes` is the ordered list of node IDs
    /// responsible for the key under the active replication factor — index 0 is
    /// the primary; subsequent entries are replicas walking the consistent-hash
    /// ring clockwise.
    record DhtReplicationEntry(String key, List<String> nodes) {}
}
