package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ManagementApiResponses.AlertsClearedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AlertsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ThresholdRemovedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ThresholdSetResponse;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for alert management: thresholds, active alerts, history.
public final class AlertRoutes implements RouteSource {
    private final AlertManager alertManager;

    private AlertRoutes(AlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public static AlertRoutes alertRoutes(AlertManager alertManager) {
        return new AlertRoutes(alertManager);
    }

    // Request DTO
    record ThresholdRequest(String metric, Double warning, Double critical) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(// GET endpoints
        Route.<Object> get("/api/thresholds")
             .toJson(alertManager::thresholdsAsJson),
        Route.<AlertsResponse> get("/api/alerts")
             .toJson(this::buildAlertsResponse),
        Route.<Object> get("/api/alerts/active")
             .toJson(alertManager::activeAlertsAsJson),
        Route.<Object> get("/api/alerts/history")
             .toJson(alertManager::alertHistoryAsJson),
        // POST endpoints
        Route.<ThresholdSetResponse> post("/api/thresholds")
             .withBody(ThresholdRequest.class)
             .toJson(this::handleSetThreshold),
        Route.<AlertsClearedResponse> post("/api/alerts/clear")
             .toJson(this::handleClearAlerts),
        // DELETE with path parameter
        Route.<ThresholdRemovedResponse> delete("/api/thresholds")
             .withPath(aString())
             .to(this::handleDeleteThreshold)
             .asJson());
    }

    private Promise<ThresholdSetResponse> handleSetThreshold(ThresholdRequest req) {
        return validateThresholdRequest(req).async()
                                       .flatMap(valid -> alertManager.setThreshold(valid.metric(),
                                                                                   valid.warning(),
                                                                                   valid.critical())
                                                                     .map(_ -> new ThresholdSetResponse("threshold_set",
                                                                                                        valid.metric(),
                                                                                                        valid.warning(),
                                                                                                        valid.critical())));
    }

    private Result<ThresholdRequest> validateThresholdRequest(ThresholdRequest req) {
        if (req.metric() == null || req.metric()
                                       .isEmpty()) {
            return AlertError.MISSING_FIELDS.result();
        }
        if (req.warning() == null || req.critical() == null) {
            return AlertError.MISSING_FIELDS.result();
        }
        return Result.success(req);
    }

    private AlertsClearedResponse handleClearAlerts() {
        alertManager.clearAlerts();
        return new AlertsClearedResponse("alerts_cleared");
    }

    private Promise<ThresholdRemovedResponse> handleDeleteThreshold(String metric) {
        if (metric.isEmpty()) {
            return AlertError.METRIC_REQUIRED.promise();
        }
        return alertManager.removeThreshold(metric)
                           .map(_ -> new ThresholdRemovedResponse("threshold_removed", metric));
    }

    private AlertsResponse buildAlertsResponse() {
        return new AlertsResponse(alertManager.activeAlertsAsJson(), alertManager.alertHistoryAsJson());
    }

    private enum AlertError implements Cause {
        MISSING_FIELDS("Missing metric, warning, or critical field"),
        METRIC_REQUIRED("Metric name required");
        private final String message;
        AlertError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
