package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.RollingUpdate;
import org.pragmatica.aether.update.VersionRouting;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Result;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Routes for rolling update management: start, routing, complete, rollback.
 */
public final class RollingUpdateRoutes implements RouteHandler {
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ARTIFACT_BASE_PATTERN = Pattern.compile("\"artifactBase\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INSTANCES_PATTERN = Pattern.compile("\"instances\"\\s*:\\s*(\\d+)");
    private static final Pattern ROUTING_PATTERN = Pattern.compile("\"routing\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAX_ERROR_RATE_PATTERN = Pattern.compile("\"maxErrorRate\"\\s*:\\s*([0-9.]+)");
    private static final Pattern MAX_LATENCY_PATTERN = Pattern.compile("\"maxLatencyMs\"\\s*:\\s*(\\d+)");
    private static final Pattern MANUAL_APPROVAL_PATTERN = Pattern.compile("\"requireManualApproval\"\\s*:\\s*(true|false)");
    private static final Pattern CLEANUP_POLICY_PATTERN = Pattern.compile("\"cleanupPolicy\"\\s*:\\s*\"([^\"]+)\"");

    private final Supplier<AetherNode> nodeSupplier;

    private RollingUpdateRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static RollingUpdateRoutes rollingUpdateRoutes(Supplier<AetherNode> nodeSupplier) {
        return new RollingUpdateRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        // GET endpoints
        if (method == HttpMethod.GET) {
            if (path.equals("/api/rolling-updates")) {
                response.ok(buildRollingUpdatesResponse());
                return true;
            }
            if (path.startsWith("/api/rolling-update/") && path.endsWith("/health")) {
                var updateId = extractUpdateId(path, "/health");
                response.ok(buildRollingUpdateHealthResponse(updateId));
                return true;
            }
            if (path.startsWith("/api/rolling-update/")) {
                var updateId = path.substring("/api/rolling-update/".length());
                response.ok(buildRollingUpdateResponse(updateId));
                return true;
            }
            return false;
        }
        // POST endpoints
        if (method == HttpMethod.POST && path.startsWith("/api/rolling-update")) {
            var body = ctx.bodyAsString();
            if (path.equals("/api/rolling-update/start")) {
                handleRollingUpdateStart(response, body);
                return true;
            }
            if (path.endsWith("/routing")) {
                var updateId = extractUpdateId(path, "/routing");
                handleRollingUpdateRouting(response, updateId, body);
                return true;
            }
            if (path.endsWith("/complete")) {
                var updateId = extractUpdateId(path, "/complete");
                handleRollingUpdateComplete(response, updateId);
                return true;
            }
            if (path.endsWith("/rollback")) {
                var updateId = extractUpdateId(path, "/rollback");
                handleRollingUpdateRollback(response, updateId);
                return true;
            }
        }
        return false;
    }

    private String extractUpdateId(String uri, String suffix) {
        var path = uri.substring("/api/rolling-update/".length());
        return path.substring(0,
                              path.length() - suffix.length());
    }

    private void handleRollingUpdateStart(ResponseWriter response, String body) {
        if (!requireLeader(response)) {
            return;
        }
        parseRollingUpdateRequest(body).onSuccess(req -> nodeSupplier.get()
                                                                     .rollingUpdateManager()
                                                                     .startUpdate(req.artifactBase(),
                                                                                  req.version(),
                                                                                  req.instances(),
                                                                                  req.thresholds(),
                                                                                  req.cleanupPolicy())
                                                                     .onSuccess(update -> response.ok(buildRollingUpdateJson(update)))
                                                                     .onFailure(cause -> response.internalError(cause)))
                                 .onFailure(cause -> response.badRequest(cause.message()));
    }

    private void handleRollingUpdateRouting(ResponseWriter response, String updateId, String body) {
        if (!requireLeader(response)) {
            return;
        }
        var routingMatch = ROUTING_PATTERN.matcher(body);
        if (!routingMatch.find()) {
            response.badRequest("Missing routing field");
            return;
        }
        VersionRouting.versionRouting(routingMatch.group(1))
                      .onSuccess(routing -> nodeSupplier.get()
                                                        .rollingUpdateManager()
                                                        .adjustRouting(updateId, routing)
                                                        .onSuccess(update -> response.ok(buildRollingUpdateJson(update)))
                                                        .onFailure(cause -> response.badRequest(cause.message())))
                      .onFailure(cause -> response.badRequest(cause.message()));
    }

    private void handleRollingUpdateComplete(ResponseWriter response, String updateId) {
        if (!requireLeader(response)) {
            return;
        }
        nodeSupplier.get()
                    .rollingUpdateManager()
                    .completeUpdate(updateId)
                    .onSuccess(update -> response.ok(buildRollingUpdateJson(update)))
                    .onFailure(cause -> response.badRequest(cause.message()));
    }

    private void handleRollingUpdateRollback(ResponseWriter response, String updateId) {
        if (!requireLeader(response)) {
            return;
        }
        nodeSupplier.get()
                    .rollingUpdateManager()
                    .rollback(updateId)
                    .onSuccess(update -> response.ok(buildRollingUpdateJson(update)))
                    .onFailure(cause -> response.badRequest(cause.message()));
    }

    private boolean requireLeader(ResponseWriter response) {
        var node = nodeSupplier.get();
        if (!node.isLeader()) {
            var leaderInfo = node.leader()
                                 .map(id -> " Current leader: " + id.id())
                                 .or("");
            response.error(HttpStatus.SERVICE_UNAVAILABLE, "This operation requires the leader node." + leaderInfo);
            return false;
        }
        return true;
    }

