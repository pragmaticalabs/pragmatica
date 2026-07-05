// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.TraceInjectResponse;
import org.pragmatica.aether.api.ObservabilityConfigRegistry;
import org.pragmatica.aether.api.ObservabilityConfigRegistry.EffectiveEntry;
import org.pragmatica.aether.api.ObservabilityConfigRegistry.ObservabilityState;
import org.pragmatica.aether.invoke.InvocationNode;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.InvocationTraceStore.TraceStats;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.AspectObservabilityConfig;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;


public final class ObservabilityRoutes implements RouteSource {
    private static final int DEFAULT_LIMIT = 100;

    private final ObservabilityConfigRegistry configRegistry;
    private final InvocationTraceStore traceStore;

    private ObservabilityRoutes(ObservabilityConfigRegistry configRegistry, InvocationTraceStore traceStore) {
        this.configRegistry = configRegistry;
        this.traceStore = traceStore;
    }

    public static ObservabilityRoutes observabilityRoutes(ObservabilityConfigRegistry configRegistry,
                                                          InvocationTraceStore traceStore) {
        return new ObservabilityRoutes(configRegistry, traceStore);
    }

    record SetDepthRequest(String artifact, String method, int depthThreshold) {}

    record InjectTraceRequest(String operation, Long durationMs, Integer depth, String requestId, String traceId) {}

    /// Public view record for `/api/traces*` endpoints. Replaces the previous pre-serialized
    /// JSON String pathway (`tracesAsJson(...)`): wrapping a String in an `Object`-typed
    /// route handler caused Jackson to double-encode the body as `"\"...\""` instead of an
    /// inlined JSON array — every integration test in `11-observability` asserting on
    /// `"durationMs":100`/`"depth":2`/etc. failed because Jackson escaped the quotes.
    /// Now the field list IS the wire shape; Jackson handles serialization end-to-end.
    public record TraceView(String requestId,
                            int depth,
                            String timestamp,
                            String nodeId,
                            String caller,
                            String callee,
                            long durationNs,
                            double durationMs,
                            String outcome,
                            String errorMessage,
                            boolean local,
                            int hops) {}

    public record DepthConfigView(String key, int depthThreshold, int targetTracesPerSec) {}

    public record DepthSetResponse(String status, String artifact, String method, int depthThreshold) {}

    public record DepthRemovedResponse(String status, String artifact, String method) {}

    /// Body for `POST /api/observability/config` — a whole config snapshot for a scope. `artifact` and
    /// `method` accept the wildcard `*` for the artifact (`base/*`) and global (`*/*`) scopes. Absent
    /// boolean fields deserialize to false (facet off); `depth` is the logging-ladder threshold.
    record SetConfigRequest(String artifact,
                            String method,
                            boolean logging,
                            boolean metrics,
                            boolean spans,
                            boolean tracing,
                            int depth) {}

    /// Effective observability posture for one injection point / config scope. `state` is
    /// `baseline` | `configured` | `darkened`; the facet fields + `depth` are the effective config
    /// (baseline-materialized for a baseline point, so the view always shows what actually runs);
    /// `invocationCount` is the responding node's live count, `null` when no live cell is registered.
    public record ObservabilityConfigView(String artifactBase,
                                          String methodName,
                                          String state,
                                          boolean logging,
                                          boolean metrics,
                                          boolean spans,
                                          boolean tracing,
                                          int depth,
                                          Long invocationCount) {}

    public record ConfigSetResponse(String status,
                                    String artifact,
                                    String method,
                                    boolean logging,
                                    boolean metrics,
                                    boolean spans,
                                    boolean tracing,
                                    int depth) {}

