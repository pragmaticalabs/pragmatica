// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.aether.slice.topology.TopologyGraph;
import org.pragmatica.aether.slice.topology.TopologyParser;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.aether.api.ManagementApiResponses.*;


public final class SliceRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(SliceRoutes.class);

    private static final Cause MISSING_ARTIFACT_OR_INSTANCES = Causes.cause("Missing 'artifact' or 'instances' field");

    private static final Cause BLUEPRINT_NOT_FOUND = Causes.cause("Blueprint not found");
    private static final Cause SLICE_NOT_LOADED = Causes.cause("Slice not loaded or no per-slice config available");

    private static final Cause NOT_IN_BLUEPRINT = Causes.cause("Slice is not part of any active blueprint. Deploy via blueprint.");

    private final Supplier<ManageableNode> nodeSupplier;

    private SliceRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SliceRoutes sliceRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new SliceRoutes(nodeSupplier);
    }

    record ScaleRequest(String artifact, Integer instances, String placement) {}

    record BlueprintDeployRequest(String artifact) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClusterSlicesResponse> route(ManagementRoute.SLICES_LIST)
                                         .withQuery(QueryParameter.aString("state"))
                                         .toValue(this::buildClusterSlicesResponse)
                                         .asJson(),
                         ManagementRoutes.<SlicesResponse> route(ManagementRoute.NODE_SLICES).toJson(this::buildNodeSlicesResponse),
                         ManagementRoutes.<SlicesResponse> route(ManagementRoute.NODE_SLICES_GET)
                                         .withPath(aString())
                                         .to(__ -> org.pragmatica.lang.Promise.success(buildNodeSlicesResponse()))
                                         .asJson(),
                         ManagementRoutes.<SlicesStatusResponse> route(ManagementRoute.SLICES_STATUS).toJson(this::buildSlicesStatusResponse),
                         ManagementRoutes.<RoutesResponse> route(ManagementRoute.NODE_ROUTES).toJson(this::buildRoutesResponse),
                         ManagementRoutes.<RoutesResponse> route(ManagementRoute.NODE_ROUTES_GET)
                                         .withPath(aString())
                                         .to(__ -> org.pragmatica.lang.Promise.success(buildRoutesResponse()))
                                         .asJson(),
                         ManagementRoutes.<RoutesResponse> route(ManagementRoute.ROUTES_LIST).toJson(this::buildRoutesResponse),
                         ManagementRoutes.<ScaleResponse> route(ManagementRoute.SLICE_SCALE)
                                         .withBody(ScaleRequest.class)
                                         .toJson(this::handleScale),
                         ManagementRoutes.<BlueprintResponse> route(ManagementRoute.BLUEPRINT_PUBLISH_BODY)
                                         .to(ctx -> handleBlueprint(ctx.bodyAsString()))
                                         .asJson(),
                         ManagementRoutes.<BlueprintListResponse> route(ManagementRoute.BLUEPRINT_LIST).toJson(this::buildBlueprintListResponse),
                         ManagementRoutes.<BlueprintDetailResponse> route(ManagementRoute.BLUEPRINT_GET)
                                         .withPath(aString())
                                         .to(this::handleGetBlueprint)
                                         .asJson(),
                         ManagementRoutes.<BlueprintStatusResponse> route(ManagementRoute.BLUEPRINT_STATUS)
                                         .withPath(aString())
                                         .to(this::handleGetBlueprintStatus)
                                         .asJson(),
                         ManagementRoutes.<BlueprintDeleteResponse> route(ManagementRoute.BLUEPRINT_DELETE)
                                         .withPath(aString())
                                         .to(this::handleDeleteBlueprint)
                                         .asJson(),
                         ManagementRoutes.<BlueprintResponse> route(ManagementRoute.BLUEPRINT_DEPLOY)
                                         .withBody(BlueprintDeployRequest.class)
                                         .toJson(this::handleBlueprintDeploy),
                         ManagementRoutes.<BlueprintResponse> route(ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT)
                                         .withBody(BlueprintDeployRequest.class)
                                         .toJson(this::handleBlueprintPublish),
                         ManagementRoutes.<BlueprintValidationResponse> route(ManagementRoute.BLUEPRINT_VALIDATE)
                                         .to(ctx -> handleValidateBlueprint(ctx.bodyAsString()))
                                         .asJson(),
                         ManagementRoutes.<TopologyResponse> route(ManagementRoute.SLICE_TOPOLOGY).toJson(this::buildTopologyResponse),
                         ManagementRoutes.<SliceConfigResponse> route(ManagementRoute.SLICE_CONFIG)
                                         .withPath(aString())
                                         .to(this::handleSliceConfig)
                                         .asJson());
    }

    private record ScaleParams(String artifact, int instances, Option<String> placement) {}

    private Result<ScaleParams> validateScaleRequest(ScaleRequest request) {
        return Result.all(Option.option(request.artifact()).toResult(MISSING_ARTIFACT_OR_INSTANCES),
                          Option.option(request.instances()).toResult(MISSING_ARTIFACT_OR_INSTANCES))
                     .map((art, inst) -> new ScaleParams(art,
                                                         inst,
                                                         Option.option(request.placement())));
    }

    private record ValidatedScale(ScaleParams params, Artifact artifact) {}

    private Promise<ScaleResponse> handleScale(ScaleRequest request) {
        return validateScaleRequest(request).async()
                                   .flatMap(this::resolveScaleArtifact)
                                   .flatMap(this::guardScaleConstraints)
                                   .flatMap(this::executeScale)
                                   .onFailure(cause -> log.warn("Scale operation failed: {}",
                                                                cause.message()));
    }

    private Promise<ValidatedScale> resolveScaleArtifact(ScaleParams params) {
        return Artifact.artifact(params.artifact())
                       .async()
                       .map(artifact -> new ValidatedScale(params, artifact));
    }

    private Promise<ValidatedScale> guardScaleConstraints(ValidatedScale vs) {
        return guardBlueprintMembership(vs.artifact()).flatMap(_ -> guardMinInstances(vs.artifact(),
                                                                                      vs.params().instances()))
                                       .map(_ -> vs);
    }

    private Promise<ScaleResponse> executeScale(ValidatedScale vs) {
        return applyDeployCommand(vs.artifact(),
                                  vs.params().instances(),
                                  vs.params().placement()).map(_ -> new ScaleResponse("scaled",
                                                                                      vs.artifact().asString(),
                                                                                      vs.params().instances()));
    }

    private static final Cause BELOW_MIN_INSTANCES = Causes.cause("Requested instances is below blueprint minimum");

    private Promise<Unit> guardMinInstances(Artifact artifact, int requestedInstances) {
        if (requestedInstances < 1) {
            return BELOW_MIN_INSTANCES.promise();
        }

        var node = nodeSupplier.get();
        var key = SliceTargetKey.sliceTargetKey(artifact.base());

        return node.kvStore()
                   .get(key)
                   .filter(v -> v instanceof SliceTargetValue)
                   .map(v -> ((SliceTargetValue) v).effectiveMinInstances())
                   .map(min -> requestedInstances >= min
                               ? Promise.unitPromise()
                               : Causes.cause("Requested " + requestedInstances
                                             + " instances but blueprint minimum is " + min).<Unit> promise())
                   .or(Promise.unitPromise());
    }

    private Promise<Unit> guardBlueprintMembership(Artifact artifact) {
        return isPartOfActiveBlueprint(artifact)
               ? Promise.unitPromise()
               : NOT_IN_BLUEPRINT.promise();
    }

    private boolean isPartOfActiveBlueprint(Artifact artifact) {
        return nodeSupplier.get()
                           .blueprintService()
                           .list()
                           .stream()
                           .flatMap(blueprint -> blueprint.loadOrder()
                                                          .stream())
                           .anyMatch(slice -> slice.artifact()
                                                   .base()
                                                   .equals(artifact.base()));
    }

    private Promise<BlueprintResponse> handleBlueprint(String body) {
        return nodeSupplier.get()
                           .blueprintService()
                           .publish(body)
                           .withSuccess(this::onBlueprintActivated)
                           .map(expanded -> blueprintResponse("applied", expanded))
                           .onFailure(cause -> log.warn("Blueprint publish failed: {}",
                                                        cause.message()));
    }

    private static final Cause MISSING_ARTIFACT_COORDS = Causes.cause("Missing 'artifact' field");

    /// #759 — "deployed" must mean every declared instance is verified ACTIVE at response time, not
    /// merely that the publish command committed. `deployStatus` reads `deploymentMap()` to name three
    /// honest outcomes: `degraded` for an already-FAILED instance (reachable for BEST_EFFORT deploys
    /// and for redeploys onto an artifact with a lingering failure — under the default ALL_OR_NOTHING
    /// atomicity a fresh deterministic failure usually loses the race to
    /// `ClusterDeploymentState.rollbackBlueprintForArtifact`'s cleanup before this check runs),
    /// `pending` for the common case where nothing has activated yet, and `deployed` only once every
    /// target instance is already observed ACTIVE.
    private Promise<BlueprintResponse> handleBlueprintDeploy(BlueprintDeployRequest request) {
        return Option.option(request.artifact())
                     .toResult(MISSING_ARTIFACT_COORDS)
                     .async()
                     .flatMap(coords -> nodeSupplier.get()
                                                    .blueprintService()
                                                    .publishFromArtifact(coords))
                     .withSuccess(this::onBlueprintActivated)
                     .map(this::deployBlueprintResponse)
                     .onFailure(cause -> log.warn("Blueprint artifact deploy failed: {}",
                                                  cause.message()));
    }

    private Promise<BlueprintResponse> handleBlueprintPublish(BlueprintDeployRequest request) {
        return Option.option(request.artifact())
                     .toResult(MISSING_ARTIFACT_COORDS)
                     .async()
                     .flatMap(coords -> nodeSupplier.get()
                                                    .blueprintService()
                                                    .publishFromArtifact(coords, true))
                     .map(expanded -> blueprintResponse("published", expanded))
                     .onSuccess(r -> log.info("Blueprint {} published (register-only — not activated)",
                                              r.blueprint()))
                     .onFailure(cause -> log.warn("Blueprint artifact publish failed: {}",
                                                  cause.message()));
    }

    private void pushSecurityOverrides(ExpandedBlueprint expanded) {
        nodeSupplier.get()
                    .appHttpServer()
                    .httpRoutePublisher()
                    .onPresent(pub -> pub.updateSecurityOverrides(expanded.securityOverrides()));
    }

    private void onBlueprintActivated(ExpandedBlueprint expanded) {
        pushSecurityOverrides(expanded);
        auditAndEmitBlueprintDeployed(expanded.id().asString(),
                                      expanded.loadOrder().size());
    }

    private record InstanceCounts(int target, int active, int failed) {}

    private InstanceCounts instanceCounts(ExpandedBlueprint expanded) {
        var node = nodeSupplier.get();
        var target = expanded.loadOrder().stream().mapToInt(ResolvedSlice::instances).sum();
        var active = expanded.loadOrder()
                             .stream()
                             .mapToInt(slice -> countInstancesInState(node,
                                                                      slice.artifact(),
                                                                      SliceState.ACTIVE))
                             .sum();
        var failed = expanded.loadOrder()
                             .stream()
                             .mapToInt(slice -> countInstancesInState(node,
                                                                      slice.artifact(),
                                                                      SliceState.FAILED))
                             .sum();

        return new InstanceCounts(target, active, failed);
    }

    private BlueprintResponse blueprintResponse(String status, ExpandedBlueprint expanded) {
        var counts = instanceCounts(expanded);
        var id = expanded.id().asString();

        return new BlueprintResponse(status,
                                     id,
                                     counts.target(),
                                     counts.active(),
                                     counts.failed(),
                                     blueprintStatusUrl(id));
    }

    private BlueprintResponse deployBlueprintResponse(ExpandedBlueprint expanded) {
        var counts = instanceCounts(expanded);
        var id = expanded.id().asString();

        return new BlueprintResponse(deployStatus(counts),
                                     id,
                                     counts.target(),
                                     counts.active(),
                                     counts.failed(),
                                     blueprintStatusUrl(id));
    }

    private static String deployStatus(InstanceCounts counts) {
        if (counts.failed() > 0) {
            return "degraded";
        }

        return counts.target() > 0 && counts.active() >= counts.target()
               ? "deployed"
               : "pending";
    }

    /// #759 review — the prefix is derived from the registered [ManagementRoute#BLUEPRINT_STATUS]
    /// route rather than duplicated as a literal, so this URL can never drift from the path the
    /// server actually serves (a hardcoded `/api/blueprints/status/` here 404'd against the real
    /// `/api/v1/blueprints/status/` route). `id` is artifact-shaped (`group:artifact:version`), so
    /// its colons must still be percent-encoded to sit in a path segment; see
    /// `aether/docs/reference/management-api.md`.
    private static String blueprintStatusUrl(String id) {
        return ManagementRoute.BLUEPRINT_STATUS.prefix() + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

    private BlueprintListResponse buildBlueprintListResponse() {
        var blueprints = nodeSupplier.get().blueprintService().list().stream().map(this::toBlueprintSummary).toList();

        return new BlueprintListResponse(blueprints);
    }

    private BlueprintSummary toBlueprintSummary(ExpandedBlueprint blueprint) {
        return new BlueprintSummary(blueprint.id().asString(),
                                    blueprint.loadOrder().size());
    }

    private Promise<BlueprintDetailResponse> handleGetBlueprint(String id) {
        return BlueprintId.blueprintId(id)
                          .async()
                          .flatMap(blueprintId -> nodeSupplier.get()
                                                              .blueprintService()
                                                              .get(blueprintId)
                                                              .async(BLUEPRINT_NOT_FOUND))
                          .map(this::toBlueprintDetailResponse);
    }

    private BlueprintDetailResponse toBlueprintDetailResponse(ExpandedBlueprint blueprint) {
        var slices = blueprint.loadOrder().stream().map(this::toBlueprintSliceInfo).toList();
        var dependencies = blueprint.loadOrder()
                                    .stream()
                                    .filter(ResolvedSlice::isDependency)
                                    .map(s -> s.artifact()
                                               .asString())
                                    .toList();

        return new BlueprintDetailResponse(blueprint.id().asString(),
                                           slices,
                                           dependencies);
    }

    private BlueprintSliceInfo toBlueprintSliceInfo(ResolvedSlice slice) {
        var deps = slice.dependencies().stream().map(Artifact::asString).toList();

        return new BlueprintSliceInfo(slice.artifact().asString(),
                                      slice.instances(),
                                      slice.isDependency(),
                                      deps);
    }

    /// #759 Phase 2 — `outcome(id)` is consulted UNCONDITIONALLY, before `get(id)`. A terminal
    /// `FAILED`/`ROLLED_BACK` outcome wins over whatever `get(id)` currently holds — including a
    /// stale non-empty value the with-previous rollback path can leave behind (store-side defect
    /// tracked separately for stream B, out of scope here) — because the durable outcome record is
    /// authoritative regardless of what the live KV entry happens to contain. Only when the outcome
    /// is `SUCCEEDED` or absent (`Option.none()` — never deployed, still in flight, or orphaned; see
    /// `BlueprintService.outcome` for why those three are indistinguishable here) does the route fall
    /// back to the pre-existing `get(id)`-based logic: present → 200 with live slice detail
    /// (unchanged), empty → 404 `BLUEPRINT_NOT_FOUND`.
    private Promise<BlueprintStatusResponse> handleGetBlueprintStatus(String id) {
        return BlueprintId.blueprintId(id)
                          .async()
                          .flatMap(this::routeBlueprintStatusByOutcome);
    }

    private Promise<BlueprintStatusResponse> routeBlueprintStatusByOutcome(BlueprintId blueprintId) {
        return nodeSupplier.get()
                           .blueprintService()
                           .outcome(blueprintId)
                           .filter(SliceRoutes::isTerminalFailureOutcome)
                           .fold(() -> handleGetBlueprintStatusFromStore(blueprintId),
                                 outcome -> Promise.success(toBlueprintStatusResponse(blueprintId, outcome)));
    }

    private static boolean isTerminalFailureOutcome(DeploymentOutcomeValue outcome) {
        return outcome.status() == DeploymentOutcomeStatus.FAILED || outcome.status() == DeploymentOutcomeStatus.ROLLED_BACK;
    }

    private Promise<BlueprintStatusResponse> handleGetBlueprintStatusFromStore(BlueprintId blueprintId) {
        return nodeSupplier.get()
                           .blueprintService()
                           .get(blueprintId)
                           .async(BLUEPRINT_NOT_FOUND)
                           .map(this::toBlueprintStatusResponse);
    }

    private BlueprintStatusResponse toBlueprintStatusResponse(BlueprintId blueprintId, DeploymentOutcomeValue outcome) {
        return new BlueprintStatusResponse(blueprintId.asString(),
                                           outcome.status().name(),
                                           List.of(),
                                           outcome.cause(),
                                           outcome.failingSlices(),
                                           outcome.timestampMs());
    }

    private BlueprintStatusResponse toBlueprintStatusResponse(ExpandedBlueprint blueprint) {
        var node = nodeSupplier.get();
        var sliceStatuses = blueprint.loadOrder().stream().map(slice -> computeSliceStatus(node, slice)).toList();
        var overallStatus = computeOverallStatus(sliceStatuses);

        return new BlueprintStatusResponse(blueprint.id().asString(),
                                           overallStatus,
                                           sliceStatuses,
                                           "",
                                           List.of(),
                                           0L);
    }

    /// #759 — reads `SliceState.FAILED` alongside `ACTIVE` so a slice with failed instances still
    /// present in `deploymentMap()` (e.g. a `BEST_EFFORT` deploy, or a query landing before
    /// `ALL_OR_NOTHING` rollback cleanup removes the entry) is reported `FAILED` instead of silently
    /// folded into `PENDING`/`DEPLOYING`.
    private BlueprintSliceStatus computeSliceStatus(ManageableNode node, ResolvedSlice slice) {
        var artifact = slice.artifact();
        var targetInstances = slice.instances();
        var activeInstances = countActiveInstances(node, artifact);
        var failedInstances = countInstancesInState(node, artifact, SliceState.FAILED);
        var status = determineSliceDeploymentStatus(targetInstances, activeInstances, failedInstances);

        return new BlueprintSliceStatus(artifact.asString(), targetInstances, activeInstances, failedInstances, status);
    }

    private int countActiveInstances(ManageableNode node, Artifact artifact) {
        return countInstancesInState(node, artifact, SliceState.ACTIVE);
    }

    private static int countInstancesInState(ManageableNode node, Artifact artifact, SliceState state) {
        return (int) node.deploymentMap()
                         .byArtifact(artifact)
                         .values()
                         .stream()
                         .filter(s -> s == state)
                         .count();
    }

    private String determineSliceDeploymentStatus(int target, int active, int failed) {
        if (failed > 0) {
            return "FAILED";
        } else if (active == 0) {
            return "PENDING";
        } else if (active < target) {
            return "DEPLOYING";
        } else if (active == target) {
            return "DEPLOYED";
        } else {
            return "SCALING_DOWN";
        }
    }

    /// #759 — `FAILED` takes priority over every other bucket, mirroring `deployStatus`'s
    /// failed-first precedence for the deploy response.
    private String computeOverallStatus(List<BlueprintSliceStatus> sliceStatuses) {
        var hasFailed = sliceStatuses.stream().anyMatch(s -> "FAILED".equals(s.status()));
        var hasPending = sliceStatuses.stream().anyMatch(s -> "PENDING".equals(s.status()));
        var hasDeploying = sliceStatuses.stream().anyMatch(s -> "DEPLOYING".equals(s.status()));
        var hasScalingDown = sliceStatuses.stream().anyMatch(s -> "SCALING_DOWN".equals(s.status()));
        var allDeployed = sliceStatuses.stream().allMatch(s -> "DEPLOYED".equals(s.status()));

        if (hasFailed) {
            return "FAILED";
        } else if (allDeployed) {
            return "DEPLOYED";
        } else if (hasPending) {
            return "PENDING";
        } else if (hasDeploying || hasScalingDown) {
            return "IN_PROGRESS";
        } else {
            return "PARTIAL";
        }
    }

    private Promise<BlueprintDeleteResponse> handleDeleteBlueprint(String id) {
        return BlueprintId.blueprintId(id)
                          .async()
                          .flatMap(blueprintId -> nodeSupplier.get()
                                                              .blueprintService()
                                                              .delete(blueprintId)
                                                              .map(_ -> new BlueprintDeleteResponse("deleted",
                                                                                                    blueprintId.asString())))
                          .onSuccess(r -> auditAndEmitBlueprintDeleted(r.id()))
                          .onFailure(cause -> log.warn("Blueprint delete failed: {}",
                                                       cause.message()));
    }

    private void auditAndEmitBlueprintDeployed(String blueprintId, int sliceCount) {
        AuditLog.blueprintDeployed(blueprintId, sliceCount);
        nodeSupplier.get().route(OperationalEvent.BlueprintDeployed.blueprintDeployed(blueprintId, "api"));
    }

    private void auditAndEmitBlueprintDeleted(String blueprintId) {
        AuditLog.blueprintDeleted(blueprintId);
        nodeSupplier.get().route(OperationalEvent.BlueprintDeleted.blueprintDeleted(blueprintId, "api"));
    }

    private Promise<BlueprintValidationResponse> handleValidateBlueprint(String body) {
        var warnings = BlueprintParser.detectUnrecognizedSections(body);

        return Promise.success(nodeSupplier.get()
                                           .blueprintService()
                                           .validate(body)
                                           .fold(cause -> failedValidationResponse(cause, warnings),
                                                 blueprint -> successValidationResponse(blueprint, warnings)));
    }

    private static BlueprintValidationResponse failedValidationResponse(Cause cause, List<String> warnings) {
        return new BlueprintValidationResponse(false,
                                               "",
                                               0,
                                               List.of(cause.message()),
                                               warnings);
    }

    private static BlueprintValidationResponse successValidationResponse(Blueprint blueprint, List<String> warnings) {
        return new BlueprintValidationResponse(true,
                                               blueprint.id().asString(),
                                               blueprint.slices().size(),
                                               List.of(),
                                               warnings);
    }

    private Promise<List<Long>> applyDeployCommand(Artifact artifact, int instances, Option<String> placement) {
        var node = nodeSupplier.get();
        var key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        var existing = node.kvStore()
                           .get(key)
                           .filter(v -> v instanceof AetherValue.SliceTargetValue)
                           .map(v -> applyScaleToExisting((AetherValue.SliceTargetValue) v,
                                                          instances,
                                                          placement));
        var defaultPlacement = placement.or("CORE_ONLY");
        AetherValue value = existing.or(AetherValue.SliceTargetValue.sliceTargetValue(artifact.version(),
                                                                                      instances,
                                                                                      instances,
                                                                                      defaultPlacement));
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);

        return node.apply(List.of(command));
    }

    private static AetherValue.SliceTargetValue applyScaleToExisting(AetherValue.SliceTargetValue existing,
                                                                     int instances,
                                                                     Option<String> placement) {
        var updated = existing.withInstances(instances);

        return placement.map(updated::withPlacement)
                        .or(updated);
    }

    private Promise<SliceConfigResponse> handleSliceConfig(String id) {
        return Artifact.artifact(id)
                       .async()
                       .flatMap(this::buildSliceConfigResponse);
    }

    private Promise<SliceConfigResponse> buildSliceConfigResponse(Artifact artifact) {
        return nodeSupplier.get()
                           .sliceStore()
                           .sliceComposite(artifact)
                           .async(SLICE_NOT_LOADED)
                           .map(composite -> projectSliceConfig(artifact, composite));
    }

    private static SliceConfigResponse projectSliceConfig(Artifact artifact, ConfigurationProvider composite) {
        var entries = composite.keys().stream().sorted().map(key -> attribute(key, composite)).toList();

        return new SliceConfigResponse(artifact.asString(), entries);
    }

    private static SliceConfigEntry attribute(String key, ConfigurationProvider composite) {
        var value = composite.getString(key).or("");
        var source = composite instanceof LayeredConfigProvider layered
                     ? layered.sourceOf(key).map(LayeredConfigProvider.SourceAttribution::layerName).or("unknown")
                     : composite.displayName();

        return new SliceConfigEntry(key, value, source);
    }

    private TopologyResponse buildTopologyResponse() {
        var topologies = collectSliceTopologies();
        var graph = TopologyGraph.build(topologies);

        return toTopologyResponse(graph);
    }

    private List<SliceTopology> collectSliceTopologies() {
        var node = nodeSupplier.get();
        var loaded = node.sliceStore().loaded();

        log.debug("buildTopologyResponse: loaded slices={}", loaded.size());
        var topologies = loaded.stream()
                               .flatMap(ls -> TopologyParser.parse(ls.slice(),
                                                                   ls.artifact().asString())
                                                            .stream())
                               .toList();

        log.debug("buildTopologyResponse: topologies={}", topologies.size());

        return topologies;
    }

    private TopologyResponse toTopologyResponse(TopologyGraph graph) {
        log.debug("buildTopologyResponse: graph nodes={}, edges={}",
                  graph.nodes().size(),
                  graph.edges().size());
        var nodes = graph.nodes()
                         .stream()
                         .map(n -> new TopologyNodeInfo(n.id(),
                                                        n.type().name(),
                                                        n.label(),
                                                        n.sliceArtifact()))
                         .toList();
        var edges = graph.edges()
                         .stream()
                         .map(e -> new TopologyEdgeInfo(e.from(),
                                                        e.to(),
                                                        e.style().name(),
                                                        e.topicConfig()))
                         .toList();

        return new TopologyResponse(nodes, edges);
    }

    private SlicesResponse buildNodeSlicesResponse() {
        var node = nodeSupplier.get();
        var slices = node.sliceStore().loaded().stream().map(slice -> slice.artifact()
                                                                           .asString()).toList();

        return new SlicesResponse(slices);
    }

    private ClusterSlicesResponse buildClusterSlicesResponse(Option<String> stateFilter) {
        var node = nodeSupplier.get();
        var targets = collectSliceTargets(node);
        var normalizedFilter = stateFilter.map(RouteFilters::parseStateFilter);
        var slices = node.deploymentMap()
                         .allDeployments()
                         .stream()
                         .map(info -> toClusterSliceInfo(info, targets, normalizedFilter))
                         .filter(slice -> slice.instances()
                                               .size() > 0 || normalizedFilter.isEmpty())
                         .toList();

        return new ClusterSlicesResponse(slices);
    }

    private Map<String, SliceTargetValue> collectSliceTargets(ManageableNode node) {
        var targets = new HashMap<String, SliceTargetValue>();

        node.kvStore()
            .forEach(SliceTargetKey.class,
                     SliceTargetValue.class,
                     (key, value) -> targets.put(key.artifactBase().asString(),
                                                 value));

        return targets;
    }

    private static ClusterSliceInfo toClusterSliceInfo(DeploymentMap.SliceDeploymentInfo info,
                                                       Map<String, SliceTargetValue> targets,
                                                       Option<Set<String>> normalizedFilter) {
        var artifactStr = info.artifact();
        var artifactBase = artifactStr.contains(":")
                           ? artifactStr.substring(0, artifactStr.lastIndexOf(':'))
                           : artifactStr;
        var target = Option.option(targets.get(artifactBase));
        var instances = info.instances()
                            .stream()
                            .filter(i -> normalizedFilter.map(set -> set.contains(i.state().name()))
                                                         .or(true))
                            .map(i -> new ClusterSliceInstance(i.nodeId(),
                                                               i.state().name(),
                                                               ""))
                            .toList();

        return new ClusterSliceInfo(artifactStr,
                                    target.map(SliceTargetValue::targetInstances).or(instances.size()),
                                    target.map(SliceTargetValue::effectiveMinInstances).or(1),
                                    target.map(t -> t.currentVersion()
                                                     .withQualifier()).or(""),
                                    instances);
    }

    /// Cluster-wide HTTP route table, shared by three declared routes.
    ///
    /// `HttpRouteRegistry` is already cluster-wide — each `RouteInfo` carries the node ids serving
    /// that path — so `/api/routes` (ROUTES_LIST) and `/api/nodes/routes` (NODE_ROUTES) answer from
    /// the same assembly; there is nothing per-node left to aggregate.
    ///
    /// #525: ROUTES_LIST was declared and consumed by both `aether routes` and the dashboard's
    /// routes panel (`stores/deployments.js` `refreshRoutes`), but was never registered — so the
    /// live panel fetched an endpoint that answered 404.
    private RoutesResponse buildRoutesResponse() {
        var node = nodeSupplier.get();
        var routes = node.httpRouteRegistry().allRoutes().stream().map(this::toRouteInfo).toList();

        return new RoutesResponse(routes);
    }

    private RouteInfo toRouteInfo(org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo route) {
        List<String> nodeIds = route.nodes().stream().map(NodeId::id).toList();

        return new RouteInfo(route.httpMethod(), route.pathPrefix(), nodeIds, route.security());
    }

    private SlicesStatusResponse buildSlicesStatusResponse() {
        var node = nodeSupplier.get();
        var slices = node.deploymentMap().allDeployments().stream().map(this::toSliceStatusFromDeployment).toList();

        return new SlicesStatusResponse(slices);
    }

    private SliceStatus toSliceStatusFromDeployment(DeploymentMap.SliceDeploymentInfo info) {
        var instanceInfos = info.instances().stream().map(this::toSliceInstanceInfoFromDeployment).toList();

        return new SliceStatus(info.artifact(),
                               info.aggregateState().name(),
                               instanceInfos);
    }

    private SliceInstanceInfo toSliceInstanceInfoFromDeployment(DeploymentMap.SliceInstanceInfo inst) {
        var health = inst.state() == SliceState.ACTIVE
                     ? "HEALTHY"
                     : "UNHEALTHY";

        return new SliceInstanceInfo(inst.nodeId(),
                                     inst.state().name(),
                                     health);
    }
}
