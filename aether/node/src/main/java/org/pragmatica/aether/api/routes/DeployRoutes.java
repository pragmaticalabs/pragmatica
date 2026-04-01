package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.update.CanaryAnalysisConfig;
import org.pragmatica.aether.update.CanaryStage;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.Deployment;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentStrategy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.StrategyConfig;
import org.pragmatica.aether.update.StrategyConfig.BlueGreenConfig;
import org.pragmatica.aether.update.StrategyConfig.CanaryConfig;
import org.pragmatica.aether.update.StrategyConfig.RollingConfig;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.PathParameter.spacer;
import static org.pragmatica.lang.Option.option;

/// Routes for unified deployment management: start, status, promote, rollback, complete.
public final class DeployRoutes implements RouteSource {
    private static final Cause NOT_FOUND = Causes.cause("Deployment not found");
    private static final Cause MISSING_BLUEPRINT = Causes.cause("Missing required field: blueprint");
    private static final Cause MISSING_STRATEGY = Causes.cause("Missing required field: strategy");
    private static final Cause INVALID_STRATEGY = Causes.cause("Invalid strategy; must be one of: canary, blue_green, rolling");
    private static final Cause MISSING_CANARY_STAGES = Causes.cause("Canary strategy requires at least one stage");

    private final Supplier<AetherNode> nodeSupplier;

    private DeployRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static DeployRoutes deployRoutes(Supplier<AetherNode> nodeSupplier) {
        return new DeployRoutes(nodeSupplier);
    }

    // --- Request DTOs ---
    record DeployRequest(String blueprint,
                         String strategy,
                         Integer instances,
                         Map<String, Object> thresholds,
                         String cleanupPolicy,
                         Map<String, Object> canary,
                         Map<String, Object> blueGreen,
                         Map<String, Object> rolling){}

    // --- Response DTOs ---
    record DeploymentResponse(String deploymentId,
                              String blueprintId,
                              String oldVersion,
                              String newVersion,
                              String state,
                              String strategy,
                              int routingNewWeight,
                              int routingOldWeight,
                              long createdAt,
                              long updatedAt){}

    record DeploymentListResponse(List<DeploymentResponse> deployments){}

