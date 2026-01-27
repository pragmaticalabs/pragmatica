package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import java.util.regex.Pattern;

/**
 * Routes for alert management: thresholds, active alerts, history.
 */
public final class AlertRoutes implements RouteHandler {
    private static final Pattern METRIC_PATTERN = Pattern.compile("\"metric\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern WARNING_PATTERN = Pattern.compile("\"warning\"\\s*:\\s*([0-9.]+)");
    private static final Pattern CRITICAL_PATTERN = Pattern.compile("\"critical\"\\s*:\\s*([0-9.]+)");

    private final AlertManager alertManager;

    private AlertRoutes(AlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public static AlertRoutes alertRoutes(AlertManager alertManager) {
        return new AlertRoutes(alertManager);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        var path = ctx.path();
        var method = ctx.method();
        // GET endpoints
        if (method == HttpMethod.GET) {
            return switch (path) {
                case "/api/thresholds" -> {
                    response.ok(alertManager.thresholdsAsJson());
                    yield true;
                }
                case "/api/alerts" -> {
                    response.ok(buildAlertsResponse());
                    yield true;
                }
                case "/api/alerts/active" -> {
                    response.ok(alertManager.activeAlertsAsJson());
                    yield true;
                }
                case "/api/alerts/history" -> {
                    response.ok(alertManager.alertHistoryAsJson());
                    yield true;
                }
                default -> false;
            };
        }
        // POST endpoints
        if (method == HttpMethod.POST) {
            return switch (path) {
                case "/api/thresholds" -> {
                    handleSetThreshold(response, ctx.bodyAsString());
                    yield true;
                }
                case "/api/alerts/clear" -> {
                    alertManager.clearAlerts();
                    response.ok("{\"status\":\"alerts_cleared\"}");
                    yield true;
                }
                default -> false;
            };
        }
        // DELETE endpoints
        if (method == HttpMethod.DELETE && path.startsWith("/api/thresholds/")) {
            var metric = path.substring("/api/thresholds/".length());
            handleDeleteThreshold(response, metric);
            return true;
        }
        return false;
    }

    private void handleSetThreshold(ResponseWriter response, String body) {
        try{
            var metricMatch = METRIC_PATTERN.matcher(body);
            var warningMatch = WARNING_PATTERN.matcher(body);
            var criticalMatch = CRITICAL_PATTERN.matcher(body);
            if (!metricMatch.find() || !warningMatch.find() || !criticalMatch.find()) {
                response.badRequest("Missing metric, warning, or critical field");
                return;
            }
            var metric = metricMatch.group(1);
            var warning = Double.parseDouble(warningMatch.group(1));
            var critical = Double.parseDouble(criticalMatch.group(1));
            alertManager.setThreshold(metric, warning, critical)
                        .onSuccess(_ -> response.ok("{\"status\":\"threshold_set\",\"metric\":\"" + metric
                                                    + "\",\"warning\":" + warning + ",\"critical\":" + critical + "}"))
                        .onFailure(cause -> response.error(HttpStatus.INTERNAL_SERVER_ERROR,
                                                           "Failed to persist threshold: " + cause.message()));
        } catch (Exception e) {
            response.badRequest(e.getMessage());
        }
    }

    private void handleDeleteThreshold(ResponseWriter response, String metric) {
        if (metric.isEmpty()) {
            response.badRequest("Metric name required");
            return;
        }
        alertManager.removeThreshold(metric)
                    .onSuccess(_ -> response.ok("{\"status\":\"threshold_removed\",\"metric\":\"" + metric + "\"}"))
                    .onFailure(cause -> response.error(HttpStatus.INTERNAL_SERVER_ERROR,
                                                       "Failed to remove threshold: " + cause.message()));
    }

    private String buildAlertsResponse() {
        return "{\"active\":" + alertManager.activeAlertsAsJson() + ",\"history\":" + alertManager.alertHistoryAsJson()
               + "}";
    }
}
