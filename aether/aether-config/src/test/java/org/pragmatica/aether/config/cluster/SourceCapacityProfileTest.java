// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 spec §8.1 [P-SRC1] — the source capacity profile (`lead_time`, `max_nodes`, `preference`, `cost_class`,
/// `fallback`) and the first-class `[cluster] max_nodes`: defaults, parse refusals, validator refusals, diff
/// and the overlay that carries the fleet cap to every node.
class SourceCapacityProfileTest {
    private static final String CLUSTER_HEAD = """
        config_version = "1.0.0"

        [cluster]
        name = "production"
        version = "1.0.0"
        %s
        """;

    private static final String HETZNER_SOURCE = """
        [source.hz]
        type = "cloud"
        provider = "hetzner"
        credentials = "key"
        region = "eu-central"
        %s

        [source.hz.core]
        count = 3
        instance_type = "cx41"
        runtime = "prod"

        %s

        [runtime.prod]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
        """;

    private static String hetzner(String clusterExtra, String sourceExtra, String trailingTables) {
        return CLUSTER_HEAD.formatted(clusterExtra) + HETZNER_SOURCE.formatted(sourceExtra, trailingTables);
    }

    private static SourceProfile parsedHz(String sourceExtra, String trailingTables) {
        return ClusterBootstrapConfigParser.parse(hetzner("", sourceExtra, trailingTables))
                                           .fold(cause -> Assertions.fail(cause.message()),
                                                 config -> config.sources().get("hz"));
    }

    private static void assertParseRefused(String toml, String... fragments) {
        ClusterBootstrapConfigParser.parse(toml)
                                    .onSuccess(_ -> Assertions.fail("Expected a parse refusal, not a silent default"))
                                    .onFailure(cause -> assertThat(cause.message()).contains(fragments));
    }

