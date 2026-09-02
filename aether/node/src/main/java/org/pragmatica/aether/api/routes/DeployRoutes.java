// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.update.CanaryAnalysisConfig;
import org.pragmatica.aether.update.CanaryStage;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.Deployment;
import org.pragmatica.aether.update.DeploymentError;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentStrategy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.StrategyConfig;
import org.pragmatica.aether.update.StrategyConfig.BlueGreenConfig;
import org.pragmatica.aether.update.StrategyConfig.CanaryConfig;
import org.pragmatica.aether.update.StrategyConfig.RollingConfig;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class DeployRoutes implements RouteSource {
    // #569: request-validation and not-found failures carry their own HTTP status. They were bare
    // `Causes.cause(...)` constants, which `ProblemResponses.resolveStatus` cannot distinguish from a
    // node fault — every one of them answered 500. `DEPLOYMENT_NOT_FOUND` is now minted per-request
    // from the domain type so the ProblemDetail `detail` names WHICH deployment was missing.
    private static final Cause MISSING_BLUEPRINT = DeployRouteError.MISSING_BLUEPRINT;
    private static final Cause MISSING_STRATEGY = DeployRouteError.MISSING_STRATEGY;
    private static final Cause INVALID_STRATEGY = DeployRouteError.INVALID_STRATEGY;
    private static final Cause MISSING_CANARY_STAGES = DeployRouteError.MISSING_CANARY_STAGES;

    private final Supplier<ManageableNode> nodeSupplier;

    private DeployRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static DeployRoutes deployRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new DeployRoutes(nodeSupplier);
    }

    record DeployRequest(String blueprint,
                         String strategy,
                         Integer instances,
                         Map<String, Object> thresholds,
                         String cleanupPolicy,
                         Map<String, Object> canary,
                         Map<String, Object> blueGreen,
                         Map<String, Object> rolling) {}

    record DeploymentResponse(String deploymentId,
                              String blueprintId,
                              String oldVersion,
                              String newVersion,
                              String state,
                              String strategy,
                              int routingNewWeight,
                              int routingOldWeight,
                              long createdAt,
                              long updatedAt) {}

    record DeploymentListResponse(List<DeploymentResponse> deployments) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<DeploymentResponse> route(ManagementRoute.DEPLOY_START)
                                         .withBody(DeployRequest.class)
                                         .toResult(this::startDeployment)
                                         .asJson(),
                         ManagementRoutes.<DeploymentListResponse> route(ManagementRoute.DEPLOY_LIST).toJson(this::listDeployments),
                         ManagementRoutes.<DeploymentResponse> route(ManagementRoute.DEPLOY_STATUS)
                                         .withPath(aString())
                                         .toResult(this::getDeployment)
                                         .asJson(),
                         ManagementRoutes.<DeploymentResponse> route(ManagementRoute.DEPLOY_PROMOTE)
                                         .withPath(aString())
                                         .toResult(this::promoteDeployment)
                                         .asJson(),
                         ManagementRoutes.<DeploymentResponse> route(ManagementRoute.DEPLOY_ROLLBACK)
                                         .withPath(aString())
                                         .toResult(this::rollbackDeployment)
                                         .asJson(),
                         ManagementRoutes.<DeploymentResponse> route(ManagementRoute.DEPLOY_COMPLETE)
                                         .withPath(aString())
                                         .toResult(this::completeDeployment)
                                         .asJson());
    }

    private Result<DeploymentResponse> startDeployment(DeployRequest request) {
        return parseDeployRequest(request).flatMap(this::executeStart)
                                 .map(DeployRoutes::toResponse);
    }

    private DeploymentListResponse listDeployments() {
        var responses = deploymentManager().list().stream().map(DeployRoutes::toResponse).toList();

        return new DeploymentListResponse(responses);
    }

    private Result<DeploymentResponse> getDeployment(String deploymentId) {
        return deploymentManager().status(deploymentId)
                                .toResult(DeploymentError.DeploymentNotFound.deploymentNotFound(deploymentId))
                                .map(DeployRoutes::toResponse);
    }

    private Result<DeploymentResponse> promoteDeployment(String deploymentId) {
        return deploymentManager().promote(deploymentId)
                                .map(DeployRoutes::toResponse);
    }

    private Result<DeploymentResponse> rollbackDeployment(String deploymentId) {
        return deploymentManager().rollback(deploymentId)
                                .map(DeployRoutes::toResponse);
    }

    private Result<DeploymentResponse> completeDeployment(String deploymentId) {
        return deploymentManager().complete(deploymentId)
                                .map(DeployRoutes::toResponse);
    }

    private record ParsedDeployRequest(String blueprintId,
                                       Version newVersion,
                                       DeploymentStrategy strategy,
                                       StrategyConfig config,
                                       HealthThresholds thresholds,
                                       CleanupPolicy cleanupPolicy,
                                       int instances) {}

    private Result<ParsedDeployRequest> parseDeployRequest(DeployRequest request) {
        return parseBlueprint(request).flatMap(blueprintParts -> buildParsedRequest(blueprintParts, request));
    }

    /// Validation is a SEQUENTIAL first-failure-wins chain, deliberately NOT `Result.all` (#569).
    ///
    /// `Result.all` replaces the emerging cause with `Causes.composite(...)` as soon as ANY input fails,
    /// and `CompositeCause extends Cause` only — so the `HttpStatusAware` mixin these causes carry is
    /// erased on the way out and `ProblemResponses.resolveStatus` silently restores the very 500 this
    /// change exists to remove. An unrecognized strategy answered `500 Internal Server Error` for exactly
    /// this reason, even though `DeployRouteError.INVALID_STRATEGY` is typed 400.
    ///
    /// Accumulation buys nothing here: `CompositeCause` renders one opaque message, so the caller never
    /// saw the individual validation failures anyway. First-failure-wins yields one precise message AND
    /// the correct status. `CompositeCause` cannot simply be made status-aware — it lives in `core`,
    /// which is deliberately HTTP-free.
    private Result<ParsedDeployRequest> buildParsedRequest(String[] blueprintParts, DeployRequest request) {
        return parseVersion(blueprintParts[2]).flatMap(version -> parseStrategy(request.strategy()).flatMap(strategy -> completeParsedRequest(request,
                                                                                                                                              version,
                                                                                                                                              strategy)));
    }

    private Result<ParsedDeployRequest> completeParsedRequest(DeployRequest request,
                                                              Version version,
                                                              DeploymentStrategy strategy) {
        return parseThresholds(request.thresholds()).flatMap(thresholds -> parseCleanupPolicy(request.cleanupPolicy()).flatMap(cleanupPolicy -> parseStrategyConfig(strategy,
                                                                                                                                                                    request).map(config -> new ParsedDeployRequest(request.blueprint(),
                                                                                                                                                                                                                   version,
                                                                                                                                                                                                                   strategy,
                                                                                                                                                                                                                   config,
                                                                                                                                                                                                                   thresholds,
                                                                                                                                                                                                                   cleanupPolicy,
                                                                                                                                                                                                                   parseInstances(request.instances())))));
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
        return switch (name) {
            case "CANARY" -> Result.success(DeploymentStrategy.CANARY);
            case "BLUE_GREEN" -> Result.success(DeploymentStrategy.BLUE_GREEN);
            case "ROLLING" -> Result.success(DeploymentStrategy.ROLLING);
            default -> INVALID_STRATEGY.result();
        };
    }

    // RET-06: `raw` is a deserialized request body (nullable JSON map); the null/empty coalesce is
    // parse-don't-validate handling of wire input.
    @SuppressWarnings("JBCT-RET-06")
    private static Result<HealthThresholds> parseThresholds(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Result.success(HealthThresholds.DEFAULT);
        }

        var maxErrorRate = toDouble(raw.get("maxErrorRate"), HealthThresholds.DEFAULT.maxErrorRate());
        var maxLatencyMs = toLong(raw.get("maxLatencyMs"),
                                  HealthThresholds.DEFAULT.maxLatency().millis());

        return HealthThresholds.healthThresholds(maxErrorRate, maxLatencyMs, false);
    }

    // RET-06: `raw` is a nullable request field; the null/empty coalesce is parse-don't-validate of wire input.
    @SuppressWarnings("JBCT-RET-06")
    private static Result<CleanupPolicy> parseCleanupPolicy(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Result.success(CleanupPolicy.GRACE_PERIOD);
        }

        return switch (raw.toUpperCase()) {
            case "IMMEDIATE" -> Result.success(CleanupPolicy.IMMEDIATE);
            case "GRACE_PERIOD" -> Result.success(CleanupPolicy.GRACE_PERIOD);
            case "MANUAL" -> Result.success(CleanupPolicy.MANUAL);
            default -> Result.success(CleanupPolicy.GRACE_PERIOD);
        };
    }

    // RET-06: `raw` is a nullable boxed Integer from a deserialized request; the coalesce is wire-input handling.
    @SuppressWarnings("JBCT-RET-06")
    private static int parseInstances(Integer raw) {
        return raw != null
               ? raw
               : 1;
    }

    private static Result<StrategyConfig> parseStrategyConfig(DeploymentStrategy strategy, DeployRequest request) {
        return switch (strategy) {
            case CANARY -> parseCanaryConfig(request.canary());
            case BLUE_GREEN -> parseBlueGreenConfig(request.blueGreen());
            case ROLLING -> parseRollingConfig(request.rolling());
        };
    }

    // RET-06: `raw` is a deserialized request body (nullable JSON map); parse-don't-validate of wire input.
    @SuppressWarnings({"unchecked", "JBCT-RET-06"})
    private static Result<StrategyConfig> parseCanaryConfig(Map<String, Object> raw) {
        if (raw == null) {
            return MISSING_CANARY_STAGES.result();
        }

        var rawStages = (List<Map<String, Object>>) raw.get("stages");

        if (rawStages == null || rawStages.isEmpty()) {
            return MISSING_CANARY_STAGES.result();
        }

        return parseCanaryStages(rawStages).map(stages -> new CanaryConfig(stages, CanaryAnalysisConfig.DEFAULT));
    }

    /// First-failure-wins, deliberately NOT `Result.allOf` (#569). `allOf` accumulates every stage's
    /// failure into `Causes.composite(...)`, and `CompositeCause extends Cause` only — which erases the
    /// `HttpStatusAware` mixin the stage errors carry and restores the 500. A canary deploy with
    /// `trafficPercent: 500` is a malformed request, not a cluster fault.
    ///
    /// `flatMap` is what preserves the cause: on a failure it forwards the original instance untouched,
    /// so the mixin survives the hop to the response funnel.
    private static Result<List<CanaryStage>> parseCanaryStages(List<Map<String, Object>> rawStages) {
        return parseCanaryStagesFrom(rawStages, 0, List.of());
    }

    private static Result<List<CanaryStage>> parseCanaryStagesFrom(List<Map<String, Object>> rawStages,
                                                                   int index,
                                                                   List<CanaryStage> accumulated) {
        if (index >= rawStages.size()) {
            return Result.success(accumulated);
        }

        return parseCanaryStage(rawStages.get(index)).flatMap(stage -> parseCanaryStagesFrom(rawStages,
                                                                                             index + 1,
                                                                                             appended(accumulated, stage)));
    }

    private static List<CanaryStage> appended(List<CanaryStage> stages, CanaryStage stage) {
        var next = new ArrayList<>(stages);

        next.add(stage);

        return List.copyOf(next);
    }

    private static Result<CanaryStage> parseCanaryStage(Map<String, Object> raw) {
        var trafficPercent = toInt(raw.get("trafficPercent"), 5);
        var observationMinutes = toInt(raw.get("observationMinutes"), 10);

        return CanaryStage.canaryStage(trafficPercent, observationMinutes);
    }

    // RET-06: `raw` is a deserialized request body (nullable JSON map); parse-don't-validate of wire input.
    @SuppressWarnings("JBCT-RET-06")
    private static Result<StrategyConfig> parseBlueGreenConfig(Map<String, Object> raw) {
        var drainTimeoutMs = raw != null
                             ? toLong(raw.get("drainTimeoutMs"), 30_000L)
                             : 30_000L;

        return Result.success(new BlueGreenConfig(timeSpan(drainTimeoutMs).millis()));
    }

    // RET-06: `raw` is a deserialized request body (nullable JSON map); parse-don't-validate of wire input.
    @SuppressWarnings("JBCT-RET-06")
    private static Result<StrategyConfig> parseRollingConfig(Map<String, Object> raw) {
        var requireManualApproval = raw != null && Boolean.TRUE.equals(raw.get("requireManualApproval"));

        return Result.success(new RollingConfig(requireManualApproval));
    }

    private Result<Deployment> executeStart(ParsedDeployRequest parsed) {
        return deploymentManager().start(parsed.blueprintId(),
                                         parsed.newVersion(),
                                         parsed.strategy(),
                                         parsed.config(),
                                         parsed.thresholds(),
                                         parsed.cleanupPolicy(),
                                         parsed.instances());
    }

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

    private static double toDouble(Object value, double defaultValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }

        return defaultValue;
    }

    private static long toLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            return n.longValue();
        }

        return defaultValue;
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }

        return defaultValue;
    }

    private DeploymentManager deploymentManager() {
        return nodeSupplier.get()
                           .deploymentManager();
    }
}