    public record ConfigRemovedResponse(String status, String artifact, String method) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<List<TraceView>> route(ManagementRoute.TRACES_QUERY)
                                         .withQuery(QueryParameter.aString("limit"),
                                                    QueryParameter.aString("method"),
                                                    QueryParameter.aString("status"),
                                                    QueryParameter.aString("minDepth"),
                                                    QueryParameter.aString("maxDepth"))
                                         .to(this::handleQueryTraces)
                                         .asJson(),
                         ManagementRoutes.<TraceStats> route(ManagementRoute.TRACES_STATS).toJson(this::handleTraceStats),
                         ManagementRoutes.<TraceInjectResponse> route(ManagementRoute.TRACES_INJECT)
                                         .withBody(InjectTraceRequest.class)
                                         .toJson(this::handleInjectTrace),
                         ManagementRoutes.<List<TraceView>> route(ManagementRoute.TRACE_BY_REQUEST_ID)
                                         .withPath(aString())
                                         .to(this::handleTraceByRequestId)
                                         .asJson(),
                         ManagementRoutes.<List<DepthConfigView>> route(ManagementRoute.OBSERVABILITY_DEPTH_GET).toJson(this::handleGetDepthConfigs),
                         ManagementRoutes.<DepthSetResponse> route(ManagementRoute.OBSERVABILITY_DEPTH_SET)
                                         .withBody(SetDepthRequest.class)
                                         .toJson(this::handleSetDepth),
                         ManagementRoutes.<DepthRemovedResponse> route(ManagementRoute.OBSERVABILITY_DEPTH_DELETE)
                                         .withPath(aString(),
                                                   aString())
                                         .to(this::handleDeleteDepth)
                                         .asJson(),
                         ManagementRoutes.<List<ObservabilityConfigView>> route(ManagementRoute.OBSERVABILITY_CONFIG_GET).toJson(this::handleListConfigs),
                         ManagementRoutes.<ObservabilityConfigView> route(ManagementRoute.OBSERVABILITY_CONFIG_GET_ONE)
                                         .withPath(aString(),
                                                   aString())
                                         .toValue(this::handleGetConfig)
                                         .asJson(),
                         ManagementRoutes.<ConfigSetResponse> route(ManagementRoute.OBSERVABILITY_CONFIG_SET)
                                         .withBody(SetConfigRequest.class)
                                         .toJson(this::handleSetConfig),
                         ManagementRoutes.<ConfigRemovedResponse> route(ManagementRoute.OBSERVABILITY_CONFIG_DELETE)
                                         .withPath(aString(),
                                                   aString())
                                         .to(this::handleDeleteConfig)
                                         .asJson());
    }

    private Promise<TraceInjectResponse> handleInjectTrace(InjectTraceRequest req) {
        return traceStore.inject(req.operation(),
                                 Option.option(req.durationMs()),
                                 Option.option(req.depth()),
                                 Option.option(req.requestId()),
                                 Option.option(req.traceId())).map(ObservabilityRoutes::toTraceInjectResponse)
                                .async();
    }

    private static TraceInjectResponse toTraceInjectResponse(InvocationNode node) {
        var durationMs = node.durationNs() / 1_000_000L;

        return new TraceInjectResponse(node.requestId(),
                                       node.requestId(),
                                       node.callee(),
                                       durationMs,
                                       node.depth(),
                                       node.timestamp().toString());
    }

    private Promise<List<TraceView>> handleQueryTraces(Option<String> limitOpt,
                                                       Option<String> methodOpt,
                                                       Option<String> statusOpt,
                                                       Option<String> minDepthOpt,
                                                       Option<String> maxDepthOpt) {
        var limit = limitOpt.map(ObservabilityRoutes::parseIntOrDefault).or(DEFAULT_LIMIT);

        return traceStore.query(node -> matchesTraceFilters(node, methodOpt, statusOpt, minDepthOpt, maxDepthOpt),
                                limit)
                         .map(ObservabilityRoutes::toTraceViews);
    }

    private Promise<List<TraceView>> handleTraceByRequestId(String requestId) {
        if (requestId.isEmpty()) {
            return ObservabilityError.REQUEST_ID_REQUIRED.promise();
        }

        return traceStore.forRequest(requestId)
                         .map(ObservabilityRoutes::toTraceViews);
    }

    private TraceStats handleTraceStats() {
        return traceStore.stats();
    }

    private List<DepthConfigView> handleGetDepthConfigs() {
        return toDepthConfigViews(configRegistry.allConfigs());
    }

    private Promise<DepthSetResponse> handleSetDepth(SetDepthRequest req) {
        return validateSetDepthRequest(req).async()
                                      .flatMap(valid -> configRegistry.setDepth(valid.artifact(),
                                                                                valid.method(),
                                                                                valid.depthThreshold())
                                                                      .map(_ -> new DepthSetResponse("depth_set",
                                                                                                     valid.artifact(),
                                                                                                     valid.method(),
                                                                                                     valid.depthThreshold())));
    }

    private Promise<DepthRemovedResponse> handleDeleteDepth(String artifact, String method) {
        if (artifact.isEmpty() || method.isEmpty()) {
            return ObservabilityError.KEY_REQUIRED.promise();
        }

        return configRegistry.removeDepth(artifact, method)
                             .map(_ -> new DepthRemovedResponse("depth_removed", artifact, method));
    }

    private List<ObservabilityConfigView> handleListConfigs() {
        return configRegistry.allEffectiveStates()
                             .stream()
                             .map(ObservabilityRoutes::toConfigView)
                             .toList();
    }

    private ObservabilityConfigView handleGetConfig(String artifact, String method) {
        return toConfigView(configRegistry.effectiveEntry(artifact, method));
    }

    private Promise<ConfigSetResponse> handleSetConfig(SetConfigRequest req) {
        return validateSetConfigRequest(req).async()
                                      .flatMap(valid -> configRegistry.setConfig(valid.artifact(),
                                                                                 valid.method(),
                                                                                 valid.logging(),
                                                                                 valid.metrics(),
                                                                                 valid.spans(),
                                                                                 valid.tracing(),
                                                                                 valid.depth())
                                                                      .map(_ -> toConfigSetResponse(valid)));
    }

    private Promise<ConfigRemovedResponse> handleDeleteConfig(String artifact, String method) {
        if (artifact.isEmpty() || method.isEmpty()) {
            return ObservabilityError.CONFIG_KEY_REQUIRED.promise();
        }

        return configRegistry.removeConfig(artifact, method)
                             .map(_ -> new ConfigRemovedResponse("config_removed", artifact, method));
    }

    private static Result<SetConfigRequest> validateSetConfigRequest(SetConfigRequest req) {
        if (req.artifact() == null || req.artifact().isEmpty()) {
            return ObservabilityError.CONFIG_MISSING_FIELDS.result();
        }

        if (req.method() == null || req.method().isEmpty()) {
            return ObservabilityError.CONFIG_MISSING_FIELDS.result();
        }

        if (req.depth() < 0) {
            return ObservabilityError.INVALID_DEPTH.result();
        }

        return Result.success(req);
    }

    private static ConfigSetResponse toConfigSetResponse(SetConfigRequest req) {
        return new ConfigSetResponse("config_set",
                                     req.artifact(),
                                     req.method(),
                                     req.logging(),
                                     req.metrics(),
                                     req.spans(),
                                     req.tracing(),
                                     req.depth());
    }

    private static ObservabilityConfigView toConfigView(EffectiveEntry entry) {
        var config = entry.effectiveConfig();

        return new ObservabilityConfigView(entry.artifactBase(),
                                           entry.methodName(),
                                           stateLabel(entry.state()),
                                           config.logging(),
                                           config.metrics(),
                                           config.spans(),
                                           config.tracing(),
                                           config.depth(),
                                           entry.invocationCount().or((Long) null));
    }

    private static String stateLabel(ObservabilityState state) {
        return switch (state) {
            case ObservabilityState.Baseline ignored -> "baseline";
            case ObservabilityState.Configured ignored -> "configured";
            case ObservabilityState.Darkened ignored -> "darkened";
        };
    }

    private static Result<SetDepthRequest> validateSetDepthRequest(SetDepthRequest req) {
        if (req.artifact() == null || req.artifact().isEmpty()) {
            return ObservabilityError.MISSING_FIELDS.result();
        }

        if (req.method() == null || req.method().isEmpty()) {
            return ObservabilityError.MISSING_FIELDS.result();
        }

        if (req.depthThreshold() < 0) {
            return ObservabilityError.INVALID_DEPTH.result();
        }

        return Result.success(req);
    }

    private static boolean matchesTraceFilters(InvocationNode node,
                                               Option<String> methodOpt,
                                               Option<String> statusOpt,
                                               Option<String> minDepthOpt,
                                               Option<String> maxDepthOpt) {
        var matchesMethod = methodOpt.map(m -> node.callee()
                                                   .contains(m)).or(true);
        var matchesStatus = statusOpt.map(s -> node.outcome()
                                                   .name()
                                                   .equals(s)).or(true);
        var matchesMinDepth = minDepthOpt.map(d -> node.depth() >= parseIntOrDefault(d)).or(true);
        var matchesMaxDepth = maxDepthOpt.map(d -> node.depth() <= parseIntOrDefault(d)).or(true);

        return matchesMethod
               && matchesStatus
               && matchesMinDepth
               && matchesMaxDepth;
    }

    private static int parseIntOrDefault(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
            return DEFAULT_LIMIT;
        }
    }

    private static List<TraceView> toTraceViews(List<InvocationNode> traces) {
        return traces.stream()
                     .map(ObservabilityRoutes::toTraceView)
                     .toList();
    }

    private static TraceView toTraceView(InvocationNode node) {
        return new TraceView(node.requestId(),
                             node.depth(),
                             node.timestamp().toString(),
                             node.nodeId(),
                             node.caller(),
                             node.callee(),
                             node.durationNs(),
                             node.durationMs(),
                             node.outcome().name(),
                             node.errorMessage().or((String) null),
                             node.local(),
                             node.hops());
    }

    private static List<DepthConfigView> toDepthConfigViews(Map<String, AspectObservabilityConfig> configs) {
        return configs.entrySet()
                      .stream()
                      .map(entry -> new DepthConfigView(entry.getKey(),
                                                        entry.getValue().depth(),
                                                        ObservabilityConfig.DEFAULT.targetTracesPerSec()))
                      .toList();
    }

    private enum ObservabilityError implements Cause {
        MISSING_FIELDS("Missing artifact, method, or depthThreshold field"),
        INVALID_DEPTH("Depth threshold must be non-negative"),
        REQUEST_ID_REQUIRED("Request ID required"),
        KEY_REQUIRED("Depth config key required in format: artifactBase/methodName"),
        CONFIG_MISSING_FIELDS("Missing artifact or method field"),
        CONFIG_KEY_REQUIRED("Config key required in format: artifactBase/methodName");
        private final String message;
        ObservabilityError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
