// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.CertificateStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusNodeInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LoadBalancerStatusInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ProvisionFailureInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ProvisioningCircuitBreakerInfo;
import org.pragmatica.aether.api.ManagementApiResponses.ProvisioningDiagnosticsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.DryRunResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleClusterResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.aether.api.ManagementApiResponses.TopologyEntryInfo;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeResponse;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.deployment.cluster.ClusterConfigApplier;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.ProvisioningDiagnostics;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
public final class ClusterConfigRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(ClusterConfigRoutes.class);

    private final Supplier<ManageableNode> nodeSupplier;
    private final ClusterConfigApplier applier;

    private ClusterConfigRoutes(Supplier<ManageableNode> nodeSupplier, ClusterConfigApplier applier) {
        this.nodeSupplier = nodeSupplier;
        this.applier = applier;
    }

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<ManageableNode> nodeSupplier,
                                                          ClusterConfigApplier applier) {
        return new ClusterConfigRoutes(nodeSupplier, applier);
    }

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterConfigRoutes(nodeSupplier, new ClusterConfigApplier.unused());
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClusterConfigResponse> route(ManagementRoute.CLUSTER_CONFIG_GET)
                                         .to(_ -> buildConfigResponse())
                                         .asJson(),
                         ManagementRoutes.<ProvisioningDiagnosticsResponse> route(ManagementRoute.CLUSTER_PROVISIONING_GET)
                                         .to(_ -> buildProvisioningResponse())
                                         .asJson(),
                         ManagementRoutes.<ClusterStatusResponse> route(ManagementRoute.CLUSTER_STATUS)
                                         .to(_ -> buildStatusResponse())
                                         .asJson(),
                         ManagementRoutes.<Object> route(ManagementRoute.CLUSTER_CONFIG_APPLY)
                                         .withBody(ApplyConfigRequest.class)
                                         .toJson(this::handleApplyConfig),
                         ManagementRoutes.<ScaleClusterResponse> route(ManagementRoute.CLUSTER_SCALE)
                                         .withBody(ScaleRequest.class)
                                         .toJson(this::handleScale),
                         ManagementRoutes.<UpgradeResponse> route(ManagementRoute.CLUSTER_UPGRADE)
                                         .withBody(UpgradeRequest.class)
                                         .toJson(this::handleUpgrade));
    }

    private Promise<ClusterConfigResponse> buildConfigResponse() {
        return lookupClusterConfig().map(ClusterConfigRoutes::toConfigResponse);
    }

    private static ClusterConfigResponse toConfigResponse(ClusterConfigValue config) {
        return new ClusterConfigResponse(config.tomlContent(),
                                         config.clusterName(),
                                         config.version(),
                                         config.coreCount(),
                                         toTopologyInfo(config),
                                         config.coreMin(),
                                         config.coreMax(),
                                         config.deploymentType(),
                                         config.configVersion(),
                                         config.updatedAt());
    }

    private static List<TopologyEntryInfo> toTopologyInfo(ClusterConfigValue config) {
        return config.desiredTopology()
                     .stream()
                     .map(entry -> new TopologyEntryInfo(entry.sourceName(),
                                                         entry.role(),
                                                         entry.count()))
                     .toList();
    }

    /// #336 observability — flatten the assembled provisioning diagnostics into a readable JSON view.
    /// `provisioningDiagnostics()` is present only on the leader that owns a `ClusterTopologyManager`;
    /// elsewhere it is empty, in which case an explanatory `leader=false` body is returned (matching how
    /// sibling routes surface "no data" — a valid body rather than an error).
    private Promise<ProvisioningDiagnosticsResponse> buildProvisioningResponse() {
        return Promise.success(nodeSupplier.get()
                                           .provisioningDiagnostics()
                                           .map(ClusterConfigRoutes::toProvisioningResponse)
                                           .or(NOT_LEADER_PROVISIONING));
    }

    private static ProvisioningDiagnosticsResponse toProvisioningResponse(ProvisioningDiagnostics diagnostics) {
        var decision = diagnostics.decision();
        var breaker = diagnostics.circuitBreaker();
        var deficit = Math.max(0,
                               decision.configuredCoreCount() - decision.effective());

        return new ProvisioningDiagnosticsResponse(true,
                                                   decision.configuredCoreCount(),
                                                   decision.countedCoreMembers(),
                                                   decision.effective(),
                                                   deficit,
                                                   decision.armedForProvisioning(),
                                                   decision.reachedFullMembership(),
                                                   decision.quorumSafe(),
                                                   decision.trigger().name(),
                                                   decision.reason(),
                                                   decision.deficitAgeMs(),
                                                   new ProvisioningCircuitBreakerInfo(breaker.consecutiveFailures(),
                                                                                      breaker.tripped(),
                                                                                      breaker.nextAllowedMs()),
                                                   diagnostics.lastProvisionFailure()
                                                              .map(ClusterConfigRoutes::toFailureInfo));
    }

    private static ProvisionFailureInfo toFailureInfo(ClusterTopologyManager.LastProvisionFailure failure) {
        return new ProvisionFailureInfo(failure.cause(), failure.atEpochMs());
    }

    private static final ProvisioningDiagnosticsResponse NOT_LEADER_PROVISIONING = new ProvisioningDiagnosticsResponse(false,
                                                                                                                       0,
                                                                                                                       0,
                                                                                                                       0,
                                                                                                                       0,
                                                                                                                       false,
                                                                                                                       false,
                                                                                                                       false,
                                                                                                                       "NONE",
                                                                                                                       "Provisioning diagnostics available only on the leader that owns a cluster topology manager",
                                                                                                                       0L,
                                                                                                                       new ProvisioningCircuitBreakerInfo(0,
                                                                                                                                                          false,
                                                                                                                                                          0L),
                                                                                                                       Option.none());

    private Promise<ClusterStatusResponse> buildStatusResponse() {
        var node = nodeSupplier.get();

        return lookupClusterConfig().map(config -> assembleStatus(node, config));
    }

    private ClusterStatusResponse assembleStatus(ManageableNode node, ClusterConfigValue config) {
        var leaderId = node.leader().map(NodeId::id).or("none");
        var nodeInfos = buildNodeInfos(node, leaderId);
        var sliceCount = node.sliceStore().loaded().size();
        var sliceInstances = countSliceInstances(node);
        var certExpiry = buildCertificateExpiry(node);
        var lbInfo = buildLoadBalancerInfo(node);

        return new ClusterStatusResponse(config.clusterName(),
                                         config.version(),
                                         config.coreCount(),
                                         node.connectedNodeCount() + 1,
                                         reconcilerStateName(node),
                                         leaderId,
                                         nodeInfos,
                                         sliceCount,
                                         sliceInstances,
                                         certExpiry.map(CertificateStatusResponse::expiresAt).or("N/A"),
                                         certExpiry.map(CertificateStatusResponse::secondsUntilExpiry).or(0L),
                                         config.configVersion(),
                                         node.uptimeSeconds(),
                                         lbInfo);
    }

    private static int countSliceInstances(ManageableNode node) {
        return node.deploymentMap()
                   .allDeployments()
                   .stream()
                   .mapToInt(d -> d.instances()
                                   .size())
                   .sum();
    }

    private static List<ClusterStatusNodeInfo> buildNodeInfos(ManageableNode node, String leaderId) {
        var leaderOpt = node.leader();
        var view = node.membershipView();
        // RC1 membership-v2: per-peer state sourced from the NTT-derived generation
        // snapshot's
        // `coreMembers` lifecycle enum (mirrors StatusRoutes.lifecycleStateMap). Empty when no
        // snapshot has been published yet.
        var kvStateMap = reportedStateMap(node);

        return node.metricsCollector()
                   .allMetrics()
                   .keySet()
                   .stream()
                   .map(nid -> toStatusNodeInfo(nid,
                                                leaderOpt,
                                                view,
                                                kvStateMap.getOrDefault(nid, ""),
                                                leaderId))
                   .toList();
    }

    private static ClusterStatusNodeInfo toStatusNodeInfo(NodeId nid,
                                                          Option<NodeId> leaderOpt,
                                                          MembershipView view,
                                                          String kvState,
                                                          String leaderId) {
        // derivedStatus — operator-visible projection, mirrors StatusRoutes.toNodeInfo:
        // present peers surface their real reported work-state (READY when no pong yet);
        // absent peers show UNKNOWN.
        var derivedStatus = view.isPresent(nid)
                            ? presentDisplay(kvState)
                            : "UNKNOWN";
        // TODO (follow-up): role is still hardcoded "core" — should read from ActivationDirectiveValue
        // in kvStore (see StatusRoutes.collectNodeRoles). Out of scope for the state-authority split.
        return new ClusterStatusNodeInfo(nid.id(),
                                         "core",
                                         kvState,
                                         derivedStatus,
                                         AetherNode.VERSION,
                                         nid.id().equals(leaderId));
    }

    private static String presentDisplay(String reportedState) {
        return reportedState.isEmpty()
               ? NodeReportedState.READY.name()
               : reportedState;
    }

    /// Membership-v2 finale: per-peer work-state display map sourced from the real
    /// node-authoritative `NodeReportedState` (SYNCING / READY / DRAINING) carried on the metrics
    /// pong — the synthetic per-node lifecycle enum was removed. Empty when no pong observed yet.
    private static Map<NodeId, String> reportedStateMap(ManageableNode node) {
        return node.metricsCollector()
                   .reportedStates()
                   .entrySet()
                   .stream()
                   .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                         entry -> entry.getValue()
                                                                       .name()));
    }

    private static String reconcilerStateName(ManageableNode node) {
        var actualCount = node.connectedNodeCount() + 1;

        return actualCount >= node.topologyConfig()
                                  .clusterSize()
               ? "CONVERGED"
               : "RECONCILING";
    }

    private static Option<CertificateStatusResponse> buildCertificateExpiry(ManageableNode node) {
        return node.certRenewalScheduler()
                   .map(scheduler -> toCertStatus(node.tlsEnabled(),
                                                  scheduler));
    }

    private static CertificateStatusResponse toCertStatus(boolean tlsEnabled, CertificateRenewalScheduler scheduler) {
        return new CertificateStatusResponse(tlsEnabled,
                                             scheduler.currentNotAfter().toString(),
                                             scheduler.secondsUntilExpiry(),
                                             scheduler.lastRenewalAt().toString(),
                                             scheduler.renewalStatus().name());
    }

    private static Option<LoadBalancerStatusInfo> buildLoadBalancerInfo(ManageableNode node) {
        return node.taskGroupOwnerResolver()
                   .apply(TaskGroup.DEPLOYMENT)
                   .option()
                   .flatMap(ownerId -> node.topologyManager()
                                           .get(ownerId)
                                           .map(info -> toLbStatusInfo(ownerId, info, node)));
    }

    private static LoadBalancerStatusInfo toLbStatusInfo(NodeId ownerId, NodeInfo info, ManageableNode node) {
        var host = info.address().host();
        var appEndpoint = "http://" + host + ":" + node.appHttpPort();
        var mgmtEndpoint = "http://" + host + ":" + node.managementPort();

        return new LoadBalancerStatusInfo("elected", ownerId.id(), appEndpoint, mgmtEndpoint);
    }

    private Promise<Object> handleApplyConfig(ApplyConfigRequest request) {
        return parseAndValidateConfig(request.tomlContent()).async()
                                     .flatMap(desired -> lookupClusterConfig().flatMap(stored -> processApply(stored,
                                                                                                              desired,
                                                                                                              request))
                                                                            .orElse(() -> storeInitialConfig(desired,
                                                                                                             request.tomlContent())));
    }

    /// The desired shape the cluster reconciles toward: every (source, role) the operator declared,
    /// carried in cluster state so cores can provision workers from it (RFC-0017). Previously only a
    /// single cluster-wide core count survived this boundary, which is why worker counts could not
    /// be acted on by the cluster at all.
    private static List<AetherValue.TopologyEntry> topologyOf(ClusterBootstrapConfig desired) {
        return desired.sources()
                      .entrySet()
                      .stream()
                      .flatMap(source -> source.getValue()
                                               .roles()
                                               .entrySet()
                                               .stream()
                                               .map(role -> new AetherValue.TopologyEntry(source.getKey(),
                                                                                          role.getKey().value(),
                                                                                          role.getValue().count().or(0))))
                      .toList();
    }

    @SuppressWarnings("unchecked")
    private Promise<Object> storeInitialConfig(ClusterBootstrapConfig desired, String tomlContent) {
        var cluster = desired.cluster();
        var coreCount = desired.derivedCoreCount();
        var coreMin = desired.coreTopology().min().or(coreCount);
        var coreMax = desired.coreTopology().max().or(coreCount);
        var sourceType = desired.sources().values().stream().map(s -> s.type()
                                                                       .value()).findFirst().orElse("unknown");
        var configValue = new ClusterConfigValue(tomlContent,
                                                 cluster.name(),
                                                 cluster.version(),
                                                 topologyOf(desired),
                                                 coreMin,
                                                 coreMax,
                                                 sourceType,
                                                 1,
                                                 System.currentTimeMillis());
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, configValue);

        return nodeSupplier.get()
                           .<Object> apply(List.of(command))
                           .map(_ -> (Object) new ApplyConfigResponse(1,
                                                                      cluster.name(),
                                                                      coreCount,
                                                                      configValue.updatedAt()));
    }

    private Promise<Object> processApply(ClusterConfigValue stored,
                                         ClusterBootstrapConfig desired,
                                         ApplyConfigRequest request) {
        return checkVersionAsync(stored.configVersion(),
                                 request.expectedVersion()).flatMap(_ -> rebuildStoredConfigAsync(stored))
                                .flatMap(storedConfig -> executeDiff(stored, storedConfig, desired, request));
    }

    /// Pure decision for the #289 fence — package-visible so it can be unit-tested without standing up
    /// a node + KV store. True iff a populated config (`storedVersion >= 1`) is about to be overwritten
    /// by an unfenced push (`expectedVersion == 0`). `expectedVersion=0` is the "fresh cluster"
    /// sentinel that bypasses the CAS check in [#checkVersionAsync]; allowing it to mutate an existing
    /// config would let a re-run of `aether cluster bootstrap` (which always posts `expectedVersion:0`)
    /// or a concurrent bootstrap blind-overwrite mutable cluster config.
    static boolean isUnfencedOverwrite(long storedVersion, long expectedVersion) {
        return storedVersion >= 1 && expectedVersion == 0;
    }

    private Promise<Object> executeDiff(ClusterConfigValue stored,
                                        ClusterBootstrapConfig storedConfig,
                                        ClusterBootstrapConfig desired,
                                        ApplyConfigRequest request) {
        var plan = ClusterBootstrapConfigDiff.diff(storedConfig, desired);

        if (plan.hasImmutableChanges()) {
            return buildImmutableChangeError(plan).promise();
        }

        if (plan.isEmpty()) {
            // No-op (e.g. an idempotent bootstrap retry of the identical config) — return a dry-run.
            // The #289 fence is intentionally NOT applied here so a benign retry-after-success stays
            // idempotent; only a real mutation is fenced below.
            return buildDryRunResponse(stored, plan);
        }
        // #289: a real mutation against an already-populated config must carry a real expectedVersion;
        // reject the unfenced `expectedVersion=0` push that would blind-overwrite mutable config.
        if (isUnfencedOverwrite(stored.configVersion(), request.expectedVersion())) {
            return new ClusterConfigError.UnfencedOverwrite(stored.configVersion()).promise();
        }

        return applier.apply(plan.allActions())
                      .flatMap(_ -> storeUpdatedConfig(desired,
                                                       request.tomlContent(),
                                                       stored.configVersion() + 1));
    }

    private static ClusterConfigError.ValidationFailed buildImmutableChangeError(DiffPlan plan) {
        var errors = plan.immutable()
                         .stream()
                         .map(a -> (ClusterConfigError) new ClusterConfigError.ImmutableFieldChange(a.description()))
                         .toList();

        return new ClusterConfigError.ValidationFailed(errors);
    }

    private static Result<ClusterBootstrapConfig> parseAndValidateConfig(String tomlContent) {
        return ClusterBootstrapConfigParser.parse(tomlContent).flatMap(ClusterBootstrapConfigValidator::validate);
    }

    private static Promise<ClusterBootstrapConfig> rebuildStoredConfigAsync(ClusterConfigValue stored) {
        return ClusterBootstrapConfigParser.parse(stored.tomlContent()).async();
    }

    private static Promise<Object> checkVersionAsync(long storedVersion, long expectedVersion) {
        if (expectedVersion != 0 && storedVersion != expectedVersion) {
            return new ClusterConfigError.VersionConflict(expectedVersion, storedVersion).promise();
        }

        return Promise.unitPromise().map(u -> (Object) u);
    }

    private Promise<Object> storeUpdatedConfig(ClusterBootstrapConfig desired, String tomlContent, long newVersion) {
        var cluster = desired.cluster();
        var coreCount = desired.derivedCoreCount();
        var coreMin = desired.coreTopology().min().or(coreCount);
        var coreMax = desired.coreTopology().max().or(coreCount);
        var sourceType = desired.sources().values().stream().map(s -> s.type()
                                                                       .value()).findFirst().orElse("unknown");
        var configValue = new ClusterConfigValue(tomlContent,
                                                 cluster.name(),
                                                 cluster.version(),
                                                 topologyOf(desired),
                                                 coreMin,
                                                 coreMax,
                                                 sourceType,
                                                 newVersion,
                                                 System.currentTimeMillis());

        return storeFencedConfig(configValue,
                                 fresh -> tomlContent.equals(fresh.tomlContent())).map(fresh -> (Object) new ApplyConfigResponse(fresh.configVersion(),
                                                                                                                                 cluster.name(),
                                                                                                                                 coreCount,
                                                                                                                                 configValue.updatedAt()));
    }

    private static Promise<Object> buildDryRunResponse(ClusterConfigValue stored, DiffPlan plan) {
        var descriptions = plan.allActions().stream().map(ClusterConfigRoutes::formatAction).toList();

        return Promise.success((Object) new DryRunResponse(stored.clusterName(),
                                                           stored.configVersion(),
                                                           stored.configVersion(),
                                                           descriptions,
                                                           0,
                                                           0));
    }

    private static String formatAction(DiffAction action) {
        return action.symbol() + " " + action.description();
    }

    private Promise<ScaleClusterResponse> handleScale(ScaleRequest request) {
        return lookupClusterConfig().flatMap(stored -> applyScale(stored, request));
    }

    /// `source` and `role` arrive from Jackson, where an omitted JSON field is `null`, and an operator
    /// who did not name a source sends `""`. Both collapse to `Option` HERE, at the deserialization
    /// boundary, so nothing downstream null-checks a `String`. Absent and blank mean the same thing to
    /// this route — "I did not say" — and deciding that once keeps the judgement in one place.
    private Promise<ScaleClusterResponse> applyScale(ClusterConfigValue stored, ScaleRequest request) {
        var role = effectiveRole(nonBlank(request.role()));
        var source = nonBlank(request.source());

        return checkVersionAsync(stored.configVersion(),
                                 request.expectedVersion()).flatMap(_ -> Promise.resolved(resolveScaleTarget(stored,
                                                                                                             source,
                                                                                                             role)))
                                .flatMap(resolved -> Promise.resolved(validateScale(stored,
                                                                                    resolved,
                                                                                    role,
                                                                                    request.count())))
                                .flatMap(resolved -> executeScale(stored,
                                                                  resolved,
                                                                  role,
                                                                  request.count()));
    }

    static Option<String> nonBlank(String value) {
        return Option.option(value).filter(candidate -> !candidate.isBlank());
    }

    static String effectiveRole(Option<String> role) {
        return role.or(TopologyEntry.CORE_ROLE);
    }

    /// Resolve which source absorbs the scale, refusing rather than guessing.
    ///
    /// An absent source is inferred only when exactly one source declares the role. A named source
    /// must already be declared: appending an undeclared (source, role) would turn a typo into a
    /// provisioning target.
    static Result<String> resolveScaleTarget(ClusterConfigValue stored, Option<String> source, String role) {
        return source.fold(() -> inferScaleTarget(stored, role), named -> declaredTargetOrRefusal(stored, named, role));
    }

    private static Result<String> declaredTargetOrRefusal(ClusterConfigValue stored, String source, String role) {
        return stored.declares(source, role)
               ? Result.success(source)
               : new ClusterConfigError.UnknownScaleTarget(source, role, declaredTargets(stored)).result();
    }

    private static Result<String> inferScaleTarget(ClusterConfigValue stored, String role) {
        var candidates = stored.sourcesWithRole(role);

        if (candidates.size() > 1) {
            return new ClusterConfigError.AmbiguousScaleSource(role, candidates).result();
        }

        return candidates.isEmpty()
               ? new ClusterConfigError.UnknownScaleTarget("", role, declaredTargets(stored)).result()
               : Result.success(candidates.getFirst());
    }

    private static List<String> declaredTargets(ClusterConfigValue stored) {
        return stored.desiredTopology()
                     .stream()
                     .map(entry -> entry.sourceName() + "/" + entry.role())
                     .toList();
    }

    private Promise<ScaleClusterResponse> executeScale(ClusterConfigValue stored,
                                                       String source,
                                                       String role,
                                                       int count) {
        var previousCount = stored.desiredCountFor(source, role);
        var scaled = stored.withDesiredCount(source, role, count);

        return storeFencedConfig(scaled, fresh -> fresh.desiredCountFor(source, role) == count).map(fresh -> new ScaleClusterResponse(true,
                                                                                                                                      source,
                                                                                                                                      role,
                                                                                                                                      previousCount,
                                                                                                                                      count,
                                                                                                                                      fresh.configVersion()));
    }

    /// Quorum arithmetic constrains CORE nodes only — it is what keeps a majority reachable. Worker
    /// and spot counts carry no such constraint, so applying `coreMin`/`coreMax` to them would
    /// refuse legitimate scales.
    ///
    /// For cores the check runs against the resulting CLUSTER-WIDE total, not the per-source count.
    /// Scaling one core source to 1 is valid when another source carries 2; checking the per-source
    /// number would reject that, and checking the old stored scalar would miss the other source
    /// entirely.
    static Result<String> validateScale(ClusterConfigValue stored, String source, String role, int count) {
        if (!TopologyEntry.CORE_ROLE.equalsIgnoreCase(role)) {
            // Zero is legitimate for worker/spot tiers — "drain all workers" is a real operation and
            // the RFC-0017 teardown path scales to zero before sweeping. Only negatives are nonsense.
            return count < 0
                   ? new ClusterConfigError.InvalidCoreCount(count).result()
                   : Result.success(source);
        }

        var clusterTotal = stored.coreCount() - stored.desiredCountFor(source, TopologyEntry.CORE_ROLE) + count;

        if (clusterTotal < 3) {
            return new ClusterConfigError.QuorumSafetyViolation(clusterTotal, 3).result();
        }

        if (clusterTotal % 2 == 0) {
            return new ClusterConfigError.InvalidCoreCount(clusterTotal).result();
        }

        if (clusterTotal < stored.coreMin()) {
            return new ClusterConfigError.QuorumSafetyViolation(clusterTotal, stored.coreMin()).result();
        }

        if (clusterTotal > stored.coreMax()) {
            return new ClusterConfigError.InvalidCoreMax(stored.coreMax(), clusterTotal).result();
        }

        return Result.success(source);
    }

    private Promise<UpgradeResponse> handleUpgrade(UpgradeRequest request) {
        return lookupClusterConfig().flatMap(stored -> initiateUpgrade(stored, request));
    }

    private Promise<UpgradeResponse> initiateUpgrade(ClusterConfigValue stored, UpgradeRequest request) {
        var currentVersion = stored.version();
        var targetVersion = request.targetVersion();

        if (currentVersion.equals(targetVersion)) {
            return new UpgradeError.AlreadyAtVersion(targetVersion).promise();
        }

        log.info("Cluster upgrade initiated: {} -> {}",
                 currentVersion,
                 targetVersion);

        return storeUpgradedVersion(stored, targetVersion).map(_ -> new UpgradeResponse("INITIATED",
                                                                                        currentVersion,
                                                                                        targetVersion));
    }

    /// Apply a fenced `Put` of the cluster config and confirm it LANDED (RFC-0018, #570).
    ///
    /// The applier's successor fence rejects a `Put` built on a stale read — but the rejection is
    /// invisible in the apply result (under batch merging every submitter receives the full merged
    /// result list), so an unconfirmed store would report success for a write that did nothing:
    /// worse than the race it closes. The engine runs the local `process` before resolving the
    /// apply promise, so a local re-read afterwards is authoritative for this batch. `landed` is a
    /// SEMANTIC check — "did the change I asked for stick" — rather than version arithmetic, which
    /// cannot distinguish my write from a competitor's at the same version.
    @SuppressWarnings("unchecked")
    private Promise<ClusterConfigValue> storeFencedConfig(ClusterConfigValue intended,
                                                          Predicate<ClusterConfigValue> landed) {
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ClusterConfigKey.CURRENT, intended);

        return nodeSupplier.get()
                           .<Object> apply(List.of(command))
                           .flatMap(_ -> lookupClusterConfig())
                           .flatMap(fresh -> landed.test(fresh)
                                             ? Promise.success(fresh)
                                             : new ClusterConfigError.VersionConflict(intended.configVersion(),
                                                                                      fresh.configVersion()).promise());
    }

    private Promise<Object> storeUpgradedVersion(ClusterConfigValue stored, String targetVersion) {
        var configValue = new ClusterConfigValue(stored.tomlContent(),
                                                 stored.clusterName(),
                                                 targetVersion,
                                                 stored.desiredTopology(),
                                                 stored.coreMin(),
                                                 stored.coreMax(),
                                                 stored.deploymentType(),
                                                 stored.configVersion() + 1,
                                                 System.currentTimeMillis());

        return storeFencedConfig(configValue,
                                 fresh -> targetVersion.equals(fresh.version())).map(fresh -> (Object) fresh);
    }

    sealed interface UpgradeError extends Cause {
        record AlreadyAtVersion(String version) implements UpgradeError {
            @Override
            public String message() {
                return "Cluster is already at version " + version;
            }
        }
    }

    private Promise<ClusterConfigValue> lookupClusterConfig() {
        return nodeSupplier.get()
                           .kvStore()
                           .get(ClusterConfigKey.CURRENT)
                           .flatMap(ClusterConfigRoutes::narrowToConfig)
                           .async(ConfigNotFoundError.NOT_FOUND);
    }

    private static Option<ClusterConfigValue> narrowToConfig(AetherValue value) {
        return value instanceof ClusterConfigValue config
               ? Option.some(config)
               : Option.empty();
    }

    private enum ConfigNotFoundError implements Cause {
        NOT_FOUND("No cluster configuration stored");
        private final String message;
        ConfigNotFoundError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
