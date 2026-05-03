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
        assertTrue(script.contains("-v /opt/aether/config/aether.toml:/app/aether.toml:ro"),
                   "Must bind-mount composed config FILE over bundled /app/aether.toml so the image's "
                   + "hardcoded ENTRYPOINT --config=/app/aether.toml flag picks up the operator config");
        assertFalse(script.contains("-v /opt/aether/config:/config:ro"),
                    "Must NOT bind-mount the directory at /config — Main.findArg() returns first match "
                    + "and the entrypoint's --config=/app/aether.toml would win, ignoring our config");
        assertFalse(script.contains("--config /config/aether.toml"),
                    "Must NOT pass space-separated --config arg: Main.findArg(\"--config=\") only "
                    + "matches the =-form, so the flag would be silently dropped");
        assertFalse(script.contains("--config=/config/aether.toml"),
                    "Must NOT pass an extra trailing --config arg either: the bundled entrypoint already "
                    + "passes --config=/app/aether.toml first; appending another is dead code");
    }

    @Test
    void render_chmodsConfigSoInContainerAetherUserCanRead() {
        // The aether-node Dockerfile runs as user `aether` (uid 1000). Bind-mounted files inherit
        // the host file mode; without chmod the in-container user may be unable to read root-owned
        // 0600 files written by cloud-init.
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             "prod-cluster",
                                             TomlDocument.EMPTY);

        var configWriteIdx = script.indexOf("AETHER_CONFIG\n");
        var chmodIdx = script.indexOf("chmod 644 /opt/aether/config/aether.toml");
        var dockerRunIdx = script.indexOf("docker run");

        assertTrue(chmodIdx > 0, "Must chmod 644 the composed config so uid 1000 can read it");
        assertTrue(configWriteIdx > 0 && chmodIdx > configWriteIdx,
                   "chmod must happen after the heredoc write");
        assertTrue(dockerRunIdx > chmodIdx,
                   "chmod must happen before docker run so the bind-mount sees readable file");
    }

    @Test
    void render_jvmModeUsesEqualsFormConfigArg() {
        // JVM-mode uses Main.findArg("--config=") which only matches the =-form. The
        // space-separated form was silently ignored, causing the node to fall back to
        // bundled defaults (Bug 13).
        var jvmToml = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [runtime.bare-metal]
                type = "jvm"

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"

                [source.eu-1.core]
                count = 3
                runtime = "bare-metal"
                """;
        var config = ClusterBootstrapConfigParser.parse(jvmToml).unwrap();
        var source = config.sources().get("eu-1");

        var script = UserDataTemplate.render(config,
                                             source,
                                             NodeRole.CORE,
                                             "node-1",
                                             0,
                                             "secret",
                                             "prod-cluster",
                                             TomlDocument.EMPTY);

        assertTrue(script.contains("-jar /opt/aether/aether-node.jar"),
                   "JVM-mode must launch the downloaded jar");
        assertTrue(script.contains("--config=/opt/aether/config/aether.toml"),
                   "JVM-mode must use --config= (=-form) so Main.findArg picks it up");
        assertFalse(script.contains("--config /opt/aether/config/aether.toml"),
                    "JVM-mode must NOT use space-separated --config: Main.findArg(\"--config=\") "
                    + "would silently ignore it and the node would fall back to bundled defaults");
        assertFalse(script.contains("docker run"), "JVM-mode must not invoke docker run");
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
