// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.net.tcp.security.GossipKey;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests for `GET /api/certificates` response shape — specifically the `tlsEnabled`
/// boolean introduced in P3 of the 2026-05-21 production readiness plan. Replaces
/// the integration-test tautology in `test-cert-rotation.sh::test_tls_active`
/// (audit §2.2, RC1-blocker #3): operators and integration tooling can now assert
/// TLS posture directly without inferring it from a `renewalStatus` placeholder.
class StatusRoutesCertificatesTest {

    @Nested
    class WhenTlsDisabled {
        @Test
        void certificateStatus_tlsOffSchedulerAbsent_returnsNotConfiguredPlaceholders() {
            var response = StatusRoutes.certificateStatus(false, Option.none());

            assertThat(response.tlsEnabled()).isFalse();
            assertThat(response.renewalStatus()).isEqualTo("NOT_CONFIGURED");
            assertThat(response.expiresAt()).isEqualTo("N/A");
            assertThat(response.lastRenewalAt()).isEqualTo("N/A");
            assertThat(response.secondsUntilExpiry()).isZero();
        }
    }

    @Nested
    class WhenTlsEnabled {
        @Test
        void certificateStatus_tlsOnSchedulerAbsent_keepsPlaceholdersButFlagsEnabled() {
            // Defensive coverage: in normal startup TLS-on implies a scheduler is wired,
            // but the response must remain coherent if those two signals ever diverge.
            var response = StatusRoutes.certificateStatus(true, Option.none());

            assertThat(response.tlsEnabled()).isTrue();
            assertThat(response.renewalStatus()).isEqualTo("NOT_CONFIGURED");
            assertThat(response.expiresAt()).isEqualTo("N/A");
        }

        @Test
        void certificateStatus_tlsOnSchedulerPresent_populatesAllFields() {
            var notAfter = Instant.now().plus(30, ChronoUnit.DAYS);
            var scheduler = CertificateRenewalScheduler.certificateRenewalScheduler(new StubProvider(),
                                                                                    "node-1",
                                                                                    "host-1",
                                                                                    _ -> {},
                                                                                    notAfter);
            var response = StatusRoutes.certificateStatus(true, Option.some(scheduler));

            assertThat(response.tlsEnabled()).isTrue();
            assertThat(response.expiresAt()).isEqualTo(notAfter.toString());
            assertThat(response.secondsUntilExpiry()).isPositive();
            assertThat(response.renewalStatus()).isEqualTo("INITIALIZING");
            assertThat(response.lastRenewalAt()).isNotEqualTo("N/A");
        }
    }

    /// `CertificateProvider` stub that never produces a bundle. Required only to construct
    /// a `CertificateRenewalScheduler` — the scheduler is never started, so `issueCertificate`
    /// is never invoked.
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