    // --- Route definitions ---
    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<DeploymentResponse>post("/api/deploy")
                              .withBody(DeployRequest.class)
                              .toResult(this::startDeployment)
                              .asJson(),
                         Route.<DeploymentListResponse>get("/api/deploy")
                              .toJson(this::listDeployments),
                         Route.<DeploymentResponse>get("/api/deploy")
                              .withPath(aString())
                              .toResult(this::getDeployment)
                              .asJson(),
                         Route.<DeploymentResponse>post("/api/deploy")
                              .withPath(aString(),
                                        spacer("promote"))
                              .toResult(this::promoteDeployment)
                              .asJson(),
                         Route.<DeploymentResponse>post("/api/deploy")
                              .withPath(aString(),
                                        spacer("rollback"))
                              .toResult(this::rollbackDeployment)
                              .asJson(),
                         Route.<DeploymentResponse>post("/api/deploy")
                              .withPath(aString(),
                                        spacer("complete"))
                              .toResult(this::completeDeployment)
                              .asJson());
    }

    // --- Handlers ---
    private Result<DeploymentResponse> startDeployment(DeployRequest request) {
        return parseDeployRequest(request).flatMap(this::executeStart)
                                 .map(DeployRoutes::toResponse);
    }

    private DeploymentListResponse listDeployments() {
        var responses = deploymentManager().list()
                                         .stream()
                                         .map(DeployRoutes::toResponse)
                                         .toList();
        return new DeploymentListResponse(responses);
    }

    private Result<DeploymentResponse> getDeployment(String deploymentId) {
        return deploymentManager().status(deploymentId)
                                .toResult(NOT_FOUND)
                                .map(DeployRoutes::toResponse);
    }

    private Result<DeploymentResponse> promoteDeployment(String deploymentId, String spacer) {
        return deploymentManager().promote(deploymentId)
                                .map(DeployRoutes::toResponse);
    }

    private Result<DeploymentResponse> rollbackDeployment(String deploymentId, String spacer) {
        return deploymentManager().rollback(deploymentId)
                                .map(DeployRoutes::toResponse);
    }

    private Result<DeploymentResponse> completeDeployment(String deploymentId, String spacer) {
        return deploymentManager().complete(deploymentId)
                                .map(DeployRoutes::toResponse);
    }

    // --- Parsing ---
    private record ParsedDeployRequest(String blueprintId,
                                       Version newVersion,
                                       DeploymentStrategy strategy,
                                       StrategyConfig config,
                                       HealthThresholds thresholds,
                                       CleanupPolicy cleanupPolicy,
                                       int instances){}

    private Result<ParsedDeployRequest> parseDeployRequest(DeployRequest request) {
        return parseBlueprint(request).flatMap(blueprintParts -> buildParsedRequest(blueprintParts, request));
    }

    private Result<ParsedDeployRequest> buildParsedRequest(String[] blueprintParts, DeployRequest request) {
        return Result.all(parseVersion(blueprintParts[2]),
                          parseStrategy(request.strategy()),
                          parseThresholds(request.thresholds()),
                          parseCleanupPolicy(request.cleanupPolicy()))
        .flatMap((version, strategy, thresholds, cleanupPolicy) -> parseStrategyConfig(strategy, request)
        .map(config -> new ParsedDeployRequest(request.blueprint(),
                                               version,
                                               strategy,
                                               config,
                                               thresholds,
                                               cleanupPolicy,
                                               parseInstances(request.instances()))));
    }

    private static Result<String[]> parseBlueprint(DeployRequest request) {
        return option(request.blueprint()).toResult(MISSING_BLUEPRINT)
                     .filter(MISSING_BLUEPRINT,
                             bp -> bp.contains(":"))
                     .map(bp -> bp.split(":"));
    }

    private static Result<Version> parseVersion(String versionStr) {
        return Version.version(versionStr);
    }

    private static Result<DeploymentStrategy> parseStrategy(String raw) {
        return option(raw).toResult(MISSING_STRATEGY)
                     .map(String::toUpperCase)
                     .flatMap(DeployRoutes::toDeploymentStrategy);
    }

    private static Result<DeploymentStrategy> toDeploymentStrategy(String name) {
        return switch (name) {case "CANARY" -> Result.success(DeploymentStrategy.CANARY);case "BLUE_GREEN" -> Result.success(DeploymentStrategy.BLUE_GREEN);case "ROLLING" -> Result.success(DeploymentStrategy.ROLLING);default -> INVALID_STRATEGY.result();};
    }

    private static Result<HealthThresholds> parseThresholds(Map<String, Object> raw) {
        if ( raw == null || raw.isEmpty()) {
        return Result.success(HealthThresholds.DEFAULT);}
        var maxErrorRate = toDouble(raw.get("maxErrorRate"), HealthThresholds.DEFAULT.maxErrorRate());
        var maxLatencyMs = toLong(raw.get("maxLatencyMs"), HealthThresholds.DEFAULT.maxLatencyMs());
        return HealthThresholds.healthThresholds(maxErrorRate, maxLatencyMs, false);
    }

    private static Result<CleanupPolicy> parseCleanupPolicy(String raw) {
        if ( raw == null || raw.isEmpty()) {
        return Result.success(CleanupPolicy.GRACE_PERIOD);}
        return switch (raw.toUpperCase()) {case "IMMEDIATE" -> Result.success(CleanupPolicy.IMMEDIATE);case "GRACE_PERIOD" -> Result.success(CleanupPolicy.GRACE_PERIOD);case "MANUAL" -> Result.success(CleanupPolicy.MANUAL);default -> Result.success(CleanupPolicy.GRACE_PERIOD);};
    }

    private static int parseInstances(Integer raw) {
        return raw != null
               ? raw
               : 1;
    }

    private static Result<StrategyConfig> parseStrategyConfig(DeploymentStrategy strategy, DeployRequest request) {
        return switch (strategy) {case CANARY -> parseCanaryConfig(request.canary());case BLUE_GREEN -> parseBlueGreenConfig(request.blueGreen());case ROLLING -> parseRollingConfig(request.rolling());};
    }

    @SuppressWarnings("unchecked")
    private static Result<StrategyConfig> parseCanaryConfig(Map<String, Object> raw) {
        if ( raw == null) {
        return MISSING_CANARY_STAGES.result();}
        var rawStages = (List<Map<String, Object>>) raw.get("stages");
        if ( rawStages == null || rawStages.isEmpty()) {
        return MISSING_CANARY_STAGES.result();}
        return Result.allOf(rawStages.stream().map(DeployRoutes::parseCanaryStage)
                                            .toList())
        .map(stages -> new CanaryConfig(stages, CanaryAnalysisConfig.DEFAULT));
    }

    private static Result<CanaryStage> parseCanaryStage(Map<String, Object> raw) {
        var trafficPercent = toInt(raw.get("trafficPercent"), 5);
        var observationMinutes = toInt(raw.get("observationMinutes"), 10);
        return CanaryStage.canaryStage(trafficPercent, observationMinutes);
    }

    private static Result<StrategyConfig> parseBlueGreenConfig(Map<String, Object> raw) {
        var drainTimeoutMs = raw != null
                             ? toLong(raw.get("drainTimeoutMs"), 30_000L)
                             : 30_000L;
        return Result.success(new BlueGreenConfig(drainTimeoutMs));
    }

    private static Result<StrategyConfig> parseRollingConfig(Map<String, Object> raw) {
        var requireManualApproval = raw != null && Boolean.TRUE.equals(raw.get("requireManualApproval"));
        return Result.success(new RollingConfig(requireManualApproval));
    }

    // --- Execution ---
    private Result<Deployment> executeStart(ParsedDeployRequest parsed) {
        return deploymentManager().start(parsed.blueprintId(),
                                         parsed.newVersion(),
                                         parsed.strategy(),
                                         parsed.config(),
                                         parsed.thresholds(),
                                         parsed.cleanupPolicy(),
                                         parsed.instances());
    }

    // --- Mapping ---
    private static DeploymentResponse toResponse(Deployment d) {
        return new DeploymentResponse(d.deploymentId(),
                                      d.blueprintId(),
                                      d.oldVersion().toString(),
                                      d.newVersion().toString(),
                                      d.state().name(),
                                      d.strategy().name(),
                                      d.routing().newWeight(),
                                      d.routing().oldWeight(),
                                      d.createdAt(),
                                      d.updatedAt());
    }

    // --- Numeric helpers ---
    private static double toDouble(Object value, double defaultValue) {
        if ( value instanceof Number n) {
        return n.doubleValue();}
        return defaultValue;
    }

    private static long toLong(Object value, long defaultValue) {
        if ( value instanceof Number n) {
        return n.longValue();}
        return defaultValue;
    }

    private static int toInt(Object value, int defaultValue) {
        if ( value instanceof Number n) {
        return n.intValue();}
        return defaultValue;
    }

    private DeploymentManager deploymentManager() {
        return nodeSupplier.get().deploymentManager();
    }
}