    private static Result<ClusterBootstrapConfig> parseAndValidate(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).flatMap(ClusterBootstrapConfigValidator::validate);
    }

    @Test
    void parse_absentCapacityFields_takeDocumentedDefaults() {
        var hz = parsedHz("", "");

        assertThat(hz.preference()).isEqualTo(100);
        assertThat(hz.costClass()).isEqualTo(CostClass.ONDEMAND);
        assertThat(hz.fallback()).isEqualTo(SourceFallback.ALLOW);
        assertThat(hz.maxNodes()).isEqualTo(Option.none());
        assertThat(hz.leadTime()).isEmpty();
        assertThat(hz.effectiveLeadTime(NodeRole.CORE).millis()).isEqualTo(65_000L);
    }

    @Test
    void parse_declaredCapacityFields_reachTheProfile() {
        var hz = parsedHz("""
                          max_nodes = 12
                          preference = 7
                          cost_class = "reserved"
                          fallback = "forbid"
                          """,
                          """
                          [source.hz.lead_time]
                          core = "90s"
                          """);

        assertThat(hz.maxNodes()).isEqualTo(Option.some(12));
        assertThat(hz.preference()).isEqualTo(7);
        assertThat(hz.costClass()).isEqualTo(CostClass.RESERVED);
        assertThat(hz.fallback()).isEqualTo(SourceFallback.FORBID);
        assertThat(hz.effectiveLeadTime(NodeRole.CORE).millis()).isEqualTo(90_000L);
        assertThat(hz.effectiveLeadTime(NodeRole.WORKER).millis()).as("a role without a lead_time takes the provider default")
                                                                  .isEqualTo(65_000L);
    }

    @Test
    void effectiveLeadTime_perProviderDefaults_areStatedConstants() {
        var hetznerSource = parsedHz("", "");
        var awsSource = ClusterBootstrapConfigParser.parse(hetzner("", "", "").replace("provider = \"hetzner\"", "provider = \"aws\""))
                                                    .fold(cause -> Assertions.fail(cause.message()),
                                                          config -> config.sources().get("hz"));
        var dockerSource = ClusterBootstrapConfigParser.parse(CLUSTER_HEAD.formatted("") + """
                                                                [source.local]
                                                                type = "docker"

                                                                [source.local.core]
                                                                count = 3
                                                                """)
                                                       .fold(cause -> Assertions.fail(cause.message()),
                                                             config -> config.sources().get("local"));

        assertThat(hetznerSource.provider()).isEqualTo(Option.some(CloudProviderName.HETZNER));
        assertThat(hetznerSource.effectiveLeadTime(NodeRole.CORE).millis()).isEqualTo(65_000L);
        assertThat(awsSource.effectiveLeadTime(NodeRole.CORE).millis()).isEqualTo(120_000L);
        assertThat(dockerSource.effectiveLeadTime(NodeRole.CORE).millis()).isEqualTo(30_000L);
        assertThat(SourceProfile.HETZNER_LEAD_TIME.millis()).isEqualTo(65_000L);
        assertThat(SourceProfile.OTHER_CLOUD_LEAD_TIME.millis()).isEqualTo(120_000L);
        assertThat(SourceProfile.NON_CLOUD_LEAD_TIME.millis()).isEqualTo(30_000L);
    }

    @Test
    void parse_badLeadTimeDuration_failsNamingKeyAndValue() {
        assertParseRefused(hetzner("", "", """
                                          [source.hz.lead_time]
                                          core = "soon"
                                          """),
                           "lead_time.core",
                           "soon");
    }

    @Test
    void parse_zeroLeadTime_failsAsNonPositive() {
        assertParseRefused(hetzner("", "", """
                                          [source.hz.lead_time]
                                          core = "0s"
                                          """),
                           "lead_time.core",
                           "positive");
    }

    @Test
    void parse_leadTimeForUnknownRole_failsNamingRole() {
        assertParseRefused(hetzner("", "", """
                                          [source.hz.lead_time]
                                          gpu = "60s"
                                          """),
                           "lead_time.gpu");
    }

    @Test
    void parse_sourceMaxNodesBelowOne_failsNamingKey() {
        List.of("0", "-3")
            .forEach(value -> assertParseRefused(hetzner("", "max_nodes = " + value, ""),
                                                 "source.hz.max_nodes",
                                                 ">= 1",
                                                 value));
    }

    @Test
    void parse_unknownCostClass_failsNamingValue() {
        assertParseRefused(hetzner("", "cost_class = \"cheap\"", ""), "cost_class", "cheap");
    }

    @Test
    void parse_unknownFallback_failsNamingValue() {
        assertParseRefused(hetzner("", "fallback = \"maybe\"", ""), "fallback", "maybe");
    }

    @Test
    void validate_spotCostClassOnHetzner_returnsPf37() {
        parseAndValidate(hetzner("", "cost_class = \"spot\"", ""))
            .onSuccess(_ -> Assertions.fail("Hetzner has no spot arm; cost_class = spot must be refused"))
            .onFailure(cause -> assertThat(cause.message()).contains("PF-37: ").doesNotContain("PF-37x").contains("hetzner").contains("'hz'"));
    }

    @Test
    void validate_spotCostClassOnDockerSource_returnsPf37() {
        var toml = CLUSTER_HEAD.formatted("") + """
            [source.local]
            type = "docker"
            cost_class = "spot"

            [source.local.core]
            count = 3
            """;

        parseAndValidate(toml)
            .onSuccess(_ -> Assertions.fail("Docker sources cannot provide spot capacity"))
            .onFailure(cause -> assertThat(cause.message()).contains("PF-37").contains("docker"));
    }

    @Test
    void validate_ondemandAndReservedCostClassOnHetzner_notRefusedAsSpot() {
        List.of("ondemand", "reserved", "free")
            .forEach(value -> parseAndValidate(hetzner("", "cost_class = \"" + value + "\"", ""))
                .onFailure(cause -> assertThat(cause.message()).doesNotContain("PF-37")));
    }

    @Test
    void parse_clusterMaxNodes_becomesFirstClassField() {
        ClusterBootstrapConfigParser.parse(hetzner("max_nodes = 20", "", ""))
                                    .onFailure(cause -> Assertions.fail(cause.message()))
                                    .onSuccess(config -> assertThat(config.maxNodes()).isEqualTo(Option.some(20)));
        ClusterBootstrapConfigParser.parse(hetzner("", "", ""))
                                    .onFailure(cause -> Assertions.fail(cause.message()))
                                    .onSuccess(config -> assertThat(config.maxNodes()).isEqualTo(Option.none()));
    }

    @Test
    void parse_clusterMaxNodesBelowOne_failsNamingKey() {
        assertParseRefused(hetzner("max_nodes = 0", "", ""), "cluster.max_nodes", ">= 1");
    }

    @Test
    void overlay_clusterMaxNodes_reachesEveryNodesClusterSection() {
        var config = ClusterBootstrapConfigParser.parse(hetzner("max_nodes = 20", "", ""))
                                                 .fold(cause -> Assertions.<ClusterBootstrapConfig> fail(cause.message()),
                                                       parsed -> parsed);
        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    config.sources().get("hz"),
                                                    0,
                                                    Option.none(),
                                                    Option.none(),
                                                    Option.none(),
                                                    NodeRole.CORE,
                                                    List.of(),
                                                    List.of());

        assertThat(doc.getInt("cluster", "max_nodes")).isEqualTo(Option.some(20));
    }

    @Test
    void overlay_absentClusterMaxNodes_rendersNoKey() {
        var config = ClusterBootstrapConfigParser.parse(hetzner("", "", ""))
                                                 .fold(cause -> Assertions.<ClusterBootstrapConfig> fail(cause.message()),
                                                       parsed -> parsed);
        var doc = BootstrapOverlayGenerator.overlay(config,
                                                    config.sources().get("hz"),
                                                    0,
                                                    Option.none(),
                                                    Option.none(),
                                                    Option.none(),
                                                    NodeRole.CORE,
                                                    List.of(),
                                                    List.of());

        assertThat(doc.hasKey("cluster", "max_nodes")).isFalse();
    }

    @Test
    void validate_nodeConfigClusterMaxNodes_returnsPf38() {
        parseAndValidate(hetzner("", "", """
                                         [source.hz.node_config.cluster]
                                         max_nodes = 12
                                         """))
            .onSuccess(_ -> Assertions.fail("The fleet cap lives at [cluster] max_nodes; a per-source copy must be refused"))
            .onFailure(cause -> assertThat(cause.message()).contains("PF-38").contains("[cluster] max_nodes"));
    }

    @Test
    void diff_changedCapacityFieldsAndFleetCap_areReported() {
        var base = ClusterBootstrapConfigParser.parse(hetzner("", "", "")).unwrap();
        var changed = ClusterBootstrapConfigParser.parse(hetzner("max_nodes = 9",
                                                                 """
                                                                 max_nodes = 4
                                                                 preference = 5
                                                                 cost_class = "reserved"
                                                                 fallback = "forbid"
                                                                 """,
                                                                 """
                                                                 [source.hz.lead_time]
                                                                 core = "80s"
                                                                 """)).unwrap();
        var sourceFields = ClusterBootstrapConfigDiff.diff(base, changed)
                                                     .modifications()
                                                     .stream()
                                                     .filter(DiffAction.SourceFieldChange.class::isInstance)
                                                     .map(DiffAction.SourceFieldChange.class::cast)
                                                     .map(DiffAction.SourceFieldChange::field)
                                                     .toList();
        var clusterFields = ClusterBootstrapConfigDiff.diff(base, changed)
                                                      .modifications()
                                                      .stream()
                                                      .filter(DiffAction.ClusterLevelChange.class::isInstance)
                                                      .map(DiffAction.ClusterLevelChange.class::cast)
                                                      .map(DiffAction.ClusterLevelChange::field)
                                                      .toList();

        assertThat(sourceFields).containsExactlyInAnyOrder("leadTime", "maxNodes", "preference", "costClass", "fallback");
        assertThat(clusterFields).containsExactly("max_nodes");
        assertThat(ClusterBootstrapConfigDiff.diff(base, base).modifications()).isEmpty();
    }
}
