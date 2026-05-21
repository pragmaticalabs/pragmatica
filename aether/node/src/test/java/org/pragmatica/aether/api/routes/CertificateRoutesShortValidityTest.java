// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.CertConfigureShortValidityRequest;
import org.pragmatica.aether.api.ManagementApiResponses.CertConfigureShortValidityResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.net.tcp.security.GossipKey;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/// Covers `POST /api/certificates/configure-short-validity` (P-NEW-I, dev-mode-only).
/// Validates the dev-mode gate, request validation, scheduler-presence guard, and
/// successful application of a short-validity window. Unblocks `Strengthen-cert-rotation-trigger`
/// in `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
class CertificateRoutesShortValidityTest {

    private CertificateRenewalScheduler scheduler;
    private ManageableNode nodeWithScheduler;
    private ManageableNode nodeWithoutScheduler;

    @BeforeEach
    void setUp() {
        scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(new StubProvider(),
                                                                              "node-1",
                                                                              "host-1",
                                                                              _ -> {},
                                                                              Instant.now().plus(30, ChronoUnit.DAYS));
        nodeWithScheduler = stubNode(Option.some(scheduler));
        nodeWithoutScheduler = stubNode(Option.none());
    }

    private CertificateRoutes routesWithDevMode(boolean enabled, ManageableNode node) {
        BooleanSupplier devMode = () -> enabled;
        return CertificateRoutes.certificateRoutes(() -> node, devMode);
    }

    @Nested
    class DevModeGate {

        @Test
        void configureShortValidity_rejected_whenDevModeDisabled() {
            var routes = routesWithDevMode(false, nodeWithScheduler);

            var result = routes.handleConfigureShortValidityForTest(new CertConfigureShortValidityRequest(60))
                                .onSuccess(_ -> fail("Must be rejected when dev-mode is off"))
                                .await();

            assertTrue(result.isFailure(), "Result must be failure when dev-mode disabled");
            result.onFailure(cause -> assertTrue(cause.message().contains("AETHER_INSECURE_DEV_MODE"),
                                                  "Failure must mention the gate env var: " + cause.message()));
        }
    }

    @Nested
    class Validation {

        @Test
        void configureShortValidity_rejectsZeroValidity() {
            var routes = routesWithDevMode(true, nodeWithScheduler);

            var result = routes.handleConfigureShortValidityForTest(new CertConfigureShortValidityRequest(0))
                                .onSuccess(_ -> fail("Zero validity must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("validity"),
                                                  "Failure must mention validity: " + cause.message()));
        }

        @Test
        void configureShortValidity_rejectsExcessiveValidity() {
            var routes = routesWithDevMode(true, nodeWithScheduler);

            var result = routes.handleConfigureShortValidityForTest(new CertConfigureShortValidityRequest(100_000))
                                .onSuccess(_ -> fail("Excessive validity must be rejected"))
                                .await();

            assertTrue(result.isFailure());
        }
    }

    @Nested
    class SchedulerPresence {

        @Test
        void configureShortValidity_failsWhenSchedulerAbsent() {
            var routes = routesWithDevMode(true, nodeWithoutScheduler);

            var result = routes.handleConfigureShortValidityForTest(new CertConfigureShortValidityRequest(60))
                                .onSuccess(_ -> fail("Must fail when scheduler is not configured"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("scheduler"),
                                                  "Failure must mention scheduler: " + cause.message()));
        }
    }

    @Nested
    class HappyPath {

        @Test
        void configureShortValidity_updatesSchedulerNotAfter_andReturnsConfirmation() {
            var routes = routesWithDevMode(true, nodeWithScheduler);
            var beforeNotAfter = scheduler.currentNotAfter();

            var response = routes.handleConfigureShortValidityForTest(new CertConfigureShortValidityRequest(60))
                                  .onFailure(cause -> fail("Must succeed: " + cause.message()))
                                  .await()
                                  .or((CertConfigureShortValidityResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo("short_validity_configured");
            assertThat(response.validitySeconds()).isEqualTo(60);
            assertThat(response.secondsUntilExpiry()).isBetween(0L, 60L);
            assertThat(scheduler.currentNotAfter()).isBefore(beforeNotAfter);
            assertThat(scheduler.secondsUntilExpiry()).isLessThanOrEqualTo(60);
        }
    }

    @Nested
    class RouteRegistration {

        @Test
        void routes_includesConfigureShortValidityRoute() {
            var routes = routesWithDevMode(true, nodeWithScheduler);

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("CERT_CONFIGURE_SHORT_VALIDITY");
        }
    }

    // --- helpers ---

    private static ManageableNode stubNode(Option<CertificateRenewalScheduler> scheduler) {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _) -> {
                if ("certRenewalScheduler".equals(method.getName())) {
                    return scheduler;
                }
                throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            }
        );
    }

    private static final class StubProvider implements CertificateProvider {
        @Override
        public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
            return Causes.cause("stub").result();
        }

        @Override
        public Result<CertificateBundle> caCertificate() {
            return Causes.cause("stub").result();
        }

        @Override
        public Result<GossipKey> currentGossipKey() {
            return Causes.cause("stub").result();
        }

        @Override
        public Option<GossipKey> previousGossipKey() {
            return Option.none();
        }
    }
}
