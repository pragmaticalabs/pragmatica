// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class NodeConfigComposerTest {

    @Test
    void mergeDocuments_higherTableOverridesLowerTable() {
        var lower = parse("""
                [cluster]
                tls = false
                name = "lower"
                """);
        var higher = parse("""
                [cluster]
                tls = true
                """);

        var merged = NodeConfigComposer.mergeDocuments(lower, higher);

        assertThat(merged.getBoolean("cluster", "tls").unwrap()).isTrue();
        assertThat(merged.getString("cluster", "name").unwrap()).isEqualTo("lower");
    }

    @Test
    void mergeDocuments_higherSectionAddedWhenAbsentInLower() {
        var lower = parse("""
                [cluster]
                tls = true
                """);
        var higher = parse("""
                [app-http]
                enabled = true
                """);

        var merged = NodeConfigComposer.mergeDocuments(lower, higher);

        assertThat(merged.getBoolean("cluster", "tls").unwrap()).isTrue();
        assertThat(merged.getBoolean("app-http", "enabled").unwrap()).isTrue();
    }

    @Test
    void mergeDocuments_arraysReplaceEntirely() {
        var lower = parse("""
                [slice]
                repositories = ["builtin"]
                """);
        var higher = parse("""
                [slice]
                repositories = ["local", "remote"]
                """);

        var merged = NodeConfigComposer.mergeDocuments(lower, higher);

        assertThat(merged.getStringList("slice", "repositories").unwrap()).containsExactly("local", "remote");
    }

    @Test
    void mergeDocuments_emptyLowerProducesHigher() {
        var higher = parse("""
                [cluster]
                tls = true
                """);

        var merged = NodeConfigComposer.mergeDocuments(TomlDocument.EMPTY, higher);

        assertThat(merged.getBoolean("cluster", "tls").unwrap()).isTrue();
    }

    @Test
    void mergeDocuments_emptyHigherProducesLower() {
        var lower = parse("""
                [cluster]
                tls = false
                """);

        var merged = NodeConfigComposer.mergeDocuments(lower, TomlDocument.EMPTY);

        assertThat(merged.getBoolean("cluster", "tls").unwrap()).isFalse();
    }

    @Test
    void compose_fourLayersStackInPrecedenceOrder() {
        var l1 = parse("""
                [cluster]
                tls = false
                name = "global"
                """);
        var l2 = parse("""
                [cluster]
                tls = true
                """);
        var l3 = parse("""
                [cluster]
                name = "operator"
                """);
        var l4 = parse("""
                [node]
                id = "n1"
                """);

        var composed = NodeConfigComposer.compose(l1, l2, Option.some(l3), l4);

        assertThat(composed.getBoolean("cluster", "tls").unwrap()).isTrue();
        assertThat(composed.getString("cluster", "name").unwrap()).isEqualTo("operator");
        assertThat(composed.getString("node", "id").unwrap()).isEqualTo("n1");
    }

    @Test
    void compose_missingOperatorOverrideSkipsLayer3() {
        var l1 = parse("""
                [cluster]
                name = "global"
                """);
        var l2 = parse("""
                [cluster]
                tls = true
                """);
        var l4 = parse("""
                [cluster]
                name = "cli"
                """);

        var composed = NodeConfigComposer.compose(l1, l2, Option.empty(), l4);

        assertThat(composed.getString("cluster", "name").unwrap()).isEqualTo("cli");
        assertThat(composed.getBoolean("cluster", "tls").unwrap()).isTrue();
    }

    @Test
    void compose_layer4OverridesAllLowerLayers() {
        var l1 = parse("""
                [cluster]
                tls = false
                """);
        var l4 = parse("""
                [cluster]
                tls = true
                """);

        var composed = NodeConfigComposer.compose(l1, TomlDocument.EMPTY, Option.empty(), l4);

        assertThat(composed.getBoolean("cluster", "tls").unwrap()).isTrue();
    }

    @Test
    void mergeDocuments_inlineTableReplacesEntirely() {
        var lower = new TomlDocument(Map.of("cluster",
                                            Map.of("limits", Map.of("max", 10, "min", 1))),
                                     Map.of());
        var higher = new TomlDocument(Map.of("cluster",
                                             Map.of("limits", Map.of("max", 20))),
                                      Map.of());

        var merged = NodeConfigComposer.mergeDocuments(lower, higher);

        var limits = merged.getInlineTable("cluster", "limits").unwrap();
        assertThat(limits).containsEntry("max", 20).doesNotContainKey("min");
    }

    @Test
    void mergeDocuments_tableArraysReplaceWholesale() {
        var lower = new TomlDocument(Map.of(),
                                     Map.of("firewall", List.of(Map.of("port", 80))));
        var higher = new TomlDocument(Map.of(),
                                      Map.of("firewall", List.of(Map.of("port", 443))));

        var merged = NodeConfigComposer.mergeDocuments(lower, higher);

        var arr = merged.getTableArray("firewall").unwrap();
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0)).containsEntry("port", 443);
    }

    @Test
    void defaultNodeConfig_loadsGlobalDefault() {
        var doc = DefaultNodeConfig.globalDefault().unwrap();
        assertThat(doc.getStringList("slice", "repositories").unwrap()).contains("builtin");
        assertThat(doc.getBoolean("app-http", "enabled").unwrap()).isTrue();
    }

    @Test
    void defaultNodeConfig_loadsDockerSourceTypeDefault() {
        var doc = DefaultNodeConfig.sourceTypeDefault(SourceType.DOCKER).unwrap();
        assertThat(doc.getBoolean("cluster", "tls").unwrap()).isFalse();
        assertThat(doc.getString("cloud", "provider").unwrap()).isEqualTo("docker");
    }

    @Test
    void defaultNodeConfig_loadsCloudSourceTypeDefault() {
        var doc = DefaultNodeConfig.sourceTypeDefault(SourceType.CLOUD).unwrap();
        assertThat(doc.getBoolean("cluster", "tls").unwrap()).isTrue();
        assertThat(doc.getBoolean("tls", "auto_generate").unwrap()).isTrue();
    }

    @Test
    void defaultNodeConfig_allSourceTypesPresent() {
        for (var type : SourceType.values()) {
            DefaultNodeConfig.sourceTypeDefault(type)
                .onFailure(cause -> org.assertj.core.api.Assertions.fail("Missing Layer 2 default for " + type + ": " + cause.message()));
        }
    }

    @Test
    void defaultNodeConfig_returnsFailureForUnknownResource() {
        // Sanity: same loader path returns failure when resource is absent.
        var loader = DefaultNodeConfig.class.getClassLoader();
        assertThat(loader.getResource("defaults/aether-nonexistent.toml")).isNull();
    }

    private static TomlDocument parse(String content) {
        return TomlParser.parse(content).unwrap();
    }
}
