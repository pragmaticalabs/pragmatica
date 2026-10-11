// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;

/// #1968 (#1543 part A2): a replacement node renders the SAME `[backup]` as the cluster's committed configuration, in container
/// and JVM mode, together with the directory or volume the backup repository needs. The replacement pipeline is the CTM's: the
/// composed config comes from the persisted cluster TOML ([ReplacementNodeConfigComposer]) and is rendered by
/// [NodeUserDataRenderer]. A node booted from a `[backup]` section whose path does not exist (JVM) or lives in the container's
/// writable layer (container) loses its repository with the node.
class ReplacementBackupRenderTest {
    private static final String CLUSTER_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            [runtime.containers]
            type = "container"

            [runtime.bare-metal]
            type = "jvm"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "hcloud-token"

            [source.eu-1.core]
            count = 3
            runtime = "%s"
            %s
            """;

    private static final String BACKUP_NODE_CONFIG = """

            [source.eu-1.node_config.backup]
            enabled = true
            path = "/var/aether/backups"
            remote = "git@backups.example.com:ops/cluster.git"
            restore = "auto"
            """;

    private static final String DISABLED_BACKUP_NODE_CONFIG = """

            [source.eu-1.node_config.backup]
            enabled = false
            path = "/var/aether/backups"
            """;

    private static String replacementScript(String runtime, String nodeConfig) {
        var config = ClusterBootstrapConfigParser.parse(CLUSTER_TOML.formatted(runtime, nodeConfig)).unwrap();
        var source = config.sources().get("eu-1");
        var composed = ReplacementNodeConfigComposer.compose(config, source, NodeRole.CORE, Option.some("secret"), List.of()).unwrap();

        return NodeUserDataRenderer.render(config,
                                           source,
                                           NodeRole.CORE,
                                           "node-replacement",
                                           0,
                                           "secret",
                                           clusterName("prod-cluster").unwrap(),
                                           composed,
                                           List.of(),
                                           List.of("node-a:10.0.0.2:6000"));
    }

    @Test
    void containerReplacement_rendersTheCommittedBackup_andMountsItsDirectory() {
        var script = replacementScript("containers", BACKUP_NODE_CONFIG);

        assertThat(script).contains("[backup]")
                          .contains("path = \"/var/aether/backups\"")
                          .contains("remote = \"git@backups.example.com:ops/cluster.git\"");
        assertThat(script).as("the host directory exists and belongs to the in-container aether user before the node starts")
                          .contains("install -d -m 0750 -o 1000 -g 1000 /opt/aether/backups");
        assertThat(script).as("and is bind-mounted at the configured path, so the repository outlives the container")
                          .contains("-v /opt/aether/backups:/var/aether/backups \\");
        assertThat(script.indexOf("install -d -m 0750 -o 1000 -g 1000 /opt/aether/backups"))
            .as("created before docker run")
            .isLessThan(script.indexOf("docker run -d"));
    }

    @Test
    void jvmReplacement_rendersTheCommittedBackup_andCreatesItsDirectory() {
        var script = replacementScript("bare-metal", BACKUP_NODE_CONFIG);

        assertThat(script).contains("[backup]").contains("path = \"/var/aether/backups\"");
        assertThat(script).as("a JVM node writes the host path directly, so it must exist before systemd starts the node")
                          .contains("install -d -m 0750 /var/aether/backups");
        assertThat(script.indexOf("install -d -m 0750 /var/aether/backups")).isLessThan(script.indexOf("systemctl start"));
    }

    /// v-2001 blocker, at the real seam: the node TOML the REAL composer produces for a cloud container and a cloud JVM replacement,
    /// carrying `[backup] path = "/var/aether/backups"`, must LOAD (the node load applies the Docker `/data` rule only to an environment
    /// that was said, and a composed cloud TOML never says one).
    @Test
    void theComposedCloudNodeToml_withABackupOutsideData_loads_forContainerAndJvm() {
        for (var runtime : List.of("containers", "bare-metal")) {
            var config = ClusterBootstrapConfigParser.parse(CLUSTER_TOML.formatted(runtime, BACKUP_NODE_CONFIG)).unwrap();
            var source = config.sources().get("eu-1");
            var composed = ReplacementNodeConfigComposer.compose(config, source, NodeRole.CORE, Option.some("secret"), List.of()).unwrap();
            var toml = org.pragmatica.config.toml.TomlWriter.toToml(composed);

            assertThat(toml).as(runtime + ": CONTROL the backup section is in the composed TOML").contains("path = \"/var/aether/backups\"");
            assertThat(org.pragmatica.aether.config.ConfigLoader.loadFromString(toml).isSuccess()).as(runtime + " composed node TOML loads").isTrue();
        }
    }

    /// The controls: without `[backup]`, and with it disabled, nothing backup-related is rendered (no stray mount, no directory).
    @Test
    void noBackupOrDisabledBackup_rendersNoBackupDirectoryOrMount() {
        for (var runtime : List.of("containers", "bare-metal")) {
            for (var nodeConfig : List.of("", DISABLED_BACKUP_NODE_CONFIG)) {
                var script = replacementScript(runtime, nodeConfig);

                assertThat(script).as(runtime + " / " + (nodeConfig.isEmpty() ? "no [backup]" : "disabled"))
                                  .doesNotContain("/opt/aether/backups")
                                  .doesNotContain("install -d -m 0750 /var/aether/backups");
            }
        }
    }
}
