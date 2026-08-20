// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.FirewallName.firewallName;
import static org.pragmatica.aether.environment.FirewallName.forSource;
import static org.pragmatica.aether.environment.SourceName.sourceName;


class FirewallNameTest {

    private static void assertAccepted(String raw) {
        firewallName(raw).onFailure(cause -> fail("'" + raw + "' must be accepted: " + cause.message()))
                         .onSuccess(name -> assertThat(name.value()).isEqualTo(raw));
    }

    private static void assertRejected(String raw) {
        firewallName(raw).onSuccess(name -> fail("'" + raw + "' must be rejected, produced " + name));
    }

    private static ClusterName cluster(String raw) {
        return clusterName(raw).onFailure(cause -> fail("test fixture must be a valid cluster name: " + cause.message()))
                               .unwrap();
    }

    private static SourceName source(String raw) {
        return sourceName(raw).onFailure(cause -> fail("test fixture must be a valid source name: " + cause.message()))
                              .unwrap();
    }

    @Nested
    class ValidationTests {

        @Test
        void firewallName_accepts_theDerivedShapeAndPlainLabels() {
            List.of("aether-prod-eu-hetzner-eu", "aether-eu-1", "a", "a" + "b".repeat(62))
                .forEach(FirewallNameTest::assertAccepted);
        }

        @Test
        void firewallName_rejects_null() {
            assertRejected(null);
        }

        /// Outside the RFC-1035 label grammar the name has to satisfy simultaneously as a Hetzner firewall
        /// name, an AWS security-group name and a GCP firewall-rule name.
        @Test
        void firewallName_rejects_namesOutsideTheRfc1035LabelGrammar() {
            List.of("", " ", "Aether-prod", "aether_prod", "1aether", "-aether", "aether-", "aether prod", "aether.prod")
                .forEach(FirewallNameTest::assertRejected);
        }

        @Test
        void firewallName_rejects_namesLongerThan63Characters() {
            assertRejected("a" + "b".repeat(63));
        }
    }

    @Nested
    class DerivationTests {

        @Test
        void forSource_derivesAetherClusterSource() {
            assertThat(forSource(cluster("prod-eu"), source("hetzner-eu")).value()).isEqualTo("aether-prod-eu-hetzner-eu");
        }

        /// The derivation is TOTAL, so whatever it produces must be re-parseable by the fallible factory —
        /// otherwise an instance could exist that the type's own grammar rejects.
        @Test
        void forSource_producesAValidName_forEveryClusterSourcePairIncludingTheLongest() {
            List.of(forSource(cluster("prod-eu"), source("hetzner-eu")),
                    forSource(cluster("a"), source("b")),
                    forSource(cluster("a" + "b".repeat(62)), source("c" + "d".repeat(62))),
                    forSource(cluster("a" + "-".repeat(55) + "b"), source("c" + "d".repeat(62))))
                .forEach(name -> assertAccepted(name.value()));
        }

        /// The 63-character bound is load-bearing for GCP, whose firewall-rule names admit nothing longer.
        /// Both inputs may themselves be 63 characters, so the concatenation reaches ~134 and must be cut.
        @Test
        void forSource_truncatesToSixtyThreeCharacters_whenBothInputsAreMaximal() {
            var name = forSource(cluster("a" + "b".repeat(62)), source("c" + "d".repeat(62)));

            assertThat(name.value()).hasSize(FirewallName.MAX_LENGTH);
            assertThat(FirewallName.PATTERN.matcher(name.value()).matches()).isTrue();
            assertThat(name.value()).startsWith("aether-ab");
        }

        /// The cut can land inside a run of hyphens — the grammar admits `a--b` inside a label — so ALL of
        /// them have to go. Dropping only the last would leave a value ending in `-`, which the grammar
        /// rejects, i.e. a `FirewallName` that is not a valid firewall name.
        @Test
        void forSource_dropsEveryTrailingHyphenTheCutExposes_notJustTheLast() {
            // "aether-" (7) + 54 chars + "---" puts a hyphen run across the 63-character boundary.
            var name = forSource(cluster("a" + "b".repeat(53) + "---" + "c"), source("hetzner-eu"));

            assertThat(name.value()).doesNotEndWith("-");
            assertThat(FirewallName.PATTERN.matcher(name.value()).matches()).isTrue();
        }

        /// Characterization of the behaviour this derivation REPLACED: `HetznerComputeProvider` used to
        /// build the name by hand and run it through `sanitizeLabelValue`. That call is gone, and this
        /// pins the claim that removing it changed nothing — for every pair, including the ones where the
        /// truncation exposes a hyphen run.
        @Test
        void forSource_matchesTheLegacySanitizeLabelValueDerivation_forEveryPair() {
            List.of(List.of("prod-eu", "hetzner-eu"),
                    List.of("a", "b"),
                    List.of("a" + "b".repeat(62), "c" + "d".repeat(62)),
                    List.of("a" + "b".repeat(53) + "---" + "c", "hetzner-eu"),
                    List.of("a" + "-".repeat(55) + "b", "c" + "d".repeat(62)))
                .forEach(pair -> assertThat(forSource(cluster(pair.getFirst()), source(pair.getLast())).value())
                             .isEqualTo(legacyFirewallName(pair.getFirst(), pair.getLast())));
        }

        private static final Pattern LEGACY_DISALLOWED = Pattern.compile("[^a-zA-Z0-9._-]");
        private static final Pattern LEGACY_EDGE_TRIM = Pattern.compile("^[._-]+|[._-]+$");

        /// `HetznerComputeProvider.firewallName` + `sanitizeLabelValue`, verbatim, as of the commit before
        /// this type replaced them.
        private static String legacyFirewallName(String cluster, String source) {
            var raw = "aether-" + cluster + "-" + source;
            var mapped = LEGACY_DISALLOWED.matcher(raw).replaceAll("-");
            var capped = mapped.length() > 63
                         ? mapped.substring(0, 63)
                         : mapped;

            return LEGACY_EDGE_TRIM.matcher(capped).replaceAll("");
        }
    }

    @Nested
    class RenderingTests {

        /// Ledger entries and log lines interpolate this type directly; `toString` must stay the raw value.
        @Test
        void toString_rendersTheRawValue_soInterpolationStaysIdentical() {
            assertThat("Firewall " + forSource(cluster("prod-eu"), source("hetzner-eu")))
                .isEqualTo("Firewall aether-prod-eu-hetzner-eu");
        }
    }
}
