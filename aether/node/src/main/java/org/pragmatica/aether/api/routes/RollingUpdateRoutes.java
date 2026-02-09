package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.RollingUpdate;
import org.pragmatica.aether.update.RollingUpdateManager.VersionMetrics;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.aether.api.ManagementApiResponses.RollingUpdateErrorResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.RollingUpdateHealthResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.RollingUpdateInfo;
import static org.pragmatica.aether.api.ManagementApiResponses.RollingUpdatesResponse;
import static org.pragmatica.aether.api.ManagementApiResponses.VersionHealth;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// Routes for rolling update management: start, routing, complete, rollback.
public final class RollingUpdateRoutes implements RouteSource {
    private static final Cause MISSING_ARTIFACT_BASE_OR_VERSION = Causes.cause("Missing artifactBase or version");
    private static final Cause NOT_LEADER = Causes.cause("This operation requires the leader node");

    private final Supplier<AetherNode> nodeSupplier;

    private RollingUpdateRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static RollingUpdateRoutes rollingUpdateRoutes(Supplier<AetherNode> nodeSupplier) {
        return new RollingUpdateRoutes(nodeSupplier);
    }

    // Request DTOs
    record RollingUpdateStartRequest(String artifactBase,
                                     String version,
                                     Integer instances,
                                     Double maxErrorRate,
                                     Long maxLatencyMs,
                                     Boolean requireManualApproval,
                                     String cleanupPolicy) {}

