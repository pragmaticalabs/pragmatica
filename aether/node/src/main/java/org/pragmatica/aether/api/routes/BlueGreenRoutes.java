package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.update.BlueGreenDeployment;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.BlueGreenInfo;
import static org.pragmatica.aether.api.ManagementApiResponses.BlueGreenListResponse;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// Routes for blue-green deployment management: deploy, switch, switch-back, complete.
public final class BlueGreenRoutes implements RouteSource {
    private static final Cause MISSING_ARTIFACT_BASE_OR_VERSION = Causes.cause("Missing artifactBase or version");
    private static final Cause NOT_LEADER = Causes.cause("This operation requires the leader node");
    private static final Cause DEPLOYMENT_NOT_FOUND = Causes.cause("Blue-green deployment not found");

    private final Supplier<AetherNode> nodeSupplier;

    private BlueGreenRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static BlueGreenRoutes blueGreenRoutes(Supplier<AetherNode> nodeSupplier) {
        return new BlueGreenRoutes(nodeSupplier);
    }

    // Request DTOs
    record BlueGreenDeployRequest(String artifactBase,
                                  String version,
                                  Integer instances,
                                  Double maxErrorRate,
                                  Long maxLatencyMs,
                                  Long drainTimeoutMs,
                                  String cleanupPolicy) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(// GET /api/blue-green-deployments - List all
        Route.<BlueGreenListResponse> get("/api/blue-green-deployments")
             .toJson(this::buildBlueGreenListResponse),
        // GET /api/blue-green/{deploymentId} - Single deployment by ID
        Route.<BlueGreenInfo> get("/api/blue-green")
             .withPath(aString())
             .to(this::buildBlueGreenResponse)
             .asJson(),
        // POST /api/blue-green/deploy - Deploy green version
        Route.<BlueGreenInfo> post("/api/blue-green/deploy")
             .withBody(BlueGreenDeployRequest.class)
             .toJson(this::handleBlueGreenDeploy),
        // POST /api/blue-green/{deploymentId}/switch - Switch to green
        Route.<BlueGreenInfo> post("/api/blue-green")
             .withPath(aString(),
                       spacer("switch"))
             .to((deploymentId, _) -> handleBlueGreenSwitch(deploymentId))
             .asJson(),
        // POST /api/blue-green/{deploymentId}/switch-back - Switch back to blue
        Route.<BlueGreenInfo> post("/api/blue-green")
             .withPath(aString(),
                       spacer("switch-back"))
             .to((deploymentId, _) -> handleBlueGreenSwitchBack(deploymentId))
             .asJson(),
        // POST /api/blue-green/{deploymentId}/complete - Complete deployment
        Route.<BlueGreenInfo> post("/api/blue-green")
             .withPath(aString(),
                       spacer("complete"))
             .to((deploymentId, _) -> handleBlueGreenComplete(deploymentId))
             .asJson());
    }

    private Promise<BlueGreenInfo> handleBlueGreenDeploy(BlueGreenDeployRequest request) {
        return requireLeader().flatMap(_ -> parseDeployRequest(request))
                            .flatMap(parsed -> nodeSupplier.get()
                                                           .blueGreenDeploymentManager()
                                                           .deployGreen(parsed.artifactBase(),
                                                                        parsed.version(),
                                                                        parsed.instances(),
                                                                        parsed.thresholds(),
                                                                        parsed.drainTimeoutMs(),
                                                                        parsed.cleanupPolicy()))
                            .map(this::toBlueGreenInfo)
                            .onSuccess(info -> AuditLog.deploymentStart("blue-green",
                                                                        info.deploymentId(),
                                                                        info.artifactBase(),
                                                                        info.blueVersion(),
                                                                        info.greenVersion()));
    }

    private Promise<BlueGreenInfo> handleBlueGreenSwitch(String deploymentId) {
        return requireLeader().flatMap(_ -> nodeSupplier.get()
                                                        .blueGreenDeploymentManager()
                                                        .switchToGreen(deploymentId))
                            .map(this::toBlueGreenInfo)
                            .onSuccess(info -> AuditLog.deploymentPromote("blue-green",
                                                                          info.deploymentId(),
                                                                          info.artifactBase(),
                                                                          info.routing()));
    }

    private Promise<BlueGreenInfo> handleBlueGreenSwitchBack(String deploymentId) {
        return requireLeader().flatMap(_ -> nodeSupplier.get()
                                                        .blueGreenDeploymentManager()
                                                        .switchBack(deploymentId))
                            .map(this::toBlueGreenInfo)
                            .onSuccess(info -> AuditLog.deploymentRollback("blue-green",
                                                                           info.deploymentId(),
                                                                           info.artifactBase(),
                                                                           "manual"));
    }

    private Promise<BlueGreenInfo> handleBlueGreenComplete(String deploymentId) {
        return requireLeader().flatMap(_ -> nodeSupplier.get()
                                                        .blueGreenDeploymentManager()
                                                        .completeDeployment(deploymentId))
                            .map(this::toBlueGreenInfo)
                            .onSuccess(info -> AuditLog.deploymentComplete("blue-green",
                                                                           info.deploymentId(),
                                                                           info.artifactBase()));
    }

    private Promise<org.pragmatica.lang.Unit> requireLeader() {
        var node = nodeSupplier.get();
        if (!node.isLeader()) {
            var leaderInfo = node.leader()
                                 .map(id -> " Current leader: " + id.id())
                                 .or("");
            return Causes.cause(NOT_LEADER.message() + leaderInfo)
                         .promise();
        }
        return Promise.unitPromise();
    }

    private record ParsedBlueGreenRequest(ArtifactBase artifactBase,
                                          Version version,
                                          int instances,
                                          HealthThresholds thresholds,
                                          long drainTimeoutMs,
                                          CleanupPolicy cleanupPolicy) {}

    private Promise<ParsedBlueGreenRequest> parseDeployRequest(BlueGreenDeployRequest request) {
        if (request.artifactBase() == null || request.version() == null) {
            return MISSING_ARTIFACT_BASE_OR_VERSION.promise();
        }
        var instances = Option.option(request.instances())
                              .or(1);
        var maxErrorRate = Option.option(request.maxErrorRate())
                                 .or(0.01);
        var maxLatencyMs = Option.option(request.maxLatencyMs())
                                 .or(500L);
        var drainTimeoutMs = Option.option(request.drainTimeoutMs())
                                   .or(30_000L);
        var cleanupPolicy = request.cleanupPolicy() != null
                            ? CleanupPolicy.valueOf(request.cleanupPolicy())
                            : CleanupPolicy.GRACE_PERIOD;
        return Result.all(ArtifactBase.artifactBase(request.artifactBase()),
                          Version.version(request.version()),
                          HealthThresholds.healthThresholds(maxErrorRate, maxLatencyMs, false))
                     .map((artifactBase, version, thresholds) -> new ParsedBlueGreenRequest(artifactBase,
                                                                                            version,
                                                                                            instances,
                                                                                            thresholds,
                                                                                            drainTimeoutMs,
                                                                                            cleanupPolicy))
                     .async();
    }

    private BlueGreenListResponse buildBlueGreenListResponse() {
        var deployments = nodeSupplier.get()
                                      .blueGreenDeploymentManager()
                                      .allDeployments()
                                      .stream()
                                      .map(this::toBlueGreenInfo)
                                      .toList();
        return new BlueGreenListResponse(deployments);
    }

    private Promise<BlueGreenInfo> buildBlueGreenResponse(String deploymentId) {
        return nodeSupplier.get()
                           .blueGreenDeploymentManager()
                           .getDeployment(deploymentId)
                           .map(this::toBlueGreenInfo)
                           .async(DEPLOYMENT_NOT_FOUND);
    }

    private BlueGreenInfo toBlueGreenInfo(BlueGreenDeployment deployment) {
        return new BlueGreenInfo(deployment.deploymentId(),
                                 deployment.artifactBase()
                                           .asString(),
                                 deployment.blueVersion()
                                           .toString(),
                                 deployment.greenVersion()
                                           .toString(),
                                 deployment.state()
                                           .name(),
                                 deployment.activeEnvironment()
                                           .name(),
                                 deployment.routing()
                                           .toString(),
                                 deployment.blueInstances(),
                                 deployment.greenInstances(),
                                 deployment.createdAt(),
                                 deployment.updatedAt());
    }
}
