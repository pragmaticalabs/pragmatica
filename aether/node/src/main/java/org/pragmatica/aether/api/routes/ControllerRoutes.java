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

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.lang.Option.option;

/**
 * Routes for controller management: config, status, TTM status.
 */
public final class ControllerRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;

    private ControllerRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ControllerRoutes controllerRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ControllerRoutes(nodeSupplier);
    }

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
                              .toJson(() -> new EvaluationTriggeredResponse("evaluation_triggered")));
    }

    private Promise<ControllerConfigUpdatedResponse> handleControllerConfig(ControllerConfigRequest req) {
        var node = nodeSupplier.get();
        var currentConfig = node.controller()
                                .configuration();
        return mergeConfig(req, currentConfig).async()
                          .withSuccess(node.controller()::updateConfiguration)
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
                           .controller()
                           .configuration();
    }

    private ControllerStatusResponse buildControllerStatusResponse() {
        var node = nodeSupplier.get();
        var config = node.controller()
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
                                     forecast.or((TtmForecast) null));
    }

    private TtmForecast toTtmForecast(TTMForecast f) {
        return new TtmForecast(f.timestamp(),
                               f.confidence(),
                               f.recommendation()
                                .getClass()
                                .getSimpleName());
    }
}
