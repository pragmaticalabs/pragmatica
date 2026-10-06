// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.config.toml.TomlDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;


/// #1543 part C: the committed TOML's `[cluster] version` is what replacements render. These pin the rewrite
/// (only that line, read back through the real parser, rendered into the replacement's image tag / jar URL) and the
/// detection of pins that would make the rewrite a no-op.
class ClusterUpgradeTomlTest {
    private static final String BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0" # operator note

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            %s

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            %s
            """;

    private static String toml(String runtimeTable, String coreRuntimeRef) {
        return BASE.formatted(runtimeTable, coreRuntimeRef);
    }

    @Test
    void withVersion_rewritesOnlyTheClusterVersionLine_keepingTheComment() {
        var original = toml("", "");
        var rewritten = ClusterUpgradeToml.withVersion(original, "1.1.0").unwrap();

        assertThat(rewritten).isEqualTo(original.replace("version = \"1.0.0\" # operator note",
                                                         "version = \"1.1.0\" # operator note"));
    }

    @Test
    void withVersion_leavesAVersionKeyInAnotherSectionAlone() {
        var original = toml("[runtime.node]\ntype = \"container\"\nversion = \"keep-me\"", "runtime = \"node\"");
        var rewritten = ClusterUpgradeToml.withVersion(original, "1.1.0").unwrap();

        assertThat(rewritten).contains("version = \"keep-me\"").contains("version = \"1.1.0\" # operator note");
        assertThat(ClusterBootstrapConfigParser.parse(rewritten).unwrap().cluster().version()).isEqualTo("1.1.0");
    }

    @Test
    void withVersion_refusesAConfigWithNoVersionLineUnderCluster() {
        var noVersion = toml("", "").replace("version = \"1.0.0\" # operator note\n", "");

        assertThat(ClusterUpgradeToml.withVersion(noVersion, "1.1.0").isFailure()).isTrue();
    }

    @Test
    void withVersion_refusesAVersionThatCouldBreakOutOfTheTomlString() {
        assertThat(ClusterUpgradeToml.withVersion(toml("", ""), "1.1.0\"\nname = \"x").isFailure()).isTrue();
    }

    /// The consequence the whole change exists for: a replacement rendered from the rewritten TOML boots the target.
    @Test
    void renderedReplacement_afterRewrite_carriesTheTargetImageTag() {
        var rewritten = ClusterUpgradeToml.withVersion(toml("", ""), "1.1.0").unwrap();

        assertThat(render(rewritten)).contains("aether-node:1.1.0").doesNotContain("aether-node:1.0.0");
    }

    @Test
    void renderedReplacement_afterRewrite_carriesTheTargetJarUrl() {
        var jvm = toml("[runtime.bare-metal]\ntype = \"jvm\"", "runtime = \"bare-metal\"");
        var rewritten = ClusterUpgradeToml.withVersion(jvm, "1.1.0").unwrap();

        assertThat(render(rewritten)).contains("/releases/download/v1.1.0/aether-node.jar");
    }

    @Test
    void pinnedRuntimeProfiles_namesAContainerProfileThatPinsAnImage() {
        assertThat(pinned(toml("[runtime.node]\ntype = \"container\"\nimage = \"registry/x:1.0.0\"",
                               "runtime = \"node\""))).containsExactly("node");
    }

    @Test
    void pinnedRuntimeProfiles_namesAJvmProfileThatPinsAJarUrl() {
        assertThat(pinned(toml("[runtime.bare-metal]\ntype = \"jvm\"\njar_url = \"https://h/x.jar\"",
                               "runtime = \"bare-metal\""))).containsExactly("bare-metal");
    }

    /// An image on a JVM profile (or a jar_url on a container profile) is never what the renderer launches, so it
    /// does not make the version ineffective.
    @Test
    void pinnedRuntimeProfiles_ignoresAFieldTheRuntimeTypeNeverLaunches() {
        assertThat(pinned(toml("[runtime.bare-metal]\ntype = \"jvm\"\nimage = \"registry/x:1.0.0\"",
                               "runtime = \"bare-metal\""))).isEmpty();
        assertThat(pinned(toml("[runtime.node]\ntype = \"container\"\njar_url = \"https://h/x.jar\"",
                               "runtime = \"node\""))).isEmpty();
    }

    @Test
    void pinnedRuntimeProfiles_ignoresAPinnedProfileNoRoleReferences() {
        assertThat(pinned(toml("[runtime.node]\ntype = \"container\"\nimage = \"registry/x:1.0.0\"", ""))).isEmpty();
    }

    private static List<String> pinned(String toml) {
        return ClusterUpgradeToml.pinnedRuntimeProfiles(ClusterBootstrapConfigParser.parse(toml).unwrap());
    }

    private static String render(String toml) {
        var config = ClusterBootstrapConfigParser.parse(toml).unwrap();

        return NodeUserDataRenderer.render(config,
                                           config.sources().get("eu-1"),
                                           NodeRole.CORE,
                                           "eu-1-core-0",
                                           0,
                                           "test-secret",
                                           clusterName("prod-cluster").unwrap(),
                                           TomlDocument.EMPTY,
                                           List.of(),
                                           List.of());
    }
}
