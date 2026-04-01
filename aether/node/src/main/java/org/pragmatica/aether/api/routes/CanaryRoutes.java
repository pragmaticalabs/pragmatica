package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.update.CanaryAnalysisConfig;
import org.pragmatica.aether.update.CanaryDeployment;
import org.pragmatica.aether.update.CanaryHealthComparison;
import org.pragmatica.aether.update.CanaryStage;
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

import static org.pragmatica.aether.api.ManagementApiResponses.CanaryHealthResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.CanaryInfo;
import static org.pragmatica.aether.api.ManagementApiResponses.CanaryListResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.CanaryVersionHealth;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// Routes for canary deployment management: start, promote, rollback, health.
public final class CanaryRoutes implements RouteSource {
    private static final Cause MISSING_ARTIFACT_BASE_OR_VERSION = Causes.cause("Missing artifactBase or version");
    private static final Cause NOT_LEADER = Causes.cause("This operation requires the leader node");
    private static final Cause CANARY_NOT_FOUND = Causes.cause("Canary deployment not found");

    private final Supplier<AetherNode> nodeSupplier;

    private CanaryRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static CanaryRoutes canaryRoutes(Supplier<AetherNode> nodeSupplier) {
        return new CanaryRoutes(nodeSupplier);
    }

    // Request DTOs
    record CanaryStartRequest(String artifactBase,
                              String version,
                              Integer instances,
                              Double maxErrorRate,
                              Long maxLatencyMs,
                              String cleanupPolicy){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(// GET /api/canaries - List all canaries
        Route.<CanaryListResponse>get("/api/canaries")
             .toJson(this::buildCanaryListResponse),
        // GET /api/canary/{canaryId}/health - Canary health comparison
        Route.<CanaryHealthResponse>get("/api/canary")
             .withPath(aString(),
                       spacer("health"))
             .to((canaryId, _) -> buildCanaryHealthResponse(canaryId))
             .asJson(),
        // GET /api/canary/{canaryId} - Single canary by ID
        Route.<CanaryInfo>get("/api/canary")
             .withPath(aString())
             .to(this::buildCanaryResponse)
             .asJson(),
        // POST /api/canary/start - Start canary
        Route.<CanaryInfo>post("/api/canary/start")
             .withBody(CanaryStartRequest.class)
             .toJson(this::handleCanaryStart),
        // POST /api/canary/{canaryId}/promote - Promote to next stage
        Route.<CanaryInfo>post("/api/canary")
             .withPath(aString(),
                       spacer("promote"))
             .to((canaryId, _) -> handleCanaryPromote(canaryId))
             .asJson(),
        // POST /api/canary/{canaryId}/promote-full - Promote to 100%
        Route.<CanaryInfo>post("/api/canary")
             .withPath(aString(),
                       spacer("promote-full"))
             .to((canaryId, _) -> handleCanaryPromoteFull(canaryId))
             .asJson(),
        // POST /api/canary/{canaryId}/rollback - Rollback
        Route.<CanaryInfo>post("/api/canary")
             .withPath(aString(),
                       spacer("rollback"))
             .to((canaryId, _) -> handleCanaryRollback(canaryId))
             .asJson());
    }

    private Promise<CanaryInfo> handleCanaryStart(CanaryStartRequest request) {
        return requireLeader().flatMap(_ -> parseStartRequest(request))
                            .flatMap(parsed -> nodeSupplier.get().canaryDeploymentManager()
                                                               .startCanary(parsed.artifactBase(),
                                                                            parsed.version(),
                                                                            parsed.instances(),
                                                                            CanaryStage.defaultStages(),
                                                                            parsed.thresholds(),
                                                                            CanaryAnalysisConfig.DEFAULT,
                                                                            parsed.cleanupPolicy()))
                            .map(this::toCanaryInfo)
                            .onSuccess(info -> AuditLog.deploymentStart("canary",
                                                                        info.canaryId(),
                                                                        info.artifactBase(),
                                                                        info.oldVersion(),
                                                                        info.newVersion()));
    }

    private Promise<CanaryInfo> handleCanaryPromote(String canaryId) {
        return requireLeader().flatMap(_ -> nodeSupplier.get().canaryDeploymentManager()
                                                            .promoteCanary(canaryId))
                            .map(this::toCanaryInfo)
                            .onSuccess(info -> AuditLog.deploymentPromote("canary",
                                                                          info.canaryId(),
                                                                          info.artifactBase(),
                                                                          info.routing()));
    }

