package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ObservabilityDepthRegistry;
import org.pragmatica.aether.invoke.InvocationNode;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.InvocationTraceStore.TraceStats;
import org.pragmatica.aether.invoke.ObservabilityConfig;
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

/// Routes for observability: distributed traces and per-method depth configuration.
public final class ObservabilityRoutes implements RouteSource {
    private static final int DEFAULT_LIMIT = 100;

    private final ObservabilityDepthRegistry depthRegistry;
    private final InvocationTraceStore traceStore;

    private ObservabilityRoutes(ObservabilityDepthRegistry depthRegistry, InvocationTraceStore traceStore) {
        this.depthRegistry = depthRegistry;
        this.traceStore = traceStore;
    }

    public static ObservabilityRoutes observabilityRoutes(ObservabilityDepthRegistry depthRegistry,
                                                          InvocationTraceStore traceStore) {
        return new ObservabilityRoutes(depthRegistry, traceStore);
    }

    // Request DTO for depth configuration
    record SetDepthRequest(String artifact, String method, int depthThreshold) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<Object> get("/api/traces")
                              .withQuery(QueryParameter.aString("limit"),
                                         QueryParameter.aString("method"),
                                         QueryParameter.aString("status"),
                                         QueryParameter.aString("minDepth"),
                                         QueryParameter.aString("maxDepth"))
                              .toValue(this::handleQueryTraces)
                              .asJson(),
                         Route.<TraceStats> get("/api/traces/stats")
                              .toJson(this::handleTraceStats),
                         Route.<Object> get("/api/traces")
                              .withPath(aString())
                              .to(this::handleTraceByRequestId)
                              .asJson(),
                         Route.<Object> get("/api/observability/depth")
                              .toJson(this::handleGetDepthConfigs),
                         Route.<Object> post("/api/observability/depth")
                              .withBody(SetDepthRequest.class)
                              .toJson(this::handleSetDepth),
                         Route.<Object> delete("/api/observability/depth")
                              .withPath(aString(), aString())
                              .to(this::handleDeleteDepth)
                              .asJson());
    }

    private Object handleQueryTraces(Option<String> limitOpt,
                                     Option<String> methodOpt,
                                     Option<String> statusOpt,
                                     Option<String> minDepthOpt,
                                     Option<String> maxDepthOpt) {
        var limit = limitOpt.map(ObservabilityRoutes::parseIntOrDefault)
                            .or(DEFAULT_LIMIT);
        var traces = traceStore.query(node -> matchesTraceFilters(node, methodOpt, statusOpt, minDepthOpt, maxDepthOpt),
                                      limit);
        return tracesAsJson(traces);
    }

    private Promise<Object> handleTraceByRequestId(String requestId) {
        if (requestId.isEmpty()) {
            return ObservabilityError.REQUEST_ID_REQUIRED.promise();
        }
        var traces = traceStore.forRequest(requestId);
        return Promise.success(tracesAsJson(traces));
    }

    private TraceStats handleTraceStats() {
        return traceStore.stats();
    }

    private Object handleGetDepthConfigs() {
        return depthConfigsAsJson(depthRegistry.allConfigs());
    }

    private Promise<Object> handleSetDepth(SetDepthRequest req) {
        return validateSetDepthRequest(req).async()
                                      .flatMap(valid -> depthRegistry.setConfig(valid.artifact(),
                                                                                valid.method(),
                                                                                valid.depthThreshold())
                                                                     .map(_ -> depthSetResponseAsJson(valid)));
    }

    private Promise<Object> handleDeleteDepth(String artifact, String method) {
        if (artifact.isEmpty() || method.isEmpty()) {
            return ObservabilityError.KEY_REQUIRED.promise();
        }
        return depthRegistry.removeConfig(artifact, method)
                            .map(_ -> depthRemovedResponseAsJson(artifact, method));
    }

    // --- Validation ---
    private static Result<SetDepthRequest> validateSetDepthRequest(SetDepthRequest req) {
        if (req.artifact() == null || req.artifact()
                                         .isEmpty()) {
            return ObservabilityError.MISSING_FIELDS.result();
        }
        if (req.method() == null || req.method()
                                       .isEmpty()) {
            return ObservabilityError.MISSING_FIELDS.result();
        }
        if (req.depthThreshold() < 0) {
            return ObservabilityError.INVALID_DEPTH.result();
        }
        return Result.success(req);
    }

    // --- Filter matching ---
    private static boolean matchesTraceFilters(InvocationNode node,
                                               Option<String> methodOpt,
                                               Option<String> statusOpt,
                                               Option<String> minDepthOpt,
                                               Option<String> maxDepthOpt) {
        var matchesMethod = methodOpt.map(m -> node.callee()
                                                   .contains(m))
                                     .or(true);
        var matchesStatus = statusOpt.map(s -> node.outcome()
                                                   .name()
                                                   .equals(s))
                                     .or(true);
        var matchesMinDepth = minDepthOpt.map(d -> node.depth() >= parseIntOrDefault(d))
                                         .or(true);
        var matchesMaxDepth = maxDepthOpt.map(d -> node.depth() <= parseIntOrDefault(d))
                                         .or(true);
        return matchesMethod && matchesStatus && matchesMinDepth && matchesMaxDepth;
    }

    private static int parseIntOrDefault(String value) {
        try{
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
            return DEFAULT_LIMIT;
        }
    }

    // --- Manual JSON serialization ---
    @SuppressWarnings("JBCT-PAT-01")
    private static String tracesAsJson(List<InvocationNode> traces) {
        var sb = new StringBuilder(traces.size() * 256);
        sb.append("[");
        var first = true;
        for (var node : traces) {
            if (!first) sb.append(",");
            appendTraceNodeJson(sb, node);
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void appendTraceNodeJson(StringBuilder sb, InvocationNode node) {
        sb.append("{\"requestId\":\"")
          .append(escapeJson(node.requestId()))
          .append("\"");
        sb.append(",\"depth\":")
          .append(node.depth());
        sb.append(",\"timestamp\":\"")
          .append(node.timestamp())
          .append("\"");
        sb.append(",\"nodeId\":\"")
          .append(escapeJson(node.nodeId()))
          .append("\"");
        sb.append(",\"caller\":\"")
          .append(escapeJson(node.caller()))
          .append("\"");
        sb.append(",\"callee\":\"")
          .append(escapeJson(node.callee()))
          .append("\"");
        sb.append(",\"durationNs\":")
          .append(node.durationNs());
        sb.append(",\"durationMs\":")
          .append(node.durationMs());
        sb.append(",\"outcome\":\"")
          .append(node.outcome()
                      .name())
          .append("\"");
        node.errorMessage()
            .onPresent(msg -> sb.append(",\"errorMessage\":\"")
                                .append(escapeJson(msg))
                                .append("\""));
        sb.append(",\"local\":")
          .append(node.local());
        sb.append(",\"hops\":")
          .append(node.hops());
        sb.append("}");
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static String depthConfigsAsJson(Map<String, ObservabilityConfig> configs) {
        var sb = new StringBuilder(configs.size() * 128);
        sb.append("[");
        var first = true;
        for (var entry : configs.entrySet()) {
            if (!first) sb.append(",");
            sb.append("{\"key\":\"")
              .append(escapeJson(entry.getKey()))
              .append("\"");
            sb.append(",\"depthThreshold\":")
              .append(entry.getValue()
                           .depthThreshold());
            sb.append(",\"targetTracesPerSec\":")
              .append(entry.getValue()
                           .targetTracesPerSec());
            sb.append("}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static Object depthSetResponseAsJson(SetDepthRequest req) {
        return "{\"status\":\"depth_set\"" + ",\"artifact\":\"" + escapeJson(req.artifact()) + "\"" + ",\"method\":\"" + escapeJson(req.method())
               + "\"" + ",\"depthThreshold\":" + req.depthThreshold() + "}";
    }

    private static Object depthRemovedResponseAsJson(String artifact, String method) {
        return "{\"status\":\"depth_removed\"" + ",\"artifact\":\"" + escapeJson(artifact) + "\"" + ",\"method\":\"" + escapeJson(method)
               + "\"" + "}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
    }

    // --- Error causes ---
    private enum ObservabilityError implements Cause {
        MISSING_FIELDS("Missing artifact, method, or depthThreshold field"),
        INVALID_DEPTH("Depth threshold must be non-negative"),
        REQUEST_ID_REQUIRED("Request ID required"),
        KEY_REQUIRED("Depth config key required in format: artifactBase/methodName");
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
