// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.ControllerConfigUpdatedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ControllerStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.EvaluationTriggeredResponse;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.fsm.ScalingDecisionRecord;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;


public final class ControllerRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private ControllerRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ControllerRoutes controllerRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ControllerRoutes(nodeSupplier);
    }

    record ControllerConfigRequest(Double cpuScaleUpThreshold,
                                   Double cpuScaleDownThreshold,
                                   Double callRateScaleUpThreshold,
                                   Long evaluationIntervalMs) {}

    /// Response for `GET /api/controller/decisions` (#425). `clusterCpuContext` is the cluster-average
    /// CPU usage surfaced as honest node-capacity context (never acted on by the autoscaler); each
    /// entry in `decisions` is the latest per-artifact decision snapshot.
    record ControllerDecisionsResponse(double clusterCpuContext, List<ScalingDecisionView> decisions) {
        static ControllerDecisionsResponse controllerDecisionsResponse(double clusterCpuContext,
                                                                       List<ScalingDecisionView> decisions) {
            return new ControllerDecisionsResponse(clusterCpuContext, decisions);
        }
    }

    record ScalingDecisionView(String artifact,
                               String outcome,
                               String guard,
                               double loadFactor,
                               int currentInstances,
                               int requestedInstances,
                               int cappedInstances,
                               long atMs) {
        static ScalingDecisionView scalingDecisionView(String artifact,
                                                       String outcome,
                                                       String guard,
                                                       double loadFactor,
                                                       int currentInstances,
                                                       int requestedInstances,
                                                       int cappedInstances,
                                                       long atMs) {
            return new ScalingDecisionView(artifact,
                                           outcome,
                                           guard,
                                           loadFactor,
                                           currentInstances,
                                           requestedInstances,
                                           cappedInstances,
                                           atMs);
        }
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ControllerConfig> route(ManagementRoute.CONTROLLER_CONFIG_GET).toJson(this::buildControllerConfigResponse),
                         ManagementRoutes.<ControllerStatusResponse> route(ManagementRoute.CONTROLLER_STATUS).toJson(this::buildControllerStatusResponse),
                         ManagementRoutes.<ControllerDecisionsResponse> route(ManagementRoute.CONTROLLER_DECISIONS).toJson(this::buildDecisionsResponse),
                         ManagementRoutes.<ControllerConfigUpdatedResponse> route(ManagementRoute.CONTROLLER_CONFIG_SET)
                                         .withBody(ControllerConfigRequest.class)
                                         .toJson(this::handleControllerConfig),
                         ManagementRoutes.<EvaluationTriggeredResponse> route(ManagementRoute.CONTROLLER_EVALUATE).toJson(() -> new EvaluationTriggeredResponse("evaluation_triggered")));
    }

    private ControllerDecisionsResponse buildDecisionsResponse() {
        var controlLoop = nodeSupplier.get().controlLoop();
        var decisions = controlLoop.scalingDecisions().values().stream().map(ControllerRoutes::toDecisionView).toList();

        return ControllerDecisionsResponse.controllerDecisionsResponse(controlLoop.clusterCpuContext(), decisions);
    }

    private static ScalingDecisionView toDecisionView(ScalingDecisionRecord decision) {
        return ScalingDecisionView.scalingDecisionView(decision.artifact().asString(),
                                                       decision.outcome().name(),
                                                       decision.guard().name(),
                                                       decision.loadFactor(),
                                                       decision.currentInstances(),
                                                       decision.requestedInstances(),
                                                       decision.cappedInstances(),
                                                       decision.atMs());
    }

    private Promise<ControllerConfigUpdatedResponse> handleControllerConfig(ControllerConfigRequest req) {
        var node = nodeSupplier.get();
        var currentConfig = node.controlLoop().configuration();

        return mergeConfig(req, currentConfig).async()
                          .withSuccess(node.controlLoop()::updateConfiguration)
                          .map(newConfig -> new ControllerConfigUpdatedResponse("updated", newConfig));
    }

    private static Result<ControllerConfig> mergeConfig(ControllerConfigRequest req, ControllerConfig current) {
        return ControllerConfig.controllerConfig(mergeDouble(option(req.cpuScaleUpThreshold()),
                                                             current.cpuScaleUpThreshold()),
                                                 mergeDouble(option(req.cpuScaleDownThreshold()),
                                                             current.cpuScaleDownThreshold()),
                                                 mergeDouble(option(req.callRateScaleUpThreshold()),
                                                             current.callRateScaleUpThreshold()),
                                                 mergeLong(option(req.evaluationIntervalMs()),
                                                           current.evaluationInterval().millis()));
    }

    private static double mergeDouble(Option<Double> requested, double current) {
        return requested.or(current);
    }

    private static long mergeLong(Option<Long> requested, long current) {
        return requested.or(current);
    }

    private ControllerConfig buildControllerConfigResponse() {
        return nodeSupplier.get()
                           .controlLoop()
                           .configuration();
    }

    private ControllerStatusResponse buildControllerStatusResponse() {
        var node = nodeSupplier.get();
        var config = node.controlLoop().configuration();

        return new ControllerStatusResponse(true,
                                            config.evaluationInterval().millis(),
                                            config);
    }
}
