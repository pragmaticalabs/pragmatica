// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapCommand.ParsedConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterConfigError;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// #1487 — `aether cluster bootstrap --cluster <name>` must persist the override. Formation POSTs the raw
/// TOML (`BootstrapPhaseFormation.storeClusterConfig` → `buildConfigJson`) and CTM names every replacement
/// from it, so the POSTed TOML's `[cluster] name` must be the override when one is given and the TOML's own
/// name when none is.
class ClusterNameOverridePersistenceTest {
    private static final String TOML = """
            config_version = "1.0.0"

            # operator comment, preserved verbatim
            [cluster]
            version = "1.0.0"
            name = "from-toml"   # trailing comment

            [source.forge]
            type = "forge"

            [source.forge.core]
            count = 3
            """;

    @Nested
    class CommandOverride {
        @Test
        void applyClusterNameOverride_clusterFlagGiven_postedTomlCarriesOverrideName() {
            var applied = ClusterBootstrapCommand.applyClusterNameOverride(parsed(TOML), some("cli-name"))
                                                 .onFailure(cause -> fail(cause.message()))
                                                 .unwrap();

            assertThat(applied.config().cluster().name().value()).isEqualTo("cli-name");
            assertThat(persistedClusterName(applied.rawToml())).isEqualTo("cli-name");
            assertThat(postedBody(applied.rawToml())).contains("name = \\\"cli-name\\\"")
                                                     .doesNotContain("from-toml");
        }

        @Test
        void applyClusterNameOverride_noClusterFlag_postedTomlCarriesTomlName() {
            var parsed = parsed(TOML);
            var applied = ClusterBootstrapCommand.applyClusterNameOverride(parsed, none()).unwrap();

            assertThat(applied.rawToml()).isEqualTo(TOML);
            assertThat(persistedClusterName(applied.rawToml())).isEqualTo("from-toml");
            assertThat(postedBody(applied.rawToml())).contains("name = \\\"from-toml\\\"");
        }

        @Test
        void applyClusterNameOverride_blankClusterFlag_postedTomlCarriesTomlName() {
            var applied = ClusterBootstrapCommand.applyClusterNameOverride(parsed(TOML), some(" ")).unwrap();

            assertThat(applied.rawToml()).isEqualTo(TOML);
            assertThat(applied.config().cluster().name().value()).isEqualTo("from-toml");
        }
    }

    @Nested
    class TomlRewrite {
        @Test
        void withClusterName_nameLine_replacesOnlyClusterSectionName() {
            var toml = TOML + """

                    [runtime.default]
                    name = "untouched"
                    """;

            var rewritten = ClusterNameToml.withClusterName(toml, "cli-name").unwrap();

            assertThat(rewritten).isEqualTo(toml.replace("name = \"from-toml\"   # trailing comment",
                                                         "name = \"cli-name\""));
        }

        @Test
        void withClusterName_clusterSubsectionBeforeCluster_rewritesOnlyClusterName() {
            var toml = """
                    [cluster.core]
                    name = "sub"
                    min = 3

                    [cluster]
                    name = "from-toml"
                    version = "1.0.0"
                    """;

            var rewritten = ClusterNameToml.withClusterName(toml, "cli-name")
                                           .onFailure(cause -> fail(cause.message()))
                                           .unwrap();

            assertThat(rewritten).isEqualTo(toml.replace("name = \"from-toml\"", "name = \"cli-name\""));
        }

        @Test
        void withClusterName_multilineStringHoldsNameLine_failsInsteadOfRewritingWrongLine() {
            var toml = """
                    [cluster]
                    description = \"""
                    name = "decoy"
                    \"""
                    name = "from-toml"
                    version = "1.0.0"
                    """;

            ClusterNameToml.withClusterName(toml, "cli-name")
                           .onSuccess(rewritten -> fail("expected a loud failure, got:\n" + rewritten))
                           .onFailure(cause -> assertThat(cause).isEqualTo(ClusterNameToml.NameNotRewritten.FACTORY.apply("from-toml")));
        }

        @Test
        void withClusterName_invalidName_fails() {
            ClusterNameToml.withClusterName(TOML, "Bad_Name")
                           .onSuccess(rewritten -> fail("expected the name to be refused, got:\n" + rewritten));
        }

        @Test
        void withClusterName_noClusterSection_fails() {
            ClusterNameToml.withClusterName("[source.forge]\nname = \"x\"\n", "cli-name")
                           .onSuccess(_ -> fail("expected failure without a [cluster] section"));
        }

        @Test
        void withClusterName_clusterSectionWithoutName_fails() {
            ClusterNameToml.withClusterName("[cluster]\nversion = \"1.0.0\"\n\n[other]\nname = \"x\"\n", "cli-name")
                           .onSuccess(_ -> fail("expected failure when [cluster] has no name line"));
        }
    }

    @Nested
    class ApplyOverride {
        private final String persistedUnderOverride = TOML.replace("from-toml", "cli-name");

        @Test
        void withClusterNameOverride_clusterFlagMatchesPersistedName_planAccepted() {
            var desiredToml = ClusterApplyCommand.withClusterNameOverride(TOML, some("cli-name"))
                                                 .onFailure(cause -> fail(cause.message()))
                                                 .unwrap();

            assertThat(persistedClusterName(desiredToml)).isEqualTo("cli-name");
            ApplyOrchestrator.dryRun(config(desiredToml), config(persistedUnderOverride))
                             .onFailure(cause -> fail("apply refused: " + cause.message()));
        }

        @Test
        void withClusterNameOverride_noClusterFlag_immutableNameRefused() {
            var desiredToml = ClusterApplyCommand.withClusterNameOverride(TOML, none()).unwrap();

            assertThat(desiredToml).isEqualTo(TOML);
            ApplyOrchestrator.dryRun(config(desiredToml), config(persistedUnderOverride))
                             .onSuccess(plan -> fail("expected the immutable-name refusal, got plan:\n" + plan))
                             .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.ImmutableFieldChange.class));
        }

        @Test
        void withClusterNameOverride_blankClusterFlag_fileUnchanged() {
            assertThat(ClusterApplyCommand.withClusterNameOverride(TOML, some(" ")).unwrap()).isEqualTo(TOML);
        }
    }

    private static ClusterBootstrapConfig config(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).unwrap();
    }

    private static ParsedConfig parsed(String toml) {
        return new ParsedConfig(ClusterBootstrapConfigParser.parse(toml).unwrap(), toml);
    }

    private static String persistedClusterName(String rawToml) {
        return ClusterBootstrapConfigParser.parse(rawToml)
                                           .unwrap()
                                           .cluster()
                                           .name()
                                           .value();
    }

    /// Same composition `BootstrapPhaseFormation.storeClusterConfig` applies before POSTing.
    private static String postedBody(String rawToml) {
        return BootstrapPhaseFormation.buildConfigJson(SshAuthorizedKeysToml.withAuthorizedKeys(rawToml, List.of()));
    }
}
