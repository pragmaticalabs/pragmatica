package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.*;

/**
 * Routes for slice management: deploy, undeploy, scale, blueprint.
 */
public final class SliceRoutes implements RouteSource {
    private static final Cause MISSING_ARTIFACT = Causes.cause("Missing 'artifact' field");
    private static final Cause MISSING_ARTIFACT_OR_INSTANCES = Causes.cause("Missing 'artifact' or 'instances' field");

    private final Supplier<AetherNode> nodeSupplier;

    private SliceRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SliceRoutes sliceRoutes(Supplier<AetherNode> nodeSupplier) {
        return new SliceRoutes(nodeSupplier);
    }

    // Request DTOs - nullable types required for JSON deserialization
    record DeployRequest(String artifact, Integer instances) {}

    record ScaleRequest(String artifact, Integer instances) {}

    record UndeployRequest(String artifact) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<SlicesResponse> get("/api/slices")
                              .toJson(this::buildSlicesResponse),
                         Route.<SlicesStatusResponse> get("/api/slices/status")
                              .toJson(this::buildSlicesStatusResponse),
                         Route.<RoutesResponse> get("/api/routes")
                              .toJson(this::buildRoutesResponse),
                         Route.<DeployResponse> post("/api/deploy")
                              .withBody(DeployRequest.class)
                              .toJson(this::handleDeploy),
                         Route.<DeployResponse> post("/api/scale")
                              .withBody(ScaleRequest.class)
                              .toJson(this::handleScale),
                         Route.<UndeployResponse> post("/api/undeploy")
                              .withBody(UndeployRequest.class)
                              .toJson(this::handleUndeploy),
                         Route.<BlueprintResponse> post("/api/blueprint")
                              .to(ctx -> handleBlueprint(ctx.bodyAsString()))
                              .asJson());
    }

    private record DeployParams(String artifact, int instances) {}

    private record ScaleParams(String artifact, int instances) {}

    private record UndeployParams(String artifact) {}

    private Result<DeployParams> validateDeployRequest(DeployRequest request) {
        return Option.option(request.artifact())
                     .toResult(MISSING_ARTIFACT)
                     .map(artifact -> new DeployParams(artifact,
                                                       Option.option(request.instances())
                                                             .or(1)));
    }

    private Result<ScaleParams> validateScaleRequest(ScaleRequest request) {
        return Result.all(Option.option(request.artifact())
                                .toResult(MISSING_ARTIFACT_OR_INSTANCES),
                          Option.option(request.instances())
                                .toResult(MISSING_ARTIFACT_OR_INSTANCES))
                     .map(ScaleParams::new);
    }

    private Result<UndeployParams> validateUndeployRequest(UndeployRequest request) {
        return Option.option(request.artifact())
                     .toResult(MISSING_ARTIFACT)
                     .map(UndeployParams::new);
    }

    private Promise<DeployResponse> handleDeploy(DeployRequest request) {
        return validateDeployRequest(request).async()
                                    .flatMap(params -> Artifact.artifact(params.artifact())
                                                               .async()
                                                               .flatMap(artifact -> applyDeployCommand(artifact,
                                                                                                       params.instances())
        .map(_ -> new DeployResponse("deployed",
                                     artifact.asString(),
                                     params.instances()))));
    }

    private Promise<DeployResponse> handleScale(ScaleRequest request) {
        return validateScaleRequest(request).async()
                                   .flatMap(params -> Artifact.artifact(params.artifact())
                                                              .async()
                                                              .flatMap(artifact -> applyDeployCommand(artifact,
                                                                                                      params.instances())
        .map(_ -> new DeployResponse("scaled",
                                     artifact.asString(),
                                     params.instances()))));
    }

    private Promise<UndeployResponse> handleUndeploy(UndeployRequest request) {
        return validateUndeployRequest(request).async()
                                      .flatMap(params -> Artifact.artifact(params.artifact())
                                                                 .async()
                                                                 .flatMap(artifact -> applyUndeployCommand(artifact)
        .map(_ -> new UndeployResponse("undeployed",
                                       artifact.asString()))));
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

    private Promise<List<Long>> applyDeployCommand(Artifact artifact, int instances) {
        var node = nodeSupplier.get();
        AetherKey key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        AetherValue value = AetherValue.SliceTargetValue.sliceTargetValue(artifact.version(), instances);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        return node.apply(List.of(command));
    }

    private Promise<List<Long>> applyUndeployCommand(Artifact artifact) {
        var node = nodeSupplier.get();
        AetherKey key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        KVCommand<AetherKey> command = new KVCommand.Remove<>(key);
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
            .snapshot()
            .forEach((key, value) -> collectSliceInstance(slicesByArtifact, key, value));
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

    private void collectSliceInstance(Map<Artifact, List<SliceInstance>> map, AetherKey key, AetherValue value) {
        if (key instanceof SliceNodeKey sliceKey && value instanceof SliceNodeValue sliceValue) {
            map.computeIfAbsent(sliceKey.artifact(),
                                _ -> new ArrayList<>())
               .add(new SliceInstance(sliceKey.nodeId(),
                                      sliceValue.state()));
        }
    }

    private record SliceInstance(NodeId nodeId, SliceState state) {}
}
