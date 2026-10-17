// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.AlertManager.AlertHistoryView;
import org.pragmatica.aether.api.AlertManager.AlertView;
import org.pragmatica.aether.api.AlertManager.ThresholdView;
import org.pragmatica.aether.api.ManagementApiResponses.AlertInjectResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AlertsResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ThresholdRemovedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ThresholdSetResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.Handler;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.http.routing.PathParameter.aString;


/// Alert and threshold management routes.
///
/// **`POST /api/v1/alerts/clear` was REMOVED in #957, and it is the one alerting surface that was
/// removed rather than re-pointed.** The rule for this redesign was to re-point surfaces that read
/// node-local volatile state, because the defect was where they read from, not that they existed.
/// That rule governs QUERIES. Clear was a MUTATION of state that no longer exists, and it was
/// measurably already a no-op: `activeAlertsAsList` re-adds every stream `AlertInjected` whose id is
/// absent from the local map, so clearing the map emptied the dedup set and the alerts returned on the
/// next read — 2 seconds later, at the dashboard's poll rate. It only appeared to work in the
/// bootstrap window, when the stream read returns empty. Threshold alerts are re-derived from live
/// metrics within one tick, so clearing those was equally transient.
///
/// An operator therefore has no supported way to dismiss an injected alert. That is true TODAY and is
/// not a regression; doing it properly needs a tombstone event, which is tracked separately rather
/// than smuggled in under the word "clear".
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
                         ManagementRoutes.<AlertsResponse> route(ManagementRoute.ALERTS).toJson((Handler<AlertsResponse>) ctx -> buildAlertsResponse()),
                         ManagementRoutes.<List<AlertView>> route(ManagementRoute.ALERTS_ACTIVE).toJson((Handler<List<AlertView>>) ctx -> alertManager.activeAlertsAsList()),
                         ManagementRoutes.<List<AlertHistoryView>> route(ManagementRoute.ALERTS_HISTORY).toJson((Handler<List<AlertHistoryView>>) ctx -> alertManager.alertHistoryAsList()),
                         ManagementRoutes.<ThresholdSetResponse> route(ManagementRoute.THRESHOLD_SET)
                                         .withBody(ThresholdRequest.class)
                                         .toJson(this::handleSetThreshold),
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
        if (req.metric() == null || req.metric().isEmpty()) {
            return AlertError.MISSING_FIELDS.result();
        }

        if (req.warning() == null || req.critical() == null) {
            return AlertError.MISSING_FIELDS.result();
        }

        return Result.success(req);
    }

    private Promise<ThresholdRemovedResponse> handleDeleteThreshold(String metric) {
        if (metric.isEmpty()) {
            return AlertError.METRIC_REQUIRED.promise();
        }

        return alertManager.removeThreshold(metric)
                           .map(_ -> new ThresholdRemovedResponse("threshold_removed", metric));
    }

    /// `/api/v1/alerts` — active + history in one response. Both halves are async since #957: active
    /// is the derived view plus a cluster-wide injected-alert union, history is a bounded projection
    /// over the cluster event log.
    private Promise<AlertsResponse> buildAlertsResponse() {
        return alertManager.activeAlertsAsList()
                           .flatMap(active -> alertManager.alertHistoryAsList()
                                                          .map(history -> new AlertsResponse(active, history)));
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
