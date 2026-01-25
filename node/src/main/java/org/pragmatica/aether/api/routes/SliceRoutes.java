package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Unit.unit;

/**
 * Routes for slice management: deploy, undeploy, scale, blueprint.
 */
public final class SliceRoutes implements RouteHandler {
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile("\"artifact\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INSTANCES_PATTERN = Pattern.compile("\"instances\"\\s*:\\s*(\\d+)");

    private final Supplier<AetherNode> nodeSupplier;

    private SliceRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SliceRoutes sliceRoutes(Supplier<AetherNode> nodeSupplier) {
        return new SliceRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        // GET endpoints
        if (method == HttpMethod.GET) {
            return switch (path) {
                case "/api/slices" -> {
                    response.ok(buildSlicesResponse());
                    yield true;
                }
                case "/api/slices/status" -> {
                    response.ok(buildSlicesStatusResponse());
                    yield true;
                }
                case "/api/routes" -> {
                    response.ok(buildRoutesResponse());
                    yield true;
                }
                default -> false;
            };
        }
        // POST endpoints
        if (method == HttpMethod.POST) {
            var body = ctx.bodyAsString();
            return switch (path) {
                case "/api/deploy" -> {
                    handleDeploy(response, body);
                    yield true;
                }
                case "/api/scale" -> {
                    handleScale(response, body);
                    yield true;
                }
                case "/api/undeploy" -> {
                    handleUndeploy(response, body);
                    yield true;
                }
                case "/api/blueprint" -> {
                    handleBlueprint(response, body);
                    yield true;
                }
                default -> false;
            };
        }
        return false;
    }

    private void handleDeploy(ResponseWriter response, String body) {
        var artifactMatch = ARTIFACT_PATTERN.matcher(body);
        var instancesMatch = INSTANCES_PATTERN.matcher(body);
        if (!artifactMatch.find()) {
            response.badRequest("Missing 'artifact' field");
            return;
        }
        int instances = instancesMatch.find()
                        ? Integer.parseInt(instancesMatch.group(1))
                        : 1;
        var artifactStr = artifactMatch.group(1);
        Artifact.artifact(artifactStr)
                .async()
                .flatMap(artifact -> applyDeployCommand(artifact, instances))
                .onSuccess(_ -> response.ok("{\"status\":\"deployed\",\"artifact\":\"" + artifactStr
                                            + "\",\"instances\":" + instances + "}"))
                .onFailure(cause -> response.error(isBadRequest(cause)
                                                   ? HttpStatus.BAD_REQUEST
                                                   : HttpStatus.INTERNAL_SERVER_ERROR,
                                                   cause.message()));
    }

    private void handleScale(ResponseWriter response, String body) {
        var artifactMatch = ARTIFACT_PATTERN.matcher(body);
        var instancesMatch = INSTANCES_PATTERN.matcher(body);
        if (!artifactMatch.find() || !instancesMatch.find()) {
            response.badRequest("Missing 'artifact' or 'instances' field");
            return;
        }
        var artifactStr = artifactMatch.group(1);
        var instances = Integer.parseInt(instancesMatch.group(1));
        Artifact.artifact(artifactStr)
                .async()
                .flatMap(artifact -> applyDeployCommand(artifact, instances))
                .onSuccess(_ -> response.ok("{\"status\":\"scaled\",\"artifact\":\"" + artifactStr
                                            + "\",\"instances\":" + instances + "}"))
                .onFailure(cause -> response.error(isBadRequest(cause)
                                                   ? HttpStatus.BAD_REQUEST
                                                   : HttpStatus.INTERNAL_SERVER_ERROR,
                                                   cause.message()));
    }

    private void handleUndeploy(ResponseWriter response, String body) {
        var artifactMatch = ARTIFACT_PATTERN.matcher(body);
        if (!artifactMatch.find()) {
            response.badRequest("Missing 'artifact' field");
            return;
        }
        var artifactStr = artifactMatch.group(1);
        Artifact.artifact(artifactStr)
                .async()
                .flatMap(this::applyUndeployCommand)
                .onSuccess(_ -> response.ok("{\"status\":\"undeployed\",\"artifact\":\"" + artifactStr + "\"}"))
                .onFailure(cause -> response.error(isBadRequest(cause)
                                                   ? HttpStatus.BAD_REQUEST
                                                   : HttpStatus.INTERNAL_SERVER_ERROR,
                                                   cause.message()));
    }

