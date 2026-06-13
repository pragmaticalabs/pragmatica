// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.CertConfigureShortValidityRequest;
import org.pragmatica.aether.api.ManagementApiResponses.CertConfigureShortValidityResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;


/// Test-only certificate management endpoints. Currently hosts the dev-mode-gated
/// `POST /api/certificates/configure-short-validity` route (P-NEW-I, 2026-05-21) used
/// by `Strengthen-cert-rotation-trigger` to drive observable cert rotation without
/// waiting for the production renewal window (default = 40% of multi-day validity).
///
/// The operator-facing `GET /api/certificates` endpoint lives in `StatusRoutes` — that
/// is the read-side certificate status surface (no dev-mode gate). This route source
/// exists to keep test hooks segregated from operator endpoints.
public final class CertificateRoutes implements RouteSource {
    private static final String DEV_MODE_ENV = "AETHER_INSECURE_DEV_MODE";
    private static final int MIN_VALIDITY_SECONDS = 1;
    private static final int MAX_VALIDITY_SECONDS = 86_400;  // 24h ceiling — defensive against absurd inputs

    private final Supplier<ManageableNode> nodeSupplier;
    private final BooleanSupplier devModeEnabled;

    private CertificateRoutes(Supplier<ManageableNode> nodeSupplier, BooleanSupplier devModeEnabled) {
        this.nodeSupplier = nodeSupplier;
        this.devModeEnabled = devModeEnabled;
    }

    public static CertificateRoutes certificateRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new CertificateRoutes(nodeSupplier, CertificateRoutes::devModeFromEnv);
    }

    /// Test-friendly factory: callers (unit tests) inject the dev-mode flag directly
    /// rather than mutating the JVM-wide environment. Mirrors the pattern used by
    /// `ScheduledTaskRoutes.scheduledTaskRoutes(..., BooleanSupplier)`.
    public static CertificateRoutes certificateRoutes(Supplier<ManageableNode> nodeSupplier,
                                                      BooleanSupplier devModeEnabled) {
        return new CertificateRoutes(nodeSupplier, devModeEnabled);
    }

    private static boolean devModeFromEnv() {
        return "true".equalsIgnoreCase(System.getenv(DEV_MODE_ENV));
    }

    /// Package-private accessor for unit tests that exercise the handler without
    /// standing up the full HTTP layer. Production callers go through `routes()`.
    Promise<CertConfigureShortValidityResponse> handleConfigureShortValidityForTest(CertConfigureShortValidityRequest req) {
        return handleConfigureShortValidity(req);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<CertConfigureShortValidityResponse> route(ManagementRoute.CERT_CONFIGURE_SHORT_VALIDITY)
                                         .withBody(CertConfigureShortValidityRequest.class)
                                         .toJson(this::handleConfigureShortValidity));
    }

    private Promise<CertConfigureShortValidityResponse> handleConfigureShortValidity(CertConfigureShortValidityRequest req) {
        if (!devModeEnabled.getAsBoolean()) {
            return CertConfigureError.DEV_MODE_DISABLED.promise();
        }

        return validateRequest(req).flatMap(this::applyShortValidity);
    }

    private Promise<CertConfigureShortValidityRequest> validateRequest(CertConfigureShortValidityRequest req) {
        if (req == null) {
            return CertConfigureError.MISSING_BODY.promise();
        }

        if (req.validitySeconds() < MIN_VALIDITY_SECONDS) {
            return CertConfigureError.VALIDITY_TOO_LOW.promise();
        }

        if (req.validitySeconds() > MAX_VALIDITY_SECONDS) {
            return CertConfigureError.VALIDITY_TOO_HIGH.promise();
        }

        return Promise.success(req);
    }

    private Promise<CertConfigureShortValidityResponse> applyShortValidity(CertConfigureShortValidityRequest req) {
        return nodeSupplier.get()
                           .certRenewalScheduler()
                           .map(scheduler -> respond(req,
                                                     scheduler.configureShortValidity(req.validitySeconds())))
                           .or(CertConfigureError.SCHEDULER_NOT_CONFIGURED.promise());
    }

    private static Promise<CertConfigureShortValidityResponse> respond(CertConfigureShortValidityRequest req,
                                                                       Instant newNotAfter) {
        var secondsRemaining = Duration.between(Instant.now(), newNotAfter).toSeconds();

        return Promise.success(new CertConfigureShortValidityResponse("short_validity_configured",
                                                                      req.validitySeconds(),
                                                                      newNotAfter.toString(),
                                                                      secondsRemaining));
    }

    private enum CertConfigureError implements Cause {
        DEV_MODE_DISABLED("configure-short-validity requires AETHER_INSECURE_DEV_MODE=true"),
        MISSING_BODY("Request body is required"),
        VALIDITY_TOO_LOW("validitySeconds must be >= " + MIN_VALIDITY_SECONDS),
        VALIDITY_TOO_HIGH("validitySeconds must be <= " + MAX_VALIDITY_SECONDS),
        SCHEDULER_NOT_CONFIGURED("CertificateRenewalScheduler is not configured (TLS may be disabled)");
        private final String message;
        CertConfigureError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
