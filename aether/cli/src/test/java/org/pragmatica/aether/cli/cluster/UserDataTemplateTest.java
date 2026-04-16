// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.config.toml.TomlDocument;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Option.empty;


class UserDataTemplateTest {

    private static final String CLOUD_BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;

    @Test
    void render_embedsComposedTomlInWriteFilesBlock() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var overlay = BootstrapOverlayGenerator.overlay(config,
                                                        source,
                                                        "node-1",
                                                        0,
                                                        NodeRole.CORE,
                                                        List.of("node-1:1.2.3.4:6000"),
                                                        empty(),
                                                        empty(),
                                                        empty());

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret-xyz",
                                             "prod-cluster",
                                             overlay);

        assertTrue(script.contains("cat > /opt/aether/config/aether.toml"),
                   "Should write composed config to /opt/aether/config/aether.toml");
        assertTrue(script.contains("[cluster]"), "Should include cluster section from composed TOML");
        assertTrue(script.contains("name = \"prod-cluster\""), "Should include cluster name");
        assertTrue(script.contains("[node]"), "Should include node section");
        assertTrue(script.contains("id = \"node-1\""), "Should include node id");
    }

    @Test
    void render_includesContainerRunForContainerRuntime() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var overlay = TomlDocument.EMPTY;

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             "prod-cluster",
                                             overlay);

        assertTrue(script.contains("docker pull"), "Should pull image");
        assertTrue(script.contains("docker run"), "Should run container");
        assertTrue(script.contains("--config /config/aether.toml"), "Should mount config");
    }

    @Test
    void render_doesNotEmbedRedundantEnvVars() {
        // After Layer D refactor, AETHER_NODE_ID and AETHER_CLUSTER_SECRET are inside the composed
        // config, not passed as -e flags to docker run. They remain only as shell variables for
        // clarity in cloud-init logs.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var overlay = TomlDocument.EMPTY;

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             "prod-cluster",
                                             overlay);

        assertFalse(script.contains("-e AETHER_NODE_ID="),
                    "AETHER_NODE_ID should now come from composed config, not env var");
        assertFalse(script.contains("-e AETHER_CLUSTER_SECRET="),
                    "AETHER_CLUSTER_SECRET should now come from composed config, not env var");
    }
}
