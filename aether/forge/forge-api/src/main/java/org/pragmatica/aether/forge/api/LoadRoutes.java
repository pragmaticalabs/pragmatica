package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadConfigResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadConfigUploadResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadControlResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadRunnerStatusResponse;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadRunnerTargetInfo;
import org.pragmatica.aether.forge.api.ForgeApiResponses.LoadTargetInfo;
import org.pragmatica.aether.forge.api.ForgeApiResponses.RateSetResponse;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.PathParameter.aInteger;
import static org.pragmatica.http.routing.Route.in;

/// REST API routes for load testing control.
///
/// Provides endpoints for:
///
///   - Configuration management (get/upload TOML config)
///   - Load control (start/stop/pause/resume)
///   - Rate adjustment (set total rate)
///   - Status monitoring (per-target metrics)
///
public sealed interface LoadRoutes {
    /// Create route source for all load-related endpoints.
    static RouteSource loadRoutes(ConfigurableLoadRunner loadRunner) {
        return in("/api/load")
        .serve(getConfigRoute(loadRunner),
               postConfigRoute(loadRunner),
               getStatusRoute(loadRunner),
               startRoute(loadRunner),
               stopRoute(loadRunner),
               pauseRoute(loadRunner),
               resumeRoute(loadRunner),
               setTotalRateRoute(loadRunner));
    }

    // ========== Route Definitions ==========
    private static Route<LoadConfigResponse> getConfigRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadConfigResponse> get("/config")
                    .toJson(() -> getConfig(runner));
    }

    private static Route<LoadConfigUploadResponse> postConfigRoute(ConfigurableLoadRunner runner) {
        return Route.<LoadConfigUploadResponse> post("/config")
                    .to(ctx -> uploadConfig(runner,
                                            ctx.bodyAsString()))
                    .asJson();
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

    // ========== Handler Methods ==========
    private static LoadConfigResponse getConfig(ConfigurableLoadRunner runner) {
        var loadConfig = runner.config();
        var targetInfos = loadConfig.targets()
                                    .stream()
                                    .map(LoadRoutes::toLoadTargetInfo)
                                    .toList();
        return new LoadConfigResponse(loadConfig.targets()
                                                .size(),
                                      loadConfig.totalRequestsPerSecond(),
                                      targetInfos);
    }

    private static LoadTargetInfo toLoadTargetInfo(org.pragmatica.aether.forge.load.LoadTarget t) {
        return new LoadTargetInfo(t.name()
                                   .or(t.target()),
                                  t.target(),
                                  t.rate()
                                   .requestsPerSecond() + "/s",
                                  t.duration()
                                   .map(Object::toString)
                                   .fold(() -> null,
                                         s -> s));
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
                                                                           .map(ForgeApiResponses::formatDuration)))
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

    private static Route<RateSetResponse> setTotalRateRoute(ConfigurableLoadRunner loadRunner) {
        return Route.<RateSetResponse> post("/rate")
                    .withPath(aInteger())
                    .to(rate -> setTotalRate(loadRunner, rate))
                    .asJson();
    }

    private static Promise<RateSetResponse> setTotalRate(ConfigurableLoadRunner loadRunner, int rate) {
        loadRunner.setTotalRate(rate);
        return Promise.success(new RateSetResponse(true, rate));
    }

    record unused() implements LoadRoutes {}
}
