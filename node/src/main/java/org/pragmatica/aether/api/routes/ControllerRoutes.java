package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Routes for controller management: config, status, TTM status.
 */
public final class ControllerRoutes implements RouteHandler {
    private static final Pattern CPU_UP_PATTERN = Pattern.compile("\"cpuScaleUpThreshold\"\\s*:\\s*([0-9.]+)");
    private static final Pattern CPU_DOWN_PATTERN = Pattern.compile("\"cpuScaleDownThreshold\"\\s*:\\s*([0-9.]+)");
    private static final Pattern CALL_RATE_PATTERN = Pattern.compile("\"callRateScaleUpThreshold\"\\s*:\\s*([0-9.]+)");
    private static final Pattern INTERVAL_PATTERN = Pattern.compile("\"evaluationIntervalMs\"\\s*:\\s*(\\d+)");

    private final Supplier<AetherNode> nodeSupplier;

    private ControllerRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ControllerRoutes controllerRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ControllerRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        // GET endpoints
        if (method == HttpMethod.GET) {
            return switch (path) {
                case "/api/controller/config" -> {
                    response.ok(buildControllerConfigResponse());
                    yield true;
                }
                case "/api/controller/status" -> {
                    response.ok(buildControllerStatusResponse());
                    yield true;
                }
                case "/api/ttm/status" -> {
                    response.ok(buildTtmStatusResponse());
                    yield true;
                }
                default -> false;
            };
        }
        // POST endpoints
        if (method == HttpMethod.POST) {
            return switch (path) {
                case "/api/controller/config" -> {
                    handleControllerConfig(response, ctx.bodyAsString());
                    yield true;
                }
                case "/api/controller/evaluate" -> {
                    response.ok("{\"status\":\"evaluation_triggered\"}");
                    yield true;
                }
                default -> false;
            };
        }
        return false;
    }

    private void handleControllerConfig(ResponseWriter response, String body) {
        var node = nodeSupplier.get();
        var cpuUpMatch = CPU_UP_PATTERN.matcher(body);
        var cpuDownMatch = CPU_DOWN_PATTERN.matcher(body);
        var callRateMatch = CALL_RATE_PATTERN.matcher(body);
        var intervalMatch = INTERVAL_PATTERN.matcher(body);
        var currentConfig = node.controller()
                                .configuration();
        ControllerConfig.controllerConfig(cpuUpMatch.find()
                                          ? Double.parseDouble(cpuUpMatch.group(1))
                                          : currentConfig.cpuScaleUpThreshold(),
                                          cpuDownMatch.find()
                                          ? Double.parseDouble(cpuDownMatch.group(1))
                                          : currentConfig.cpuScaleDownThreshold(),
                                          callRateMatch.find()
                                          ? Double.parseDouble(callRateMatch.group(1))
                                          : currentConfig.callRateScaleUpThreshold(),
                                          intervalMatch.find()
                                          ? Long.parseLong(intervalMatch.group(1))
                                          : currentConfig.evaluationIntervalMs())
                        .onSuccess(newConfig -> {
                                       node.controller()
                                           .updateConfiguration(newConfig);
                                       response.ok("{\"status\":\"updated\",\"config\":" + newConfig.toJson() + "}");
                                   })
                        .onFailure(cause -> response.badRequest(cause.message()));
    }

    private String buildControllerConfigResponse() {
        var node = nodeSupplier.get();
        return node.controller()
                   .configuration()
                   .toJson();
    }

    private String buildControllerStatusResponse() {
        var node = nodeSupplier.get();
        var config = node.controller()
                         .configuration();
        return "{\"enabled\":true," + "\"evaluationIntervalMs\":" + config.evaluationIntervalMs() + "," + "\"config\":" + config.toJson()
               + "}";
    }

    private String buildTtmStatusResponse() {
        var node = nodeSupplier.get();
        var ttm = node.ttmManager();
        var config = ttm.config();
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"enabled\":")
          .append(config.enabled())
          .append(",");
        sb.append("\"active\":")
          .append(ttm.isEnabled())
          .append(",");
        sb.append("\"state\":\"")
          .append(ttm.state()
                     .name())
          .append("\",");
        sb.append("\"modelPath\":\"")
          .append(config.modelPath())
          .append("\",");
        sb.append("\"inputWindowMinutes\":")
          .append(config.inputWindowMinutes())
          .append(",");
        sb.append("\"evaluationIntervalMs\":")
          .append(config.evaluationIntervalMs())
          .append(",");
        sb.append("\"confidenceThreshold\":")
          .append(config.confidenceThreshold())
          .append(",");
        sb.append("\"hasForecast\":")
          .append(ttm.currentForecast()
                     .isPresent());
        ttm.currentForecast()
           .onPresent(forecast -> {
                          sb.append(",\"lastForecast\":{");
                          sb.append("\"timestamp\":")
                            .append(forecast.timestamp())
                            .append(",");
                          sb.append("\"confidence\":")
                            .append(forecast.confidence())
                            .append(",");
                          sb.append("\"recommendation\":\"")
                            .append(forecast.recommendation()
                                            .getClass()
                                            .getSimpleName())
                            .append("\"}");
                      });
        sb.append("}");
        return sb.toString();
    }
}
