package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.aether.slice.topology.TopologyGraph;
import org.pragmatica.aether.slice.topology.TopologyParser;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.pragmatica.aether.api.ManagementApiResponses.*;

/// Routes for slice management: scale, blueprint, status.
public final class SliceRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(SliceRoutes.class);
    private static final Cause MISSING_ARTIFACT_OR_INSTANCES = Causes.cause("Missing 'artifact' or 'instances' field");
    private static final Cause BLUEPRINT_NOT_FOUND = Causes.cause("Blueprint not found");
    private static final Cause NOT_IN_BLUEPRINT = Causes.cause("Slice is not part of any active blueprint. Deploy via blueprint.");

    private final Supplier<AetherNode> nodeSupplier;

    private SliceRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SliceRoutes sliceRoutes(Supplier<AetherNode> nodeSupplier) {
        return new SliceRoutes(nodeSupplier);
    }

    // Request DTOs - nullable types required for JSON deserialization
    record ScaleRequest(String artifact, Integer instances, String placement) {}

    record BlueprintDeployRequest(String artifact) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<SlicesResponse> get("/api/slices")
                              .toJson(this::buildSlicesResponse),
                         Route.<SlicesStatusResponse> get("/api/slices/status")
                              .toJson(this::buildSlicesStatusResponse),
                         Route.<RoutesResponse> get("/api/routes")
                              .toJson(this::buildRoutesResponse),
                         Route.<ScaleResponse> post("/api/scale")
                              .withBody(ScaleRequest.class)
                              .toJson(this::handleScale),
                         Route.<BlueprintResponse> post("/api/blueprint")
                              .to(ctx -> handleBlueprint(ctx.bodyAsString()))
                              .asJson(),
                         // Blueprint management routes
        Route.<BlueprintListResponse> get("/api/blueprints")
             .toJson(this::buildBlueprintListResponse),
                         Route.<BlueprintDetailResponse> get("/api/blueprint")
                              .withPath(aString())
                              .to(this::handleGetBlueprint)
                              .asJson(),
                         Route.<BlueprintStatusResponse> get("/api/blueprint")
                              .withPath(aString(),
                                        spacer("status"))
                              .to((id, _) -> handleGetBlueprintStatus(id))
                              .asJson(),
                         Route.<BlueprintDeleteResponse> delete("/api/blueprint")
                              .withPath(aString())
                              .to(this::handleDeleteBlueprint)
                              .asJson(),
                         Route.<BlueprintResponse> post("/api/blueprint/deploy")
                              .withBody(BlueprintDeployRequest.class)
                              .toJson(this::handleBlueprintDeploy),
                         Route.<BlueprintValidationResponse> post("/api/blueprint/validate")
                              .to(ctx -> handleValidateBlueprint(ctx.bodyAsString()))
                              .asJson(),
                         Route.<TopologyResponse> get("/api/topology")
                              .toJson(this::buildTopologyResponse));
    }

    private record ScaleParams(String artifact, int instances, Option<String> placement) {}

    private Result<ScaleParams> validateScaleRequest(ScaleRequest request) {
        return Result.all(Option.option(request.artifact())
                                .toResult(MISSING_ARTIFACT_OR_INSTANCES),
                          Option.option(request.instances())
                                .toResult(MISSING_ARTIFACT_OR_INSTANCES))
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
                                                                                      vs.params()
                                                                                        .instances()))
                                       .map(_ -> vs);
    }

    private Promise<ScaleResponse> executeScale(ValidatedScale vs) {
        return applyDeployCommand(vs.artifact(),
                                  vs.params()
                                    .instances(),
                                  vs.params()
                                    .placement())
        .map(_ -> new ScaleResponse("scaled",
                                    vs.artifact()
                                      .asString(),
                                    vs.params()
                                      .instances()));
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
                                              + " instances but blueprint minimum is " + min)
                                       .<Unit> promise())
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
                           .map(expanded -> new BlueprintResponse("applied",
                                                                  expanded.id()
                                                                          .asString(),
                                                                  expanded.loadOrder()
                                                                          .size()))
                           .onSuccess(r -> auditAndEmitBlueprintDeployed(r.blueprint(),
                                                                         r.slices()))
                           .onFailure(cause -> log.warn("Blueprint publish failed: {}",
                                                        cause.message()));
    }

    private static final Cause MISSING_ARTIFACT_COORDS = Causes.cause("Missing 'artifact' field");

    private Promise<BlueprintResponse> handleBlueprintDeploy(BlueprintDeployRequest request) {
        return Option.option(request.artifact())
                     .toResult(MISSING_ARTIFACT_COORDS)
                     .async()
                     .flatMap(coords -> nodeSupplier.get()
                                                    .blueprintService()
                                                    .publishFromArtifact(coords))
                     .map(expanded -> new BlueprintResponse("deployed",
                                                            expanded.id()
                                                                    .asString(),
                                                            expanded.loadOrder()
                                                                    .size()))
                     .onSuccess(r -> auditAndEmitBlueprintDeployed(r.blueprint(),
                                                                   r.slices()))
                     .onFailure(cause -> log.warn("Blueprint artifact deploy failed: {}",
                                                  cause.message()));
    }

    private BlueprintListResponse buildBlueprintListResponse() {
        var blueprints = nodeSupplier.get()
                                     .blueprintService()
                                     .list()
                                     .stream()
                                     .map(this::toBlueprintSummary)
                                     .toList();
        return new BlueprintListResponse(blueprints);
    }

    private BlueprintSummary toBlueprintSummary(ExpandedBlueprint blueprint) {
        return new BlueprintSummary(blueprint.id()
                                             .asString(),
                                    blueprint.loadOrder()
                                             .size());
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
        var slices = blueprint.loadOrder()
                              .stream()
                              .map(this::toBlueprintSliceInfo)
                              .toList();
        var dependencies = blueprint.loadOrder()
                                    .stream()
                                    .filter(ResolvedSlice::isDependency)
                                    .map(s -> s.artifact()
                                               .asString())
                                    .toList();
        return new BlueprintDetailResponse(blueprint.id()
                                                    .asString(),
                                           slices,
                                           dependencies);
    }

    private BlueprintSliceInfo toBlueprintSliceInfo(ResolvedSlice slice) {
        var deps = slice.dependencies()
                        .stream()
                        .map(Artifact::asString)
                        .toList();
        return new BlueprintSliceInfo(slice.artifact()
                                           .asString(),
                                      slice.instances(),
                                      slice.isDependency(),
                                      deps);
    }

    private Promise<BlueprintStatusResponse> handleGetBlueprintStatus(String id) {
        return BlueprintId.blueprintId(id)
                          .async()
                          .flatMap(blueprintId -> nodeSupplier.get()
                                                              .blueprintService()
                                                              .get(blueprintId)
                                                              .async(BLUEPRINT_NOT_FOUND))
                          .map(this::toBlueprintStatusResponse);
    }

    private BlueprintStatusResponse toBlueprintStatusResponse(ExpandedBlueprint blueprint) {
        var node = nodeSupplier.get();
        var sliceStatuses = blueprint.loadOrder()
                                     .stream()
                                     .map(slice -> computeSliceStatus(node, slice))
                                     .toList();
        var overallStatus = computeOverallStatus(sliceStatuses);
        return new BlueprintStatusResponse(blueprint.id()
                                                    .asString(),
                                           overallStatus,
                                           sliceStatuses);
    }

    private BlueprintSliceStatus computeSliceStatus(AetherNode node, ResolvedSlice slice) {
        var artifact = slice.artifact();
        var targetInstances = slice.instances();
        var activeInstances = countActiveInstances(node, artifact);
        var status = determineSliceDeploymentStatus(targetInstances, activeInstances);
        return new BlueprintSliceStatus(artifact.asString(), targetInstances, activeInstances, status);
    }

    private int countActiveInstances(AetherNode node, Artifact artifact) {
        return (int) node.deploymentMap()
                        .byArtifact(artifact)
                        .values()
                        .stream()
                        .filter(state -> state == SliceState.ACTIVE)
                        .count();
    }

    private String determineSliceDeploymentStatus(int target, int active) {
        if (active == 0) {
            return "PENDING";
        } else if (active < target) {
            return "DEPLOYING";
        } else if (active == target) {
            return "DEPLOYED";
        } else {
            return "SCALING_DOWN";
        }
    }

    private String computeOverallStatus(List<BlueprintSliceStatus> sliceStatuses) {
        var hasPending = sliceStatuses.stream()
                                      .anyMatch(s -> "PENDING".equals(s.status()));
        var hasDeploying = sliceStatuses.stream()
                                        .anyMatch(s -> "DEPLOYING".equals(s.status()));
        var hasScalingDown = sliceStatuses.stream()
                                          .anyMatch(s -> "SCALING_DOWN".equals(s.status()));
        var allDeployed = sliceStatuses.stream()
                                       .allMatch(s -> "DEPLOYED".equals(s.status()));
        if (allDeployed) {
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
        nodeSupplier.get()
                    .route(OperationalEvent.BlueprintDeployed.blueprintDeployed(blueprintId, "api"));
    }

    private void auditAndEmitBlueprintDeleted(String blueprintId) {
        AuditLog.blueprintDeleted(blueprintId);
        nodeSupplier.get()
                    .route(OperationalEvent.BlueprintDeleted.blueprintDeleted(blueprintId, "api"));
    }

    private Promise<BlueprintValidationResponse> handleValidateBlueprint(String body) {
        return Promise.success(nodeSupplier.get()
                                           .blueprintService()
                                           .validate(body)
                                           .fold(SliceRoutes::failedValidationResponse,
                                                 SliceRoutes::successValidationResponse));
    }

    private static BlueprintValidationResponse failedValidationResponse(Cause cause) {
        return new BlueprintValidationResponse(false,
                                               "",
                                               0,
                                               List.of(cause.message()));
    }

    private static BlueprintValidationResponse successValidationResponse(Blueprint blueprint) {
        return new BlueprintValidationResponse(true,
                                               blueprint.id()
                                                        .asString(),
                                               blueprint.slices()
                                                        .size(),
                                               List.of());
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

    private TopologyResponse buildTopologyResponse() {
        var topologies = collectSliceTopologies();
        var graph = TopologyGraph.build(topologies);
        return toTopologyResponse(graph);
    }

    private List<SliceTopology> collectSliceTopologies() {
        var node = nodeSupplier.get();
        var loaded = node.sliceStore()
                         .loaded();
        log.debug("buildTopologyResponse: loaded slices={}", loaded.size());
        var topologies = loaded.stream()
                               .flatMap(ls -> TopologyParser.parse(ls.slice(),
                                                                   ls.artifact()
                                                                     .asString())
                                                            .stream())
                               .toList();
        log.debug("buildTopologyResponse: topologies={}", topologies.size());
        return topologies;
    }

    private TopologyResponse toTopologyResponse(TopologyGraph graph) {
        log.debug("buildTopologyResponse: graph nodes={}, edges={}",
                  graph.nodes()
                       .size(),
                  graph.edges()
                       .size());
        var nodes = graph.nodes()
                         .stream()
                         .map(n -> new TopologyNodeInfo(n.id(),
                                                        n.type()
                                                         .name(),
                                                        n.label(),
                                                        n.sliceArtifact()))
                         .toList();
        var edges = graph.edges()
                         .stream()
                         .map(e -> new TopologyEdgeInfo(e.from(),
                                                        e.to(),
                                                        e.style()
                                                         .name(),
                                                        e.topicConfig()))
                         .toList();
        return new TopologyResponse(nodes, edges);
    }

    private SlicesResponse buildSlicesResponse() {
        var node = nodeSupplier.get();
        var slices = node.sliceStore()
                         .loaded()
                         .stream()
                         .map(slice -> slice.artifact()
                                            .asString())
                         .toList();
        return new SlicesResponse(slices);
    }

    private RoutesResponse buildRoutesResponse() {
        var node = nodeSupplier.get();
        var routes = node.httpRouteRegistry()
                         .allRoutes()
                         .stream()
                         .map(this::toRouteInfo)
                         .toList();
        return new RoutesResponse(routes);
    }

    private RouteInfo toRouteInfo(org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo route) {
        List<String> nodeIds = route.nodes()
                                    .stream()
                                    .map(NodeId::id)
                                    .toList();
        return new RouteInfo(route.httpMethod(), route.pathPrefix(), nodeIds);
    }

    private SlicesStatusResponse buildSlicesStatusResponse() {
        var node = nodeSupplier.get();
        var slices = node.deploymentMap()
                         .allDeployments()
                         .stream()
                         .map(this::toSliceStatusFromDeployment)
                         .toList();
        return new SlicesStatusResponse(slices);
    }

    private SliceStatus toSliceStatusFromDeployment(DeploymentMap.SliceDeploymentInfo info) {
        var instanceInfos = info.instances()
                                .stream()
                                .map(this::toSliceInstanceInfoFromDeployment)
                                .toList();
        return new SliceStatus(info.artifact(),
                               info.aggregateState()
                                   .name(),
                               instanceInfos);
    }

    private SliceInstanceInfo toSliceInstanceInfoFromDeployment(DeploymentMap.SliceInstanceInfo inst) {
        var health = inst.state() == SliceState.ACTIVE
                     ? "HEALTHY"
                     : "UNHEALTHY";
        return new SliceInstanceInfo(inst.nodeId(),
                                     inst.state()
                                         .name(),
                                     health);
    }
}
