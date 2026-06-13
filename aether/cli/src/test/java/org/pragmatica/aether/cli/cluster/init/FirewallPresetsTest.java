// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FirewallPresetsTest {

    private static final String ANY = "0.0.0.0/0";
    private static final String ADMIN = "203.0.113.42/32";
    private static final String INTERNAL = "10.0.0.0/8";

    @Nested
    class StandardPreset {

        @Test
        void rulesFor_standard_returnsFourRules() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, ADMIN, INTERNAL);
            assertThat(rules).hasSize(4);
        }

        @Test
        void rulesFor_standard_allRulesUseAnyCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, ADMIN, INTERNAL);
            assertThat(rules).allSatisfy(r -> assertThat(r.sourceCidr()).isEqualTo(ANY));
        }

        @Test
        void rulesFor_standard_managementOnTcp8080() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.MGMT_PORT);
                assertThat(r.protocol()).isEqualTo("tcp");
            });
        }

        @Test
        void rulesFor_standard_appHttpOnTcp8070() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.APP_HTTP_PORT);
                assertThat(r.protocol()).isEqualTo("tcp");
            });
        }

        @Test
        void rulesFor_standard_clusterOnTcp7100() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.CLUSTER_PORT);
                assertThat(r.protocol()).isEqualTo("tcp");
            });
        }

        @Test
        void rulesFor_standard_swimOnUdp7200() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.SWIM_PORT);
                assertThat(r.protocol()).isEqualTo("udp");
            });
        }
    }

    @Nested
    class RestrictivePreset {

        @Test
        void rulesFor_restrictive_managementUsesAdminCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.MGMT_PORT);
                assertThat(r.sourceCidr()).isEqualTo(ADMIN);
                assertThat(r.protocol()).isEqualTo("tcp");
            });
        }

        @Test
        void rulesFor_restrictive_appHttpUsesAdminCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.APP_HTTP_PORT);
                assertThat(r.sourceCidr()).isEqualTo(ADMIN);
                assertThat(r.protocol()).isEqualTo("tcp");
            });
        }

        @Test
        void rulesFor_restrictive_clusterUsesInternalCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.CLUSTER_PORT);
                assertThat(r.sourceCidr()).isEqualTo(INTERNAL);
                assertThat(r.protocol()).isEqualTo("tcp");
            });
        }

        @Test
        void rulesFor_restrictive_swimUsesInternalCidrUdp() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, ADMIN, INTERNAL);
            assertThat(rules).anySatisfy(r -> {
                assertThat(r.port()).isEqualTo(FirewallPresets.SWIM_PORT);
                assertThat(r.sourceCidr()).isEqualTo(INTERNAL);
                assertThat(r.protocol()).isEqualTo("udp");
            });
        }
    }

    @Nested
    class OpenPreset {

        @Test
        void rulesFor_open_returnsEmptyList() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.OPEN, ADMIN, INTERNAL);
            assertThat(rules).isEmpty();
        }
    }

    @Nested
    class CustomPreset {

        @Test
        void rulesFor_custom_returnsEmptyList() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.CUSTOM, ADMIN, INTERNAL);
            assertThat(rules).isEmpty();
        }
    }
}
