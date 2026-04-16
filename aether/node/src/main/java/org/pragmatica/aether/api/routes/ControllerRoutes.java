// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ControllerConfigUpdatedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ControllerStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.EvaluationTriggeredResponse;
import org.pragmatica.aether.api.ManagementApiResponses.TtmForecast;
import org.pragmatica.aether.api.ManagementApiResponses.TtmStatusResponse;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.ttm.model.TTMForecast;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.lang.Option.option;


/// Routes for controller management: config, status, TTM status.
public final class ControllerRoutes implements RouteSource {
    private final Supplier<ManageableNode> nodeSupplier;

    private ControllerRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ControllerRoutes controllerRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ControllerRoutes(nodeSupplier);
    }

    record TrainingDataPoint(long timestamp,
                             double cpuUsage,
                             double heapUsage,
                             double eventLoopLagMs,
                             double latencyMs,
                             long invocations,
                             long gcPauseMs,
                             double latencyP50,
                             double latencyP95,
                             double latencyP99,
                             double errorRate,
                             int eventCount){}

    record ControllerConfigRequest(Double cpuScaleUpThreshold,
                                   Double cpuScaleDownThreshold,
                                   Double callRateScaleUpThreshold,
                                   Long evaluationIntervalMs){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ControllerConfig>route(ManagementRoute.CONTROLLER_CONFIG_GET)
                                         .toJson(this::buildControllerConfigResponse),
                         ManagementRoutes.<ControllerStatusResponse>route(ManagementRoute.CONTROLLER_STATUS)
                                         .toJson(this::buildControllerStatusResponse),
                         ManagementRoutes.<TtmStatusResponse>route(ManagementRoute.TTM_STATUS)
                                         .toJson(this::buildTtmStatusResponse),
                         ManagementRoutes.<ControllerConfigUpdatedResponse>route(ManagementRoute.CONTROLLER_CONFIG_SET)
                                         .withBody(ControllerConfigRequest.class)
                                         .toJson(this::handleControllerConfig),
                         ManagementRoutes.<EvaluationTriggeredResponse>route(ManagementRoute.CONTROLLER_EVALUATE)
                                         .toJson(() -> new EvaluationTriggeredResponse("evaluation_triggered")),
                         ManagementRoutes.<List<TrainingDataPoint>>route(ManagementRoute.TTM_TRAINING_DATA)
                                         .toJson(this::buildTrainingDataResponse));
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
                                                           current.evaluationIntervalMs()));
    }

    private static double mergeDouble(Option<Double> requested, double current) {
        return requested.or(current);
    }

    private static long mergeLong(Option<Long> requested, long current) {
        return requested.or(current);
    }

    private ControllerConfig buildControllerConfigResponse() {
        return nodeSupplier.get().controlLoop()
                               .configuration();
    }

    private ControllerStatusResponse buildControllerStatusResponse() {
        var node = nodeSupplier.get();
        var config = node.controlLoop().configuration();
        return new ControllerStatusResponse(true, config.evaluationIntervalMs(), config);
    }

    private TtmStatusResponse buildTtmStatusResponse() {
        var node = nodeSupplier.get();
        var ttm = node.ttmManager();
        var config = ttm.config();
        var forecast = ttm.currentForecast().map(this::toTtmForecast);
        return new TtmStatusResponse(config.enabled(),
                                     ttm.isEnabled(),
                                     ttm.state().name(),
                                     config.modelPath(),
                                     config.inputWindowMinutes(),
                                     config.evaluationInterval().millis(),
                                     config.confidenceThreshold(),
                                     forecast.isPresent(),
                                     forecast);
    }

    private TtmForecast toTtmForecast(TTMForecast f) {
        return new TtmForecast(f.timestamp(),
                               f.confidence(),
                               f.recommendation().getClass()
                                               .getSimpleName());
    }

    private List<TrainingDataPoint> buildTrainingDataResponse() {
        return nodeSupplier.get().snapshotCollector()
                               .minuteAggregator()
                               .recent(120)
                               .stream()
                               .map(ControllerRoutes::toTrainingDataPoint)
                               .toList();
    }

    private static TrainingDataPoint toTrainingDataPoint(org.pragmatica.aether.metrics.MinuteAggregate agg) {
        return new TrainingDataPoint(agg.minuteTimestamp(),
                                     agg.avgCpuUsage(),
                                     agg.avgHeapUsage(),
                                     agg.avgEventLoopLagMs(),
                                     agg.avgLatencyMs(),
                                     agg.totalInvocations(),
                                     agg.totalGcPauseMs(),
                                     agg.latencyP50(),
                                     agg.latencyP95(),
                                     agg.latencyP99(),
                                     agg.errorRate(),
                                     agg.eventCount());
    }
}
