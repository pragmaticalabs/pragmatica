// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #580. The previous version of this suite asserted `rulesFor_standard_allRulesUseAnyCidr` — that
/// EVERY rule of the default preset, management API included, uses `0.0.0.0/0`. It encoded the
/// vulnerability as the requirement, so the exposure could never be caught by a failing test.
class FirewallPresetsTest {

    private static final String ANY = "0.0.0.0/0";
    private static final String ADMIN = "203.0.113.42/32";
    private static final String INTERNAL = "10.0.0.0/8";
    private static final PortMapping PORTS = PortMapping.defaultPortMapping();

    private static int mgmt() {
        return PORTS.management();
    }

    @Nested
    class ManagementPlaneIsNeverPublic {

        /// The control plane on `0.0.0.0/0` plus the documented `security_mode = "NONE"` is
        /// unauthenticated remote cluster control. No preset may produce it.
        @Test
        void rulesFor_noPreset_everOpensManagementToAnyCidr() {
            for (var preset : FirewallPreset.values()) {
                assertThat(FirewallPresets.rulesFor(preset, Option.some(ADMIN), INTERNAL))
                        .as("preset %s", preset)
                        .noneSatisfy(rule -> {
                            assertThat(rule.port()).isEqualTo(mgmt());
                            assertThat(rule.sourceCidr()).isEqualTo(ANY);
                        });
            }
        }

        /// An absent admin CIDR means "I did not say who may reach this" — never "everyone".
        @Test
        void rulesFor_standardWithoutAdminCidr_omitsManagementAndSshRather()  {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, Option.empty(), INTERNAL);

            assertThat(rules).noneSatisfy(rule -> assertThat(rule.port()).isEqualTo(mgmt()))
                             .noneSatisfy(rule -> assertThat(rule.port()).isEqualTo(FirewallPresets.SSH_PORT));
        }

        @Test
        void rulesFor_restrictiveWithoutAdminCidr_omitsManagementAndSsh() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, Option.empty(), INTERNAL);

            assertThat(rules).noneSatisfy(rule -> assertThat(rule.port()).isEqualTo(mgmt()))
                             .noneSatisfy(rule -> assertThat(rule.port()).isEqualTo(FirewallPresets.SSH_PORT));
        }
    }

    @Nested
    class StandardPreset {

        @Test
        void rulesFor_standard_scopesManagementToAdminCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(mgmt());
                assertThat(rule.sourceCidr()).isEqualTo(ADMIN);
                assertThat(rule.protocol()).isEqualTo("tcp");
            });
        }

        /// Bootstrap deploys the runtime over SSH; a preset that omits 22 fails on healthy nodes.
        @Test
        void rulesFor_standard_scopesBootstrapSshToAdminCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(FirewallPresets.SSH_PORT);
                assertThat(rule.sourceCidr()).isEqualTo(ADMIN);
            });
        }

        @Test
        void rulesFor_standard_keepsAppHttpPublic() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(PORTS.appHttp());
                assertThat(rule.sourceCidr()).isEqualTo(ANY);
            });
        }

        /// Nodes address each other by PUBLIC IP under `networking type = "manual"`, so narrowing
        /// the consensus ports would stop the cluster forming. They carry authenticated traffic.
        @Test
        void rulesFor_standard_leavesClusterMeshReachable() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(PORTS.cluster());
                assertThat(rule.sourceCidr()).isEqualTo(ANY);
                assertThat(rule.protocol()).isEqualTo("tcp");
            }).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(PORTS.swim());
                assertThat(rule.protocol()).isEqualTo("udp");
            });
        }

        /// Ports come from [PortMapping], never re-spelled. They previously drifted to 7100/7200,
        /// disagreeing with the documented defaults the rest of the system uses.
        @Test
        void rulesFor_standard_usesDocumentedDefaultPorts() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.STANDARD, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> assertThat(rule.port()).isEqualTo(8090))
                             .anySatisfy(rule -> assertThat(rule.port()).isEqualTo(8190))
                             .anySatisfy(rule -> assertThat(rule.port()).isEqualTo(8080))
                             .anySatisfy(rule -> assertThat(rule.port()).isEqualTo(8070));
        }
    }

    @Nested
    class RestrictivePreset {

        @Test
        void rulesFor_restrictive_managementUsesAdminCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(mgmt());
                assertThat(rule.sourceCidr()).isEqualTo(ADMIN);
            });
        }

        @Test
        void rulesFor_restrictive_appHttpAndMeshUseInternalCidr() {
            var rules = FirewallPresets.rulesFor(FirewallPreset.RESTRICTIVE, Option.some(ADMIN), INTERNAL);

            assertThat(rules).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(PORTS.appHttp());
                assertThat(rule.sourceCidr()).isEqualTo(INTERNAL);
            }).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(PORTS.cluster());
                assertThat(rule.sourceCidr()).isEqualTo(INTERNAL);
            }).anySatisfy(rule -> {
                assertThat(rule.port()).isEqualTo(PORTS.swim());
                assertThat(rule.sourceCidr()).isEqualTo(INTERNAL);
                assertThat(rule.protocol()).isEqualTo("udp");
            });
        }
    }

    @Nested
    class NoRulePresets {

        @Test
        void rulesFor_open_returnsEmptyList() {
            assertThat(FirewallPresets.rulesFor(FirewallPreset.OPEN, Option.some(ADMIN), INTERNAL)).isEmpty();
        }

        @Test
        void rulesFor_custom_returnsEmptyList() {
            assertThat(FirewallPresets.rulesFor(FirewallPreset.CUSTOM, Option.some(ADMIN), INTERNAL)).isEmpty();
        }
    }
}