    record RoutingRequest(String routing) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(// GET /api/rolling-updates - List all updates
        Route.<RollingUpdatesResponse> get("/api/rolling-updates")
             .toJson(this::buildRollingUpdatesResponse),
        // GET /api/rolling-update/{updateId}/health - Update health
        Route.<RollingUpdateHealthResponse> get("/api/rolling-update")
             .withPath(aString(),
                       spacer("health"))
             .to((updateId, _) -> buildRollingUpdateHealthResponse(updateId))
             .asJson(),
        // GET /api/rolling-update/{updateId} - Single update by ID
        Route.<RollingUpdateInfo> get("/api/rolling-update")
             .withPath(aString())
             .to(this::buildRollingUpdateResponse)
             .asJson(),
        // POST /api/rolling-update/start - Start update
        Route.<RollingUpdateInfo> post("/api/rolling-update/start")
             .withBody(RollingUpdateStartRequest.class)
             .toJson(this::handleRollingUpdateStart),
        // POST /api/rolling-update/{updateId}/routing - Update routing
        Route.<RollingUpdateInfo> post("/api/rolling-update")
             .withPath(aString(),
                       spacer("routing"))
             .withBody(RoutingRequest.class)
             .to((updateId, _, body) -> handleRollingUpdateRouting(updateId, body))
             .asJson(),
        // POST /api/rolling-update/{updateId}/complete - Complete update
        Route.<RollingUpdateInfo> post("/api/rolling-update")
             .withPath(aString(),
                       spacer("complete"))
             .to((updateId, _) -> handleRollingUpdateComplete(updateId))
             .asJson(),
        // POST /api/rolling-update/{updateId}/rollback - Rollback
        Route.<RollingUpdateInfo> post("/api/rolling-update")
             .withPath(aString(),
                       spacer("rollback"))
             .to((updateId, _) -> handleRollingUpdateRollback(updateId))
             .asJson());
    }

    private Promise<RollingUpdateInfo> handleRollingUpdateStart(RollingUpdateStartRequest request) {
        return requireLeader().flatMap(_ -> parseStartRequest(request))
                            .flatMap(parsed -> nodeSupplier.get()
                                                           .rollingUpdateManager()
                                                           .startUpdate(parsed.artifactBase(),
                                                                        parsed.version(),
                                                                        parsed.instances(),
                                                                        parsed.thresholds(),
                                                                        parsed.cleanupPolicy()))
                            .map(this::toRollingUpdateInfo);
    }

    private Promise<RollingUpdateInfo> handleRollingUpdateRouting(String updateId, RoutingRequest request) {
        var resolvedId = resolveUpdateId(updateId);
        return requireLeader().flatMap(_ -> VersionRouting.versionRouting(request.routing())
                                                          .async())
                            .flatMap(routing -> nodeSupplier.get()
                                                            .rollingUpdateManager()
                                                            .adjustRouting(resolvedId, routing))
                            .map(this::toRollingUpdateInfo);
    }

    private Promise<RollingUpdateInfo> handleRollingUpdateComplete(String updateId) {
        var resolvedId = resolveUpdateId(updateId);
        return requireLeader().flatMap(_ -> nodeSupplier.get()
                                                        .rollingUpdateManager()
                                                        .completeUpdate(resolvedId))
                            .map(this::toRollingUpdateInfo);
    }

    private Promise<RollingUpdateInfo> handleRollingUpdateRollback(String updateId) {
        var resolvedId = resolveUpdateId(updateId);
        return requireLeader().flatMap(_ -> nodeSupplier.get()
                                                        .rollingUpdateManager()
                                                        .rollback(resolvedId))
                            .map(this::toRollingUpdateInfo);
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

    private record ParsedStartRequest(ArtifactBase artifactBase,
                                      Version version,
                                      int instances,
                                      HealthThresholds thresholds,
                                      CleanupPolicy cleanupPolicy) {}

    private Promise<ParsedStartRequest> parseStartRequest(RollingUpdateStartRequest request) {
        if (request.artifactBase() == null || request.version() == null) {
            return MISSING_ARTIFACT_BASE_OR_VERSION.promise();
        }
        var instances = defaultIfNull(request.instances(), 1);
        var maxErrorRate = defaultIfNull(request.maxErrorRate(), 0.01);
        var maxLatencyMs = defaultIfNull(request.maxLatencyMs(), 500L);
        var manualApproval = request.requireManualApproval() != null && request.requireManualApproval();
        var cleanupPolicy = request.cleanupPolicy() != null
                            ? CleanupPolicy.valueOf(request.cleanupPolicy())
                            : CleanupPolicy.GRACE_PERIOD;
        return Result.all(ArtifactBase.artifactBase(request.artifactBase()),
                          Version.version(request.version()),
                          HealthThresholds.healthThresholds(maxErrorRate, maxLatencyMs, manualApproval))
                     .map((artifactBase, version, thresholds) -> new ParsedStartRequest(artifactBase,
                                                                                        version,
                                                                                        instances,
                                                                                        thresholds,
                                                                                        cleanupPolicy))
                     .async();
    }

    private static <T> T defaultIfNull(T value, T defaultValue) {
        return value != null
               ? value
               : defaultValue;
    }

    /// Resolves "current" to the first active rolling update ID.
    private String resolveUpdateId(String updateId) {
        if ("current".equals(updateId)) {
            var updates = nodeSupplier.get()
                                      .rollingUpdateManager()
                                      .activeUpdates();
            if (!updates.isEmpty()) {
                return updates.getFirst()
                              .updateId();
            }
        }
        return updateId;
    }

    private RollingUpdatesResponse buildRollingUpdatesResponse() {
        var updates = nodeSupplier.get()
                                  .rollingUpdateManager()
                                  .activeUpdates()
                                  .stream()
                                  .map(this::toRollingUpdateInfo)
                                  .toList();
        return new RollingUpdatesResponse(updates);
    }

    private static final Cause UPDATE_NOT_FOUND = Causes.cause("Update not found");

    private Promise<RollingUpdateInfo> buildRollingUpdateResponse(String updateId) {
        var resolvedId = resolveUpdateId(updateId);
        return nodeSupplier.get()
                           .rollingUpdateManager()
                           .getUpdate(resolvedId)
                           .map(this::toRollingUpdateInfo)
                           .async(UPDATE_NOT_FOUND);
    }

    private Promise<RollingUpdateHealthResponse> buildRollingUpdateHealthResponse(String updateId) {
        var resolvedId = resolveUpdateId(updateId);
        return nodeSupplier.get()
                           .rollingUpdateManager()
                           .getHealthMetrics(resolvedId)
                           .map(metrics -> new RollingUpdateHealthResponse(resolvedId,
                                                                           toVersionHealth(metrics.oldVersion()),
                                                                           toVersionHealth(metrics.newVersion()),
                                                                           metrics.collectedAt()));
    }

    private RollingUpdateInfo toRollingUpdateInfo(RollingUpdate update) {
        return new RollingUpdateInfo(update.updateId(),
                                     update.artifactBase()
                                           .asString(),
                                     update.oldVersion()
                                           .toString(),
                                     update.newVersion()
                                           .toString(),
                                     update.state()
                                           .name(),
                                     update.routing()
                                           .toString(),
                                     update.newInstances(),
                                     update.createdAt(),
                                     update.updatedAt());
    }

    private VersionHealth toVersionHealth(VersionMetrics metrics) {
        return new VersionHealth(metrics.version()
                                        .toString(),
                                 metrics.requestCount(),
                                 metrics.errorRate(),
                                 metrics.avgLatencyMs());
    }
}