    private Promise<CanaryInfo> handleCanaryPromoteFull(String canaryId) {
        return requireLeader().flatMap(_ -> nodeSupplier.get().canaryDeploymentManager()
                                                            .promoteCanaryFull(canaryId))
                            .map(this::toCanaryInfo)
                            .onSuccess(info -> AuditLog.deploymentPromote("canary",
                                                                          info.canaryId(),
                                                                          info.artifactBase(),
                                                                          info.routing()));
    }

    private Promise<CanaryInfo> handleCanaryRollback(String canaryId) {
        return requireLeader().flatMap(_ -> nodeSupplier.get().canaryDeploymentManager()
                                                            .rollbackCanary(canaryId))
                            .map(this::toCanaryInfo)
                            .onSuccess(info -> AuditLog.deploymentRollback("canary",
                                                                           info.canaryId(),
                                                                           info.artifactBase(),
                                                                           "manual"));
    }

    private Promise<org.pragmatica.lang.Unit> requireLeader() {
        var node = nodeSupplier.get();
        if ( !node.isLeader()) {
            var leaderInfo = node.leader().map(id -> " Current leader: " + id.id())
                                        .or("");
            return Causes.cause(NOT_LEADER.message() + leaderInfo).promise();
        }
        return Promise.unitPromise();
    }

    private record ParsedCanaryRequest(ArtifactBase artifactBase,
                                       Version version,
                                       int instances,
                                       HealthThresholds thresholds,
                                       CleanupPolicy cleanupPolicy){}

    private Promise<ParsedCanaryRequest> parseStartRequest(CanaryStartRequest request) {
        if ( request.artifactBase() == null || request.version() == null) {
        return MISSING_ARTIFACT_BASE_OR_VERSION.promise();}
        var instances = Option.option(request.instances()).or(1);
        var maxErrorRate = Option.option(request.maxErrorRate()).or(0.01);
        var maxLatencyMs = Option.option(request.maxLatencyMs()).or(500L);
        var cleanupPolicy = request.cleanupPolicy() != null
                            ? CleanupPolicy.valueOf(request.cleanupPolicy())
                            : CleanupPolicy.GRACE_PERIOD;
        return Result.all(ArtifactBase.artifactBase(request.artifactBase()),
                          Version.version(request.version()),
                          HealthThresholds.healthThresholds(maxErrorRate, maxLatencyMs, false)).map((artifactBase, version, thresholds) -> new ParsedCanaryRequest(artifactBase,
                                                                                                                                                                   version,
                                                                                                                                                                   instances,
                                                                                                                                                                   thresholds,
                                                                                                                                                                   cleanupPolicy))
                         .async();
    }

    private CanaryListResponse buildCanaryListResponse() {
        var canaries = nodeSupplier.get().canaryDeploymentManager()
                                       .allCanaries()
                                       .stream()
                                       .map(this::toCanaryInfo)
                                       .toList();
        return new CanaryListResponse(canaries);
    }

    private Promise<CanaryInfo> buildCanaryResponse(String canaryId) {
        return nodeSupplier.get().canaryDeploymentManager()
                               .getCanary(canaryId)
                               .map(this::toCanaryInfo)
                               .async(CANARY_NOT_FOUND);
    }

    private Promise<CanaryHealthResponse> buildCanaryHealthResponse(String canaryId) {
        return nodeSupplier.get().canaryDeploymentManager()
                               .getHealthComparison(canaryId)
                               .map(this::toCanaryHealthResponse);
    }

    private CanaryInfo toCanaryInfo(CanaryDeployment canary) {
        return new CanaryInfo(canary.canaryId(),
                              canary.artifactBase().asString(),
                              canary.oldVersion().toString(),
                              canary.newVersion().toString(),
                              canary.state().name(),
                              canary.routing().toString(),
                              canary.currentStageIndex() + 1,
                              canary.stages().size(),
                              canary.newInstances(),
                              canary.createdAt(),
                              canary.updatedAt());
    }

    private CanaryHealthResponse toCanaryHealthResponse(CanaryHealthComparison comparison) {
        return new CanaryHealthResponse(comparison.canaryId(),
                                        comparison.verdict().name(),
                                        toCanaryVersionHealth(comparison.baselineMetrics()),
                                        toCanaryVersionHealth(comparison.canaryMetrics()),
                                        comparison.collectedAt());
    }

    private CanaryVersionHealth toCanaryVersionHealth(CanaryHealthComparison.VersionMetrics metrics) {
        return new CanaryVersionHealth(metrics.version().toString(),
                                       metrics.requestCount(),
                                       metrics.errorRate(),
                                       metrics.p99LatencyMs());
    }
}
