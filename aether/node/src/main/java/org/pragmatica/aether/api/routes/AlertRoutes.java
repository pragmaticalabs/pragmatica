// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.AlertManager.AlertHistoryView;
import org.pragmatica.aether.api.AlertManager.AlertView;
import org.pragmatica.aether.api.AlertManager.ThresholdView;
import org.pragmatica.aether.api.ManagementApiResponses.AlertInjectResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AlertsClearedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AlertsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ThresholdRemovedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ThresholdSetResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;


public final class AlertRoutes implements RouteSource {
    private final AlertManager alertManager;

    private AlertRoutes(AlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public static AlertRoutes alertRoutes(AlertManager alertManager) {
        return new AlertRoutes(alertManager);
    }

    record ThresholdRequest(String metric, Double warning, Double critical) {}

    record InjectRequest(String name, String severity, String message, String metric, Double value) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<List<ThresholdView>> route(ManagementRoute.THRESHOLDS_LIST).toJson(alertManager::thresholdsAsList),
                         ManagementRoutes.<AlertsResponse> route(ManagementRoute.ALERTS).toJson(this::buildAlertsResponse),
                         ManagementRoutes.<List<AlertView>> route(ManagementRoute.ALERTS_ACTIVE).toJson(alertManager::activeAlertsAsList),
                         ManagementRoutes.<List<AlertHistoryView>> route(ManagementRoute.ALERTS_HISTORY).toJson(alertManager::alertHistoryAsList),
                         ManagementRoutes.<ThresholdSetResponse> route(ManagementRoute.THRESHOLD_SET)
                                         .withBody(ThresholdRequest.class)
                                         .toJson(this::handleSetThreshold),
                         ManagementRoutes.<AlertsClearedResponse> route(ManagementRoute.ALERTS_CLEAR).toJson(this::handleClearAlerts),
                         ManagementRoutes.<AlertInjectResponse> route(ManagementRoute.ALERTS_INJECT)
                                         .withBody(InjectRequest.class)
                                         .toJson(this::handleInjectAlert),
                         ManagementRoutes.<ThresholdRemovedResponse> route(ManagementRoute.THRESHOLD_DELETE)
                                         .withPath(aString())
                                         .to(this::handleDeleteThreshold)
                                         .asJson());
    }

    private Promise<AlertInjectResponse> handleInjectAlert(InjectRequest req) {
        return alertManager.inject(req.name(),
                                   req.severity(),
                                   req.message(),
                                   Option.option(req.metric()),
                                   Option.option(req.value()));
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
        if (req.metric() == null || req.metric().isEmpty()) {return AlertError.MISSING_FIELDS.result();}
        if (req.warning() == null || req.critical() == null) {return AlertError.MISSING_FIELDS.result();}

        return Result.success(req);
    }

    private AlertsClearedResponse handleClearAlerts() {
        alertManager.clearAlerts();

        return new AlertsClearedResponse("alerts_cleared");
    }

    private Promise<ThresholdRemovedResponse> handleDeleteThreshold(String metric) {
        if (metric.isEmpty()) {return AlertError.METRIC_REQUIRED.promise();}
        return alertManager.removeThreshold(metric)
                           .map(_ -> new ThresholdRemovedResponse("threshold_removed", metric));
    }

    private AlertsResponse buildAlertsResponse() {
        return new AlertsResponse(alertManager.activeAlertsAsList(), alertManager.alertHistoryAsList());
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