    private void handleBlueprint(ResponseWriter response, String body) {
        BlueprintParser.parse(body)
                       .async()
                       .flatMap(blueprint -> applyBlueprintCommands(response, blueprint))
                       .onFailure(cause -> response.error(isBadRequest(cause)
                                                          ? HttpStatus.BAD_REQUEST
                                                          : HttpStatus.INTERNAL_SERVER_ERROR,
                                                          cause.message()));
    }

    private Promise<Unit> applyDeployCommand(Artifact artifact, int instances) {
        var node = nodeSupplier.get();
        AetherKey key = new AetherKey.BlueprintKey(artifact);
        AetherValue value = new AetherValue.BlueprintValue(instances);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        return node.apply(List.of(command))
                   .mapToUnit();
    }

    private Promise<Unit> applyUndeployCommand(Artifact artifact) {
        var node = nodeSupplier.get();
        AetherKey key = new AetherKey.BlueprintKey(artifact);
        KVCommand<AetherKey> command = new KVCommand.Remove<>(key);
        return node.apply(List.of(command))
                   .mapToUnit();
    }

    private Promise<Unit> applyBlueprintCommands(ResponseWriter response,
                                                 org.pragmatica.aether.slice.blueprint.Blueprint blueprint) {
        var node = nodeSupplier.get();
        var commands = blueprint.slices()
                                .stream()
                                .map(spec -> {
                                         AetherKey key = new AetherKey.BlueprintKey(spec.artifact());
                                         AetherValue value = new AetherValue.BlueprintValue(spec.instances());
                                         return (KVCommand<AetherKey>) new KVCommand.Put<>(key, value);
                                     })
                                .toList();
        if (commands.isEmpty()) {
            response.ok("{\"status\":\"applied\",\"blueprint\":\"" + blueprint.id()
                                                                             .asString() + "\",\"slices\":0}");
            return Promise.success(unit());
        }
        return node.apply(commands)
                   .mapToUnit()
                   .onSuccess(_ -> response.ok("{\"status\":\"applied\",\"blueprint\":\"" + blueprint.id()
                                                                                                     .asString()
                                               + "\",\"slices\":" + commands.size() + "}"));
    }

    private String buildSlicesResponse() {
        var node = nodeSupplier.get();
        var slices = node.sliceStore()
                         .loaded();
        var sb = new StringBuilder();
        sb.append("{\"slices\":[");
        boolean first = true;
        for (var slice : slices) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(slice.artifact()
                           .asString())
              .append("\"");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildRoutesResponse() {
        var node = nodeSupplier.get();
        var routes = node.httpRouteRegistry()
                         .allRoutes();
        var sb = new StringBuilder();
        sb.append("{\"routes\":[");
        boolean first = true;
        for (var route : routes) {
            if (!first) sb.append(",");
            sb.append("{\"method\":\"")
              .append(route.httpMethod())
              .append("\",\"path\":\"")
              .append(route.pathPrefix())
              .append("\",\"artifact\":\"")
              .append(route.artifact())
              .append("\",\"sliceMethod\":\"")
              .append(route.sliceMethod())
              .append("\"}");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildSlicesStatusResponse() {
        var node = nodeSupplier.get();
        Map<Artifact, List<SliceInstance>> slicesByArtifact = new HashMap<>();
        node.kvStore()
            .snapshot()
            .forEach((key, value) -> collectSliceInstance(slicesByArtifact, key, value));
        var sb = new StringBuilder();
        sb.append("{\"slices\":[");
        boolean first = true;
        for (var entry : slicesByArtifact.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            var artifact = entry.getKey();
            var instances = entry.getValue();
            var aggregateState = instances.stream()
                                          .map(SliceInstance::state)
                                          .filter(s -> s == SliceState.ACTIVE)
                                          .findAny()
                                          .orElse(instances.isEmpty()
                                                  ? SliceState.FAILED
                                                  : instances.getFirst()
                                                             .state());
            sb.append("{\"artifact\":\"")
              .append(artifact.asString())
              .append("\",\"state\":\"")
              .append(aggregateState.name())
              .append("\",\"instances\":[");
            boolean firstInstance = true;
            for (var instance : instances) {
                if (!firstInstance) sb.append(",");
                firstInstance = false;
                var health = instance.state() == SliceState.ACTIVE
                             ? "HEALTHY"
                             : "UNHEALTHY";
                sb.append("{\"nodeId\":\"")
                  .append(instance.nodeId()
                                  .id())
                  .append("\",\"state\":\"")
                  .append(instance.state()
                                  .name())
                  .append("\",\"health\":\"")
                  .append(health)
                  .append("\"}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
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

    private static boolean isBadRequest(org.pragmatica.lang.Cause cause) {
        return cause.message()
                    .contains("Invalid") || cause.message()
                                                 .contains("Missing");
    }
}
