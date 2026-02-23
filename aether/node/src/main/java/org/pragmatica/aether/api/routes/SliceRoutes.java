package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.pragmatica.aether.api.ManagementApiResponses.*;

/// Routes for slice management: scale, blueprint, status.
public final class SliceRoutes implements RouteSource {
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
    record ScaleRequest(String artifact, Integer instances) {}

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
                         Route.<BlueprintValidationResponse> post("/api/blueprint/validate")
                              .to(ctx -> handleValidateBlueprint(ctx.bodyAsString()))
                              .asJson());
    }

    private record ScaleParams(String artifact, int instances) {}

    private Result<ScaleParams> validateScaleRequest(ScaleRequest request) {
        return Result.all(Option.option(request.artifact())
                                .toResult(MISSING_ARTIFACT_OR_INSTANCES),
                          Option.option(request.instances())
                                .toResult(MISSING_ARTIFACT_OR_INSTANCES))
                     .map(ScaleParams::new);
    }

    private Promise<ScaleResponse> handleScale(ScaleRequest request) {
        return validateScaleRequest(request).async()
                                   .flatMap(params -> Artifact.artifact(params.artifact())
                                                              .async()
                                                              .flatMap(artifact -> guardBlueprintMembership(artifact).flatMap(_ -> guardMinInstances(artifact,
                                                                                                                                                     params.instances()))
                                                                                                           .flatMap(_ -> applyDeployCommand(artifact,
                                                                                                                                            params.instances()))
                                                                                                           .map(_ -> new ScaleResponse("scaled",
                                                                                                                                       artifact.asString(),
                                                                                                                                       params.instances()))));
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
                                                                          .size()));
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
        var count = new AtomicInteger(0);
        node.kvStore()
            .forEach(SliceNodeKey.class,
                     SliceNodeValue.class,
                     (key, value) -> countIfActive(count, key, value, artifact));
        return count.get();
    }

    private void countIfActive(AtomicInteger count, SliceNodeKey key, SliceNodeValue value, Artifact artifact) {
        if (key.artifact()
               .equals(artifact) && value.state() == SliceState.ACTIVE) {
            count.incrementAndGet();
        }
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
                                                                                                    blueprintId.asString())));
    }

    private Promise<BlueprintValidationResponse> handleValidateBlueprint(String body) {
        return Promise.success(nodeSupplier.get()
                                           .blueprintService()
                                           .validate(body)
                                           .fold(cause -> new BlueprintValidationResponse(false,
                                                                                          "",
                                                                                          0,
                                                                                          List.of(cause.message())),
                                                 blueprint -> new BlueprintValidationResponse(true,
                                                                                              blueprint.id()
                                                                                                       .asString(),
                                                                                              blueprint.slices()
                                                                                                       .size(),
                                                                                              List.of())));
    }

    private Promise<List<Long>> applyDeployCommand(Artifact artifact, int instances) {
        var node = nodeSupplier.get();
        AetherKey key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        AetherValue value = AetherValue.SliceTargetValue.sliceTargetValue(artifact.version(), instances);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        return node.apply(List.of(command));
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
        Map<Artifact, List<SliceInstance>> slicesByArtifact = new HashMap<>();
        node.kvStore()
            .forEach(SliceNodeKey.class,
                     SliceNodeValue.class,
                     (key, value) -> collectSliceInstance(slicesByArtifact, key, value));
        var slices = slicesByArtifact.entrySet()
                                     .stream()
                                     .map(this::toSliceStatus)
                                     .toList();
        return new SlicesStatusResponse(slices);
    }

    private SliceStatus toSliceStatus(Map.Entry<Artifact, List<SliceInstance>> entry) {
        var artifact = entry.getKey();
        var instances = entry.getValue();
        var aggregateState = computeAggregateState(instances);
        var instanceInfos = instances.stream()
                                     .map(this::toSliceInstanceInfo)
                                     .toList();
        return new SliceStatus(artifact.asString(), aggregateState.name(), instanceInfos);
    }

    private SliceState computeAggregateState(List<SliceInstance> instances) {
        return Option.from(instances.stream()
                                    .map(SliceInstance::state)
                                    .filter(s -> s == SliceState.ACTIVE)
                                    .findAny())
                     .or(instances.isEmpty()
                         ? SliceState.FAILED
                         : instances.getFirst()
                                    .state());
    }

    private SliceInstanceInfo toSliceInstanceInfo(SliceInstance instance) {
        var health = instance.state() == SliceState.ACTIVE
                     ? "HEALTHY"
                     : "UNHEALTHY";
        return new SliceInstanceInfo(instance.nodeId()
                                             .id(),
                                     instance.state()
                                             .name(),
                                     health);
    }

    private void collectSliceInstance(Map<Artifact, List<SliceInstance>> map,
                                      SliceNodeKey sliceKey,
                                      SliceNodeValue sliceValue) {
        map.computeIfAbsent(sliceKey.artifact(),
                            _ -> new ArrayList<>())
           .add(new SliceInstance(sliceKey.nodeId(),
                                  sliceValue.state()));
    }

    private record SliceInstance(NodeId nodeId, SliceState state) {}
}