    private record RollingUpdateRequest(org.pragmatica.aether.artifact.ArtifactBase artifactBase,
                                        org.pragmatica.aether.artifact.Version version,
                                        int instances,
                                        HealthThresholds thresholds,
                                        CleanupPolicy cleanupPolicy) {}

    private Result<RollingUpdateRequest> parseRollingUpdateRequest(String body) {
        var artifactBaseMatch = ARTIFACT_BASE_PATTERN.matcher(body);
        var versionMatch = VERSION_PATTERN.matcher(body);
        var instancesMatch = INSTANCES_PATTERN.matcher(body);
        if (!artifactBaseMatch.find() || !versionMatch.find()) {
            return org.pragmatica.lang.utils.Causes.cause("Missing artifactBase or version")
                      .result();
        }
        var artifactBaseStr = artifactBaseMatch.group(1);
        var versionStr = versionMatch.group(1);
        int instances = instancesMatch.find()
                        ? Integer.parseInt(instancesMatch.group(1))
                        : 1;
        var errorRateMatch = MAX_ERROR_RATE_PATTERN.matcher(body);
        var latencyMatch = MAX_LATENCY_PATTERN.matcher(body);
        var manualApprovalMatch = MANUAL_APPROVAL_PATTERN.matcher(body);
        var cleanupMatch = CLEANUP_POLICY_PATTERN.matcher(body);
        double maxErrorRate = errorRateMatch.find()
                              ? Double.parseDouble(errorRateMatch.group(1))
                              : 0.01;
        long maxLatencyMs = latencyMatch.find()
                            ? Long.parseLong(latencyMatch.group(1))
                            : 500;
        boolean manualApproval = manualApprovalMatch.find() && Boolean.parseBoolean(manualApprovalMatch.group(1));
        var cleanupPolicy = cleanupMatch.find()
                            ? CleanupPolicy.valueOf(cleanupMatch.group(1))
                            : CleanupPolicy.GRACE_PERIOD;
        return org.pragmatica.aether.artifact.ArtifactBase.artifactBase(artifactBaseStr)
                  .flatMap(artifactBase -> org.pragmatica.aether.artifact.Version.version(versionStr)
                                              .flatMap(version -> HealthThresholds.healthThresholds(maxErrorRate,
                                                                                                    maxLatencyMs,
                                                                                                    manualApproval)
                                                                                  .map(thresholds -> new RollingUpdateRequest(artifactBase,
                                                                                                                              version,
                                                                                                                              instances,
                                                                                                                              thresholds,
                                                                                                                              cleanupPolicy))));
    }

    private String buildRollingUpdatesResponse() {
        var node = nodeSupplier.get();
        var updates = node.rollingUpdateManager()
                          .activeUpdates();
        var sb = new StringBuilder();
        sb.append("{\"updates\":[");
        boolean first = true;
        for (var update : updates) {
            if (!first) sb.append(",");
            sb.append(buildRollingUpdateJson(update));
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildRollingUpdateResponse(String updateId) {
        var node = nodeSupplier.get();
        return node.rollingUpdateManager()
                   .getUpdate(updateId)
                   .map(this::buildRollingUpdateJson)
                   .or("{\"error\":\"Update not found\",\"updateId\":\"" + updateId + "\"}");
    }

    private String buildRollingUpdateHealthResponse(String updateId) {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        node.rollingUpdateManager()
            .getHealthMetrics(updateId)
            .onSuccess(metrics -> {
                           sb.append("{\"updateId\":\"")
                             .append(updateId)
                             .append("\",");
                           sb.append("\"oldVersion\":{");
                           sb.append("\"version\":\"")
                             .append(metrics.oldVersion()
                                            .version())
                             .append("\",");
                           sb.append("\"requestCount\":")
                             .append(metrics.oldVersion()
                                            .requestCount())
                             .append(",");
                           sb.append("\"errorRate\":")
                             .append(metrics.oldVersion()
                                            .errorRate())
                             .append(",");
                           sb.append("\"avgLatencyMs\":")
                             .append(metrics.oldVersion()
                                            .avgLatencyMs());
                           sb.append("},\"newVersion\":{");
                           sb.append("\"version\":\"")
                             .append(metrics.newVersion()
                                            .version())
                             .append("\",");
                           sb.append("\"requestCount\":")
                             .append(metrics.newVersion()
                                            .requestCount())
                             .append(",");
                           sb.append("\"errorRate\":")
                             .append(metrics.newVersion()
                                            .errorRate())
                             .append(",");
                           sb.append("\"avgLatencyMs\":")
                             .append(metrics.newVersion()
                                            .avgLatencyMs());
                           sb.append("},\"collectedAt\":")
                             .append(metrics.collectedAt())
                             .append("}");
                       })
            .onFailure(cause -> sb.append("{\"error\":\"")
                                  .append(cause.message())
                                  .append("\"}"));
        return sb.length() > 0
               ? sb.toString()
               : "{\"error\":\"Unknown error\"}";
    }

    private String buildRollingUpdateJson(RollingUpdate update) {
        return "{\"updateId\":\"" + update.updateId() + "\"," + "\"artifactBase\":\"" + update.artifactBase()
                                                                                              .asString() + "\","
               + "\"oldVersion\":\"" + update.oldVersion() + "\"," + "\"newVersion\":\"" + update.newVersion() + "\","
               + "\"state\":\"" + update.state()
                                        .name() + "\"," + "\"routing\":\"" + update.routing() + "\","
               + "\"newInstances\":" + update.newInstances() + "," + "\"createdAt\":" + update.createdAt() + ","
               + "\"updatedAt\":" + update.updatedAt() + "}";
    }
}
