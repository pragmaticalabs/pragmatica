package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ControllerConfigUpdatedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ControllerStatusResponse;
import org.pragmatica.aether.api.ManagementApiResponses.EvaluationTriggeredResponse;
import org.pragmatica.aether.api.ManagementApiResponses.TtmForecast;
import org.pragmatica.aether.api.ManagementApiResponses.TtmStatusResponse;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.node.AetherNode;
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
    private final Supplier<AetherNode> nodeSupplier;

    private ControllerRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ControllerRoutes controllerRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ControllerRoutes(nodeSupplier);
    }

    // Training data export DTO
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
                             int eventCount) {}

    // Request DTO
    record ControllerConfigRequest(Double cpuScaleUpThreshold,
                                   Double cpuScaleDownThreshold,
                                   Double callRateScaleUpThreshold,
                                   Long evaluationIntervalMs) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<ControllerConfig> get("/api/controller/config")
                              .toJson(this::buildControllerConfigResponse),
                         Route.<ControllerStatusResponse> get("/api/controller/status")
                              .toJson(this::buildControllerStatusResponse),
                         Route.<TtmStatusResponse> get("/api/ttm/status")
                              .toJson(this::buildTtmStatusResponse),
                         Route.<ControllerConfigUpdatedResponse> post("/api/controller/config")
                              .withBody(ControllerConfigRequest.class)
                              .toJson(this::handleControllerConfig),
                         Route.<EvaluationTriggeredResponse> post("/api/controller/evaluate")
                              .toJson(() -> new EvaluationTriggeredResponse("evaluation_triggered")),
                         Route.<List<TrainingDataPoint>> get("/api/ttm/training-data")
                              .toJson(this::buildTrainingDataResponse));
    }

    private Promise<ControllerConfigUpdatedResponse> handleControllerConfig(ControllerConfigRequest req) {
        var node = nodeSupplier.get();
        var currentConfig = node.controlLoop()
                                .configuration();
        return mergeConfig(req, currentConfig).async()
                          .withSuccess(node.controlLoop()::updateConfiguration)
                          .map(newConfig -> new ControllerConfigUpdatedResponse("updated", newConfig));
    }

    private static Result<ControllerConfig> mergeConfig(ControllerConfigRequest req,
                                                        ControllerConfig current) {
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
        return nodeSupplier.get()
                           .controlLoop()
                           .configuration();
    }

    private ControllerStatusResponse buildControllerStatusResponse() {
        var node = nodeSupplier.get();
        var config = node.controlLoop()
                         .configuration();
        return new ControllerStatusResponse(true, config.evaluationIntervalMs(), config);
    }

    private TtmStatusResponse buildTtmStatusResponse() {
        var node = nodeSupplier.get();
        var ttm = node.ttmManager();
        var config = ttm.config();
        var forecast = ttm.currentForecast()
                          .map(this::toTtmForecast);
        return new TtmStatusResponse(config.enabled(),
                                     ttm.isEnabled(),
                                     ttm.state()
                                        .name(),
                                     config.modelPath(),
                                     config.inputWindowMinutes(),
                                     config.evaluationIntervalMs(),
                                     config.confidenceThreshold(),
                                     forecast.isPresent(),
                                     forecast);
    }

    private TtmForecast toTtmForecast(TTMForecast f) {
        return new TtmForecast(f.timestamp(),
                               f.confidence(),
                               f.recommendation()
                                .getClass()
                                .getSimpleName());
    }

    private List<TrainingDataPoint> buildTrainingDataResponse() {
        return nodeSupplier.get()
                           .snapshotCollector()
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
