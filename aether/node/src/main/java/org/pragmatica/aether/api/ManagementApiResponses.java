// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.worker.isolation.CoreAbsenceSnapshot;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;


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

    /// Wire shape for `GET /api/nodes/{id}/endpoint` (harness-resilience spec A1). Resolves a
    /// nodeId to its cluster-transport address so the integration harness can dial a node
    /// without reconstructing addressing from `bootstrap-state.json` or the cloud API. `address`
    /// is the `host:port` advertised in the consensus `NodeInfo`. `reachable` is a best-effort
    /// TCP connect probe against that address — the endpoint is useful even when `reachable=false`
    /// (it tells the caller where to try rather than forcing local reconstruction).
    record NodeEndpointResponse(String nodeId, String address, boolean reachable) {}

    /// Wire shape for `GET /api/nodes/live` (harness-resilience spec A2). Unifies a node's
    /// cluster-identity, address, role, SWIM liveness, and node-reported work-state into one
    /// document so the harness can distinguish "member the cluster knows about" from "node that
    /// is actually alive" in a single call. Nodes present in the reported-state map but absent
    /// from the SWIM-derived membership view are the zombie class: `swimAlive=false` and
    /// `address=null` (`Option.none()`). `liveCount` counts `swimAlive=true` entries;
    /// `zombieCount` counts the remainder.
    record LiveNodesResponse(List<LiveNodeEntry> nodes, int liveCount, int zombieCount) {}

    /// Per-node row of [LiveNodesResponse]. `address` is `Option.none()` (JSON `null`) for zombie
    /// entries with no resolvable consensus address. `reportedState` is the node-authoritative
    /// `NodeReportedState` (SYNCING / READY / DRAINING), empty when no metrics pong has been
    /// observed yet.
    record LiveNodeEntry(String nodeId, Option<String> address, String role, boolean swimAlive, String reportedState) {}

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

    /// Self-describing wire shape for `GET /api/events`. Adds a `type` discriminator
    /// (SCREAMING_SNAKE_CASE of the source {@link ClusterEvent} variant — e.g. `"NODE_FAILED"`)
    /// alongside the existing event fields, since the `@Codec` serializes record components only and
    /// `ClusterEvent.type()` is a default method. Backward-compatible: all prior fields keep their
    /// names; `type` is purely additive.
    record ClusterEventView(String type,
                            HlcTimestamp at,
                            ClusterEvent.Severity severity,
                            String summary,
                            Map<String, String> details) {}

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

    record WorkersResponse(List<WorkerInfo> workers) {}

    record WorkerInfo(String nodeId,
                      String community,
                      String governorId,
                      boolean isGovernor,
                      long communityTerm,
                      long announcedAt) {}

    record ScaleResponse(String status, String artifact, int instances) {}

    /// #759 review (C1, C2) — this response is a PUBLISH-TIME SNAPSHOT: `SliceRoutes.deployStatus`
    /// computes it the instant `publishFromArtifact` commits the blueprint, before any node has
    /// attempted to load a slice [mechanism: `SliceRoutes.java:233-244`]. `pending` (zero
    /// `activeInstances`, zero `failedInstances`) is therefore the NORMAL first answer for a fresh
    /// deploy, not a rare or degraded case — `degraded`/`deployed` are reachable here only on a
    /// redeploy of an already-active artifact set, or under `BEST_EFFORT` where a prior partial
    /// failure is still visible in `deploymentMap()`. `targetInstances` / `activeInstances` /
    /// `failedInstances` let an operator tell total outage from healthy without a follow-up call.
    /// Callers that need the terminal outcome MUST poll `statusUrl` [mechanism: the CLI's
    /// `aether/cli/.../DeploymentWait.java:32-40,52-67` implements exactly this poll-until-terminal
    /// loop, driven from `AetherCli.java:2261-2268,2273-2275`].
    ///
    /// `statusUrl` always points at `GET /api/v1/blueprints/status/{id}`, and — as of #759 Phase 2 —
    /// that endpoint IS durable across a rollback: under the default `ALL_OR_NOTHING` atomicity, a
    /// deterministic failure triggers `unloadBlueprintSlices`, which removes the blueprint's
    /// `AppBlueprintKey` from the KV store entirely [mechanism: `ClusterDeploymentState.java:2139-2158`],
    /// but the terminal `FAILED`/`ROLLED_BACK` `DeploymentOutcomeValue` written at the same time survives
    /// that removal and is what `statusUrl` now reads first — see `SliceRoutes.handleGetBlueprintStatus`.
    /// A `statusUrl` GET issued after rollback therefore answers `200` with `overallStatus` `FAILED` or
    /// `ROLLED_BACK`, `cause`, and `failingSlices`, `slices = []` (nothing left to report per-slice once
    /// `AppBlueprintKey` is gone), not `404`; `404 BLUEPRINT_NOT_FOUND` now means only "never reached a
    /// terminal outcome and nothing live in the KV store either" (never attempted, still in flight, or
    /// crash-orphaned — see `BlueprintService.outcome`).
    ///
    /// #759 review round 3 BLOCKING 3: the `slices = []` degenerate shape above is specific to
    /// `ALL_OR_NOTHING`, where the failing rollback removes `AppBlueprintKey` outright. Under
    /// `BEST_EFFORT`, a deterministic slice failure records the same kind of terminal outcome
    /// (`ClusterDeploymentState.recordBestEffortFailureOutcome`) WITHOUT removing `AppBlueprintKey` —
    /// siblings keep serving. A `statusUrl` GET against a still-live blueprint with a terminal outcome
    /// therefore answers `200` `PARTIAL`, with real `slices` per-instance counts alongside the same
    /// `cause`/`failingSlices`/`timestampMs` — never the degenerate `slices = []` shape, and never a
    /// bare `FAILED`/`ROLLED_BACK` that discards what is still running. The `DeploymentFailed` event
    /// on `GET /api/events` remains the timeline of when and what failed
    /// [mechanism: `ClusterEventAggregator.java:881-885` — `details.artifact` names the failed
    /// artifact; `MAX_RETAINED_EVENTS = 10_000` (`:139`) bounds retention for the whole
    /// cluster-event stream, not per-artifact; `StatusRoutes.java:114-115` — the route accepts
    /// only `sinceEpoch`/`sinceSeq`, no server-side artifact filter, so a caller must fetch and
    /// filter client-side on `details.artifact`]; `statusUrl`'s outcome record is the durable summary,
    /// not a replacement for the timeline. See `aether/docs/reference/management-api.md`
    /// `POST /api/blueprints/deploy`.
    record BlueprintResponse(String status,
                             String blueprint,
                             int targetInstances,
                             int activeInstances,
                             int failedInstances,
                             String statusUrl) {}

    record BlueprintListResponse(List<BlueprintSummary> blueprints) {}

    record BlueprintSummary(String id, int sliceCount) {}

    record BlueprintDetailResponse(String id, List<BlueprintSliceInfo> slices, List<String> dependencies) {}

    record BlueprintSliceInfo(String artifact, int instances, boolean isDependency, List<String> dependencies) {}

    /// #759 Phase 2 — `overallStatus` carries `"FAILED"`/`"ROLLED_BACK"` from
    /// `BlueprintService.outcome(id)` when a terminal failure outcome exists, taking priority over
    /// whatever `get(id)` currently holds (including a stale non-empty value left by rollback). In
    /// that case `slices` is the true degenerate empty list — outcome-sourced responses do not carry
    /// live per-slice detail — and `cause`/`failingSlices`/`timestampMs` are populated from the
    /// outcome record. For every other response (`SUCCEEDED` outcome or no outcome at all, `get(id)`
    /// present), `slices` carries the live snapshot as before and `cause`/`failingSlices` are the
    /// degenerate empty-string/empty-list, `timestampMs` is `0` — never fabricated. See
    /// `SliceRoutes.handleGetBlueprintStatus`.
    record BlueprintStatusResponse(String id,
                                   String overallStatus,
                                   List<BlueprintSliceStatus> slices,
                                   String cause,
                                   List<String> failingSlices,
                                   long timestampMs) {}

    /// #759 — `failedInstances` surfaces `SliceState.FAILED` entries still present in
    /// `deploymentMap()` at response time (e.g. `BEST_EFFORT` deploys, or a query that lands before
    /// `ALL_OR_NOTHING` rollback cleanup runs); `status` folds it in as `"FAILED"`, taking priority
    /// over the target/active comparison. See `SliceRoutes.determineSliceDeploymentStatus`.
    record BlueprintSliceStatus(String artifact,
                                int targetInstances,
                                int activeInstances,
                                int failedInstances,
                                String status) {}

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
                                        long sampleCount,
                                        ConsensusMetricsResponse consensus) {}

    /// #674: the consensus-load block — previously collected in-process and DROPPED at this DTO
    /// boundary, leaving no external observer able to measure coordination load on a core node.
    /// LIVE monotonic totals (not minute aggregates): a differencing consumer needs raw totals over
    /// its own window, the same contract `/metrics/transport` serves. NODE-LOCAL scope — each core
    /// answers for itself. `pendingBatches` is a level; `avgDecisionLatencyMs` is derived over the
    /// cumulative counts.
    record ConsensusMetricsResponse(String role,
                                    Option<String> leaderId,
                                    int pendingBatches,
                                    long decisionsCount,
                                    long proposalsCount,
                                    long voteRound1Count,
                                    long voteRound2Count,
                                    long fastPathCount,
                                    long syncSuccessCount,
                                    long syncFailureCount,
                                    double avgDecisionLatencyMs) {}

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
    record BackfillMetricsRequest(String metric, long startTimeMs, long endTimeMs, long intervalMs, String valueFn) {}

    /// Response shape for `POST /api/metrics/backfill`. `samplesWritten` is
    /// the number of synthetic snapshots appended to the ring buffer for the
    /// local node; `nodeId` identifies which node received the backfill (the
    /// route is `LOCAL`, so this is always the entry-point node). Tests can
    /// assert `samplesWritten == floor((endTimeMs - startTimeMs) / intervalMs) + 1`.
    record BackfillMetricsResponse(String nodeId,
                                   String metric,
                                   long samplesWritten,
                                   long startTimeMs,
                                   long endTimeMs) {}

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

    /// Wire shape for `GET /api/scheduled-tasks/executions-by-node` (P-NEW-H, 2026-05-21).
    /// Surfaces per-node execution attribution for a scheduled task identified by the
    /// `(section, artifact, method)` triple. Each entry pairs a `nodeId` with the number
    /// of executions attributed to it and the millisecond epoch of the most recent
    /// execution. Tests for SINGLE-mode tasks assert exactly one entry has a non-zero
    /// `count`; ALL-mode tests assert every cluster member appears with `count > 0`.
    ///
    /// Sourced from `ScheduledTaskStateRegistry` — RC1 tracks task state globally rather
    /// than per-node, so the current implementation reports the task's `registeredBy`
    /// node as the sole executor. A follow-up issue tracks adding per-node execution
    /// counters to the KV state so this endpoint can produce true per-node breakdowns.
    /// See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-H.
    record ScheduledTaskExecutionsByNodeResponse(String section,
                                                 String artifact,
                                                 String method,
                                                 List<ScheduledTaskNodeExecution> executions) {}

    record ScheduledTaskNodeExecution(String nodeId, int count, long lastExecutionMs) {}

    /// Request shape for `POST /api/certificates/configure-short-validity` (P-NEW-I,
    /// 2026-05-21; dev-mode-only). Configures the `CertificateRenewalScheduler` to
    /// behave as though the active certificate has only `validitySeconds` of remaining
    /// validity, causing the next renewal Tick to fire promptly (40% of remaining =
    /// 24s for `validitySeconds=60`). Enables `Strengthen-cert-rotation-trigger`
    /// to observe an automatic rotation without waiting hours.
    ///
    /// Gated by `AETHER_INSECURE_DEV_MODE=true` — same gate pattern as
    /// `/api/alerts/inject`, `/api/scheduled-tasks/inject`, `/api/dht/inject`,
    /// `/api/metrics/backfill`. Local-only route (no leader forwarding) — tests POST
    /// directly to the node whose scheduler they wish to mutate.
    record CertConfigureShortValidityRequest(int validitySeconds) {}

    /// Response shape for `POST /api/certificates/configure-short-validity`.
    /// `newExpiresAt` is the ISO-8601 expiry timestamp the scheduler now sees;
    /// `secondsUntilExpiry` is the post-configuration delta. The scheduler advances
    /// to its `Renewing` state on the next Tick (40% of `validitySeconds` later).
    record CertConfigureShortValidityResponse(String status,
                                              int validitySeconds,
                                              String newExpiresAt,
                                              long secondsUntilExpiry) {}

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

    /// Wire shape for `GET /api/slices/{id}/config` (Batch 4 of the hierarchical-config
    /// refactor, 2026-05-21). Returns the effective configuration view for a loaded slice,
    /// with per-key attribution of which layer of the slice-composite
    /// (`slice.toml ⊕ KV-overlay ⊕ node.toml`) produced the resolved value. `source` is
    /// one of `"KV"`, `"node.toml"`, `"slice.toml"` (see [LayeredConfigProvider#sourceOf]).
    /// `entries` is sorted alphabetically by `key` for deterministic output.
    record SliceConfigResponse(String sliceId, List<SliceConfigEntry> entries) {}

    record SliceConfigEntry(String key, String value, String source) {}

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
                                         String mode,
                                         List<FsmMemberDetail> fsmMembers) {}

    record TopologyNodeDetail(String nodeId,
                              String role,
                              String assignedRole,
                              String health,
                              String hostname,
                              String zone,
                              String address) {}

    /// Wave-1 item 6 (cluster-topology-overhaul spec): per-member `MembershipFsm` truth —
    /// lifecycle state name (`Observed`/`Member`/`Suspect`/`Departing`/`Dead`), the SWIM
    /// incarnation high-water mark, and the last-known descriptor role/source labels — so a
    /// remote run reads membership truth from `GET /api/cluster/topology` without `docker logs`.
    /// `assignedRole` (#259) is the CDM-assigned role from the KV-Store `ActivationDirective`
    /// (`UNASSIGNED` when none); it diverges from the self-asserted descriptor `role` for
    /// worker-demoted nodes, which previously made a demoted node look like a core.
    record FsmMemberDetail(String nodeId,
                           String fsmState,
                           long incarnation,
                           String role,
                           String assignedRole,
                           String source) {}

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

    /// One desired-topology entry: how many nodes of `role` the cluster wants in `sourceName`.
    ///
    /// `coreCount` on [ClusterConfigResponse] is the sum of the core entries. It stays for the
    /// existing consumers, but it cannot say WHERE those cores live, which is what an operator
    /// needs before scaling a multi-source cluster.
    record TopologyEntryInfo(String sourceName, String role, int count) {}

    record ClusterConfigResponse(String tomlContent,
                                 String clusterName,
                                 String version,
                                 int coreCount,
                                 List<TopologyEntryInfo> desiredTopology,
                                 int coreMin,
                                 int coreMax,
                                 String deploymentType,
                                 long configVersion,
                                 long updatedAt) {}

    record LoadBalancerStatusInfo(String type, String nodeId, String appEndpoint, String mgmtEndpoint) {}

    /// #336 observability — flattened provisioning-diagnostics view for `GET /api/cluster/provisioning`.
    /// Assembled from the leader reconcile decision snapshot, the provisioning circuit-breaker state,
    /// and the last provisioning failure. `leader` is `true` only when this node is the leader and owns
    /// a `ClusterTopologyManager` (the only node that can answer "why is this deficit not being
    /// filled?"); when `false` the numeric fields are zeroed and `lastReason` explains the absence.
    /// `deficit` is `configuredCoreCount - effective` clamped to `>= 0`. `lastProvisionFailure` is empty
    /// (serialized as `null`) when no provisioning failure has been recorded.
    record ProvisioningDiagnosticsResponse(boolean leader,
                                           int configuredCoreCount,
                                           int countedCoreMembers,
                                           int effective,
                                           int deficit,
                                           boolean armedForProvisioning,
                                           boolean reachedFullMembership,
                                           boolean quorumSafe,
                                           String lastTrigger,
                                           String lastReason,
                                           long deficitAgeMs,
                                           ProvisioningCircuitBreakerInfo circuitBreaker,
                                           Option<ProvisionFailureInfo> lastProvisionFailure) {}

    record ProvisioningCircuitBreakerInfo(int consecutiveFailures, boolean tripped, long nextAllowedMs) {}

    record ProvisionFailureInfo(String cause, long atEpochMs) {}

    /// SWIM-under-concurrent-loss observability — this node's LOCAL membership view for
    /// `GET /api/cluster/membership`. PER-NODE (not leader-forwarded): `nodeId` is the answering
    /// survivor; the counts and `armed`/`belowThreshold` flags are read from THAT node's own
    /// `MembershipFsm` + `QuorumLossDetector`, so an operator can see, per survivor, whether its
    /// self-drain window is armed and below the simple-majority threshold. `members` lists every
    /// peer the FSM tracks (including DEAD, retained for incarnation-fenced rejoin), sorted by
    /// `nodeId` for stable output. When the local `QuorumLossDetector` is not yet wired the
    /// threshold/below/armed fields carry sensible zero/false defaults.
    ///
    /// `coreAbsence` (#590) is the community tier's equivalent fence on the same per-node footing: the
    /// core-tier fields above answer "is this CORE node about to self-drain on quorum loss", and
    /// `coreAbsence` answers "is this node about to dissolve because it has lost the core". Both are
    /// deliberately on this LOCAL endpoint, because a leader-forwarded read cannot reach the node whose
    /// isolation is in question.
    record ClusterMembershipResponse(String nodeId,
                                     int strictCoreMemberCount,
                                     int countedCoreMemberCount,
                                     int requiredThreshold,
                                     boolean belowThreshold,
                                     boolean armed,
                                     CoreAbsenceSnapshot coreAbsence,
                                     List<MembershipNodeDetail> members) {}

    /// Per-peer membership detail as seen by the answering node's `MembershipFsm`: the lifecycle
    /// `state` (Observed/Member/Suspect/Departing/Dead), the incarnation high-water mark, the
    /// descriptor `role`, whether the peer is in the strict (MEMBER-only) core set, and whether it
    /// counts toward the effective (MEMBER+SUSPECT) core membership used as the quorum/heal-deficit
    /// denominator.
    record MembershipNodeDetail(String nodeId,
                                String state,
                                long incarnation,
                                String role,
                                boolean strictCore,
                                boolean countsTowardEffective) {}

    /// #345 item 1f committed-ownership + fence-diagnostic view for `GET /api/ownership/{domain}`.
    /// PER-NODE (not leader/owner-forwarded): every entry is read from the answering node's committed
    /// KV-Store and its LOCAL epoch high-water table, so `owner`/`epoch`/`highWater`/`fenced` reflect
    /// what THIS node has applied. The ownership fence (#345 piece 1a) rejects a deposed owner's
    /// strictly-older epoch in the Rabia applier, so the committed `epoch` is the live fencing token —
    /// the diagnostic that lets the cloud handover test verify the fence engaged. `entries` is sorted
    /// by `identity` for stable output; an empty list means no ownership of that domain is committed
    /// yet (the operator-meaningful answer, not an error).
    record OwnershipResponse(String domain, List<OwnershipEntry> entries) {}

    /// Per-partition/key committed-ownership + fence row: `identity` is the domain-specific
    /// partition/key (community id, DHT partition id, or `{stream}:{partition}`), `owner` the
    /// committed owner `NodeId`, `epoch` the committed fence `Epoch` (`fenceEpoch`), and `highWater`
    /// the answering node's LOCAL per-domain monotonic epoch high-water — both carried as the same
    /// `(rabiaTerm, localCounter)` pair used elsewhere. `fenced` is `true` when `highWater` is
    /// strictly after `epoch`: the deposed-owner window in which this node has already observed a
    /// newer epoch than the committed owner record shows, so the committed owner would be rejected as
    /// stale here. In steady state `highWater` equals `epoch` and `fenced` is `false`; a `true`
    /// pinpoints the node/arc where a takeover has advanced past the still-visible committed owner.
    record OwnershipEntry(String identity, String owner, EpochInfo epoch, EpochInfo highWater, boolean fenced) {}

    /// #260/#261/#333 regression-sensor surface — the per-partition replica-set state as seen by the
    /// answering node's `ReplicaRegistry`, with the deterministic HRW owner resolved via the read
    /// path's owner resolver. The registry is AUTHORITATIVE only on the HRW owner (only the owner
    /// receives every replica's ack), so `servedByOwner` tells an operator whether `replicas` is the
    /// complete view; when false, `hrwOwner` names the node to re-query. `ownerHeadOffset` is the
    /// answering node's local next-expected offset (head + 1) — on the owner it is the true tail used
    /// to spot a CAUGHT_UP replica whose `confirmedOffset` lags it (#333 write-idle residual).
    record StreamReplicasResponse(String stream,
                                  int partition,
                                  String hrwOwner,
                                  boolean servedByOwner,
                                  long ownerHeadOffset,
                                  long earliestRetainedOffset,
                                  List<ReplicaStateDetail> replicas) {}

    /// Per-replica state row: `state` is the `ReplicationState` name (`SYNCING` / `CAUGHT_UP` /
    /// `LAGGING`), `confirmedOffset` the replica's acked watermark, `isHrwOwner` whether this replica
    /// is the resolved HRW owner. Compare a `CAUGHT_UP` replica's `confirmedOffset` against the
    /// response's `ownerHeadOffset` to detect the #333 lag residual.
    record ReplicaStateDetail(String nodeId, String state, long confirmedOffset, boolean isHrwOwner) {}

    /// #265 increment 0 per-node hydration observability — the §6 regression sensor. Assembled ON
    /// REQUEST from the answering node's `StreamPartitionManager` snapshot (live `streams` map + budget
    /// counters, no hot-path accounting). PER-NODE: `totalAllocatedBytes` / `maxTotalBytes` are that
    /// node's off-heap budget, `overBudget` its follower over-subscribe condition (false in steady state
    /// since increment 3 removed over-subscription). `deferredPartitions` (#265 increment 3) is the
    /// node-wide count of held-but-not-yet-materialized partitions — the budget-defer sensor (spec §6).
    /// `streams` carries one row per live stream. `perStreamCeiling` / `clusterAggregateGuard` /
    /// `currentAggregatePartitionSlots` / `aggregateHeadroom` / `configOverCeilingStreams` (#265 increment 4,
    /// spec §7) add the partition-cap observability: the absolute per-stream ceiling, the
    /// `100 × nodes × maxDeclaredReplicas` aggregate guard (`-1` when the cluster size is unknown), the current
    /// cluster ring-slot total (Σ `partitions × replicas`), the remaining headroom, and the count of streams
    /// whose committed config is over the ceiling. A later increment gates materialization on placement, at
    /// which point `ringsMaterialized` drops below `partitionsDeclared` on non-replicas — this surface is
    /// how that memory win is observed.
    record StreamHydrationResponse(long totalAllocatedBytes,
                                   long maxTotalBytes,
                                   boolean overBudget,
                                   long deferredPartitions,
                                   int perStreamCeiling,
                                   long clusterAggregateGuard,
                                   long currentAggregatePartitionSlots,
                                   long aggregateHeadroom,
                                   int configOverCeilingStreams,
                                   long releaseCandidates,
                                   long releasedPartitionsSinceBoot,
                                   long materializeQueueDepth,
                                   List<StreamHydrationDetail> streams) {}

    /// #488 declarative-consumer view for THIS node. `attachedSubscriptions` is the count actually
    /// subscribed here, which for a correctly assigned consumer equals the number of partitions this
    /// node was assigned — not the stream's partition count. `cursorCommitFailureCount` (#654) is the
    /// node-wide count of cursor commits — final flush at detach, or periodic checkpoint — that
    /// failed or did not settle within their shutdown bound; see the redelivery contract on
    /// `org.pragmatica.aether.stream.StreamConsumerRuntime#close`. It is not reset by a redeploy or a
    /// reconcile, only by a node restart.
    record DeclarativeConsumersResponse(int attachedSubscriptions,
                                        long cursorCommitFailureCount,
                                        List<DeclarativeConsumerDetail> consumers) {}

    /// One declared `[streams.X]` consumer as this node sees it.
    ///
    /// `unassignedPartitions` is the loud gap (#535): partitions whose declared consumer slice is ACTIVE
    /// on NO live node, so nothing can run the handler and they are consumed by nobody. It is NOT a gap
    /// for this node to lack the slice — since #535 the partition's owner no longer has to host it, and
    /// `partitionAssignments` names which node consumes each partition and which owns it. Reads are
    /// forwarded to the owner whenever those two differ.
    ///
    /// `eventTypePublishable` is absent when this node cannot know: the probe needs the slice's own
    /// codec registry, which only a node hosting the slice has. When present and false, the event type
    /// has no codec there, so it cannot be published to the stream at all and this consumer will receive
    /// nothing however healthy it otherwise looks. `diagnostic` carries the operator-facing explanation
    /// for whichever condition applies, and is empty when the consumer is healthy.
    record DeclarativeConsumerDetail(String stream,
                                     String configSection,
                                     String artifact,
                                     String method,
                                     String consumerGroup,
                                     boolean batchMode,
                                     String eventType,
                                     boolean sliceDeployedLocally,
                                     Option<Boolean> eventTypePublishable,
                                     List<DeclarativeConsumerPartition> assignedPartitions,
                                     List<Integer> unassignedPartitions,
                                     List<DeclarativeConsumerAssignment> partitionAssignments,
                                     String diagnostic) {}

    /// Who consumes one partition and who owns it, as computed locally. Both are absent only during the
    /// bootstrap window; `consumerNode` is additionally absent when nothing can consume the partition.
    record DeclarativeConsumerAssignment(int partition, Option<String> consumerNode, Option<String> ownerNode) {}

    /// `committedOffset` is the next offset this consumer will read — one past the last delivered
    /// event. `lastCursorCommitFailure` (#654) is this partition's most recent cursor commit failure
    /// detail while the consumer stays attached, empty when its last commit succeeded — same
    /// empty-for-absent convention as `DeclarativeConsumerDetail#diagnostic`.
    record DeclarativeConsumerPartition(int partition,
                                        long committedOffset,
                                        boolean stalled,
                                        String lastCursorCommitFailure) {}

    /// Per-stream hydration row: `partitionsDeclared` the configured partition count,
    /// `ringsMaterialized` the rings actually built on this node (gated below declared on non-replicas),
    /// `partitionsDeferred` (#265 increment 3) the held partitions not yet materialized (budget-deferred
    /// per spec §6 or pre-membership), `floorBytesAllocated` the per-partition floor times the
    /// materialized ring count, `overCeiling` (#265 increment 4) whether this committed config declares
    /// more partitions than the per-stream ceiling (the follower-defense flag; materialization still
    /// proceeds under the budget backstop), and `ownerPartitions` / `replicaPartitions` / `nonePartitions`
    /// the placement-role tally for this node under the current supplier (default: all OWNER).
    record StreamHydrationDetail(String stream,
                                 int partitionsDeclared,
                                 int ringsMaterialized,
                                 int partitionsDeferred,
                                 long floorBytesAllocated,
                                 boolean overCeiling,
                                 int ownerPartitions,
                                 int replicaPartitions,
                                 int nonePartitions) {}

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

    /// Scale one (source, role) to `count` (RFC-0017 C1).
    ///
    /// REPLACES a bare `coreCount`, which could not name which source absorbed the change and did
    /// not match what the CLI was already sending — the CLI posted `source`/`role`/`count` while
    /// this record read `coreCount`, so no scale request ever carried a usable count.
    ///
    /// A blank `source` asks the server to infer it, which succeeds only when exactly one source
    /// carries `role`. `role` defaults to core when blank.
    record ScaleRequest(String source, String role, int count, long expectedVersion) {}

    record ScaleClusterResponse(boolean success,
                                String source,
                                String role,
                                int previousCount,
                                int newCount,
                                long configVersion) {}

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

    /// Request shape for `POST /api/nodes/promote/{id}` (P-NEW-E, 2026-05-21).
    /// Promotes a single node from its current role to `targetRole`, by writing a
    /// fresh `ActivationDirectiveValue` under `ActivationDirectiveKey(nodeId)`
    /// via consensus. The downstream `ClusterDeploymentManager` consumes the
    /// `ActivationDirectivePutReceived` event and drives the role-aware node
    /// machinery (`ForwardingClusterNode` / `SwitchableClusterNode`) to align
    /// runtime behavior to the new role.
    ///
    /// Accepted `targetRole` values: `"CORE"` (case-insensitive `"core"`),
    /// `"WORKER"` (case-insensitive `"worker"`). Any other value yields a
    /// 400-style validation failure. The CORE → WORKER and WORKER → CORE
    /// transitions are both supported.
    ///
    /// Route target is `LEADER` — the leader is the consensus writer; the
    /// management plane forwards the request automatically when posted to a
    /// follower. See `aether/docs/internal/production-readiness-followup-2026-05-21.md`
    /// P-NEW-E.
    record PromoteNodeRequest(String targetRole) {}

    /// Response shape for `POST /api/nodes/promote/{id}`. `previousRole` reflects
    /// the role observed in the KV-Store ActivationDirective at the start of the
    /// request (`"CORE"` when no directive existed — every joining node defaults
    /// to CORE until an operator promotes it). `newRole` is the role just
    /// written. `nodeId` is the target node. `success=true` is returned only
    /// once the consensus write succeeds.
    record PromoteNodeResponse(boolean success, String nodeId, String previousRole, String newRole, String message) {}

    /// Wire shape for `GET /api/versions` (#198 §11.3) — the version registries of the versioned
    /// slices THIS node has deployed, read from its local `HttpRoutePublisher`. `slices` is empty when
    /// the node hosts no versioned slice.
    record VersionsResponse(List<VersionedSliceView> slices) {}

    /// #345 I3 — what THIS node has checkpointed for each durable-entity keyspace it folds.
    ///
    /// `writes` is the positive signal: it must climb while a keyspace is taking writes. A driver that
    /// stopped leaves it flat while everything else still looks healthy, which is the failure this
    /// surface exists to make visible. `failures` and `checkpointedThrough` localise it — a partition
    /// whose offset stops advancing while others move is stuck on its own, not cluster-wide.
    ///
    /// A partition this node has never folded is ABSENT from `checkpointedThrough` rather than reported
    /// as `0`: "nothing to say about it" and "checkpointed through offset 0" are different claims.
    record EntityCheckpointsResponse(List<EntityKeyspaceCheckpointView> keyspaces) {}

    record EntityKeyspaceCheckpointView(String keyspace,
                                        int partitionCount,
                                        long writes,
                                        long failures,
                                        Map<Integer, Long> checkpointedThrough) {}

    /// Per-keyspace HOSTING view (#634-3, entity hosting-set fold-in, owner-ruled 2026-08-24): the set
    /// of nodes with a committed per-node registration IS the candidate set the leader mints entity-arc
    /// owners over, and until this surface it was invisible — the 02w hosting-set defect was diagnosed
    /// from typed write refusals instead of one GET. Assembled from replicated KV, so any caught-up
    /// node answers identically. `partitionCountsDisagree` mirrors the reconciler's rolling-redeploy
    /// signal: hosts declared different counts, arcs span the max until configs re-converge.
    record EntityKeyspacesResponse(List<EntityKeyspaceView> keyspaces) {}

    record EntityKeyspaceView(String keyspace,
                              int partitionCount,
                              List<String> hosts,
                              boolean partitionCountsDisagree) {}

    /// Per-slice version registry projection in [VersionsResponse]. `slice` is the deployed artifact
    /// coordinate; `apiPrefix` is the version-agnostic base prefix; `requireVersionHeader` and
    /// `defaultVersion` are the header-mode detection knobs (`defaultVersion` is `Option.none()` /
    /// JSON `null` when no version declares `defaultIfMissing`); `versions` lists each declared
    /// version's lifecycle metadata.
    record VersionedSliceView(String slice,
                              String apiPrefix,
                              boolean requireVersionHeader,
                              Option<Integer> defaultVersion,
                              List<VersionView> versions) {}

    /// Per-version lifecycle metadata in [VersionedSliceView]. `sunset` is `Option.none()` (JSON
    /// `null`) when the version declares no sunset date; `defaultIfMissing` is `true` for the version
    /// the slice serves when the version header is absent (i.e. it equals the slice's `defaultVersion`).
    record VersionView(int version, boolean deprecated, Option<String> sunset, boolean defaultIfMissing) {}
}
