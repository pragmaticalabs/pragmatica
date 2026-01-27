package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.LoadGenerator;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadConfigResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadConfigUploadResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadControlResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadRunnerStatusResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadRunnerTargetInfo;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadTargetInfo;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RampLoadResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RateSetResponse;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import static org.pragmatica.http.routing.PathParameter.aInteger;
import static org.pragmatica.http.routing.Route.get;
import static org.pragmatica.http.routing.Route.in;
import static org.pragmatica.http.routing.Route.post;

/**
 * REST API routes for load testing control.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Configuration management (get/upload TOML config)</li>
 *   <li>Load control (start/stop/pause/resume)</li>
 *   <li>Rate adjustment (ramp, set rate)</li>
 *   <li>Status monitoring (per-target metrics)</li>
 * </ul>
 */
public sealed interface LoadRoutes {
    /**
     * Request body for ramp operation.
     */
    record RampRequest(int targetRate, long durationMs) {}

    /**
     * Create route source for all load-related endpoints.
     */
    static RouteSource loadRoutes(LoadGenerator loadGenerator, ConfigurableLoadRunner loadRunner) {
        return in("/api/load")
        .serve(getConfigRoute(loadRunner),
               postConfigRoute(loadRunner),
               getStatusRoute(loadRunner),
               startRoute(loadRunner),
               stopRoute(loadRunner),
               pauseRoute(loadRunner),
               resumeRoute(loadRunner),
               rampRoute(loadGenerator),
               setRateRoute(loadGenerator));
    }

    // ========== Route Definitions ==========
    private static Route<LoadConfigResponse> getConfigRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadConfigResponse> get("/config")
                    .toJson(() -> getConfig(runner));
    }

    private static Route<LoadConfigUploadResponse> postConfigRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadConfigUploadResponse> post("/config")
                    .withBody(TypeToken.of(String.class))
                    .toJson(toml -> uploadConfig(runner, toml));
    }

    private static Route<LoadRunnerStatusResponse> getStatusRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadRunnerStatusResponse> get("/status")
                    .toJson(() -> getStatus(runner));
    }

    private static Route<LoadControlResponse> startRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadControlResponse> post("/start")
                    .toJson(_ -> start(runner));
    }

    private static Route<LoadControlResponse> stopRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadControlResponse> post("/stop")
                    .toJson(_ -> stop(runner));
    }

    private static Route<LoadControlResponse> pauseRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadControlResponse> post("/pause")
                    .toJson(_ -> pause(runner));
    }

    private static Route<LoadControlResponse> resumeRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadControlResponse> post("/resume")
                    .toJson(_ -> resume(runner));
    }

    private static Route<RampLoadResponse> rampRoute(LoadGenerator loadGenerator) {
        return Route.<RampLoadResponse> post("/ramp")
                    .withBody(TypeToken.of(RampRequest.class))
                    .toJson(req -> ramp(loadGenerator, req));
    }

    private static Route<RateSetResponse> setRateRoute(LoadGenerator loadGenerator) {
        return Route.<RateSetResponse> post("/set")
                    .withPath(aInteger())
                    .to(rate -> setRate(loadGenerator, rate))
                    .asJson();
    }

    // ========== Handler Methods ==========
    private static LoadConfigResponse getConfig(ConfigurableLoadRunner runner) {
        var loadConfig = runner.config();
        var targetInfos = loadConfig.targets()
                                    .stream()
                                    .map(t -> new LoadTargetInfo(t.name()
                                                                  .or(t.target()),
                                                                 t.target(),
                                                                 t.rate()
                                                                  .requestsPerSecond() + "/s",
                                                                 t.duration()
                                                                  .map(Object::toString)
                                                                  .or((String) null)))
                                    .toList();
        return new LoadConfigResponse(loadConfig.targets()
                                                .size(),
                                      loadConfig.totalRequestsPerSecond(),
                                      targetInfos);
    }

    private static Promise<LoadConfigUploadResponse> uploadConfig(ConfigurableLoadRunner runner, String toml) {
        return runner.loadConfigFromString(toml)
                     .async()
                     .map(config -> new LoadConfigUploadResponse(true,
                                                                 config.targets()
                                                                       .size(),
                                                                 config.totalRequestsPerSecond()));
    }

    private static LoadRunnerStatusResponse getStatus(ConfigurableLoadRunner runner) {
        var state = runner.state();
        var targetMetrics = runner.allTargetMetrics();
        var targetInfos = targetMetrics.values()
                                       .stream()
                                       .map(m -> new LoadRunnerTargetInfo(m.name(),
                                                                          m.targetRate(),
                                                                          m.actualRate(),
                                                                          m.totalRequests(),
                                                                          m.successCount(),
                                                                          m.failureCount(),
                                                                          m.avgLatencyMs(),
                                                                          m.successRate(),
                                                                          m.remainingDuration()
                                                                           .map(Object::toString)))
                                       .toList();
        return new LoadRunnerStatusResponse(state.name(), targetInfos.size(), targetInfos);
    }

    private static Promise<LoadControlResponse> start(ConfigurableLoadRunner runner) {
        return runner.start()
                     .async()
                     .map(state -> new LoadControlResponse(true,
                                                           state.name()));
    }

    private static Promise<LoadControlResponse> stop(ConfigurableLoadRunner runner) {
        runner.stop();
        return Promise.success(new LoadControlResponse(true, "IDLE"));
    }

    private static Promise<LoadControlResponse> pause(ConfigurableLoadRunner runner) {
        runner.pause();
        return Promise.success(new LoadControlResponse(true,
                                                       runner.state()
                                                             .name()));
    }

    private static Promise<LoadControlResponse> resume(ConfigurableLoadRunner runner) {
        runner.resume();
        return Promise.success(new LoadControlResponse(true,
                                                       runner.state()
                                                             .name()));
    }

    private static Promise<RampLoadResponse> ramp(LoadGenerator loadGenerator, RampRequest request) {
        loadGenerator.rampUp(request.targetRate(), request.durationMs());
        return Promise.success(new RampLoadResponse(true, request.targetRate(), request.durationMs()));
    }

    private static Promise<RateSetResponse> setRate(LoadGenerator loadGenerator, int rate) {
        loadGenerator.setRate(rate);
        return Promise.success(new RateSetResponse(true, rate));
    }

    record unused() implements LoadRoutes {}
}
