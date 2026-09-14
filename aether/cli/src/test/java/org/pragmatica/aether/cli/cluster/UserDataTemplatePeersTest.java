// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.config.toml.TomlDocument;

import java.util.List;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Bug 14: peers value threading via the AETHER_PEERS shell variable / -e PEERS
/// docker flag, and the `--peers=` CLI flag for JVM-mode runtimes. The cloud
/// path provisions VMs before IPs are known, so the cloud user_data ships an
/// empty PEERS placeholder; the SSH / post-deploy paths populate it with the
/// canonical 3-part `nodeId:host:port,...` form.
class UserDataTemplatePeersTest {

    private static final String CLOUD_BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;

    private static final String JVM_BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

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

    @Nested
    class ContainerMode {

        @Test
        void render_emitsCommaJoinedThreePartPeers_whenSupplied() {
            var peers = List.of("eu-1-core-0:1.2.3.4:6000",
                                "eu-1-core-1:1.2.3.5:6001",
                                "eu-1-core-2:1.2.3.6:6002");
            var script = renderContainer(peers);

            assertTrue(script.contains("AETHER_PEERS=\"eu-1-core-0:1.2.3.4:6000,eu-1-core-1:1.2.3.5:6001,eu-1-core-2:1.2.3.6:6002\""),
                       "Peers must be joined by ',' (no spaces) so Main.parsePeersFromString " +
                       "splits them cleanly into 3-part NodeInfo entries");
        }

        @Test
        void render_emitsEmptyPeersPlaceholder_whenListEmpty() {
            var script = renderContainer(List.of());

            assertTrue(script.contains("AETHER_PEERS=\"\""),
                       "Empty peers must be a present-but-empty shell var so the Dockerfile " +
                       "entrypoint's ${PEERS:+--peers=$PEERS} skips emitting the flag");
        }

        @Test
        void render_alwaysExportsPeersAsContainerEnvVar() {
            var script = renderContainer(List.of("a:1.1.1.1:6000"));

            assertTrue(script.contains("-e PEERS=\"${AETHER_PEERS}\""),
                       "PEERS must always be passed via -e so the Dockerfile entrypoint conversion runs");
        }

        @Test
        void render_doesNotEmbedPeersInBindMountedConfig() {
            // Main.java does not read [cluster].peers from TOML — emitting it
            // would mislead operators reading the bind-mounted config.
            var script = renderContainer(List.of("eu-1-core-0:1.2.3.4:6000"));

            assertFalse(script.contains("peers = \"eu-1-core-0"),
                        "[cluster].peers in bind-mounted aether.toml is dead config — Main never reads it");
        }
    }

    @Nested
    class JvmMode {

        @Test
        void render_emitsNodeIdPortAndManagementPortFlags() {
            var script = renderJvm(List.of());

            assertTrue(script.contains("--node-id=\"${AETHER_NODE_ID}\""),
                       "JVM mode must pass --node-id= (=-form) so Main.findArg picks it up");
            assertTrue(script.contains("--port=\"${AETHER_CLUSTER_PORT}\""),
                       "JVM mode must pass --port= so Main.parsePort uses operator port");
            assertTrue(script.contains("--management-port=\"${AETHER_MANAGEMENT_PORT}\""),
                       "JVM mode must pass --management-port= so Main.parseManagementPort uses operator port");
        }

        /// #1021 moved the launch under systemd, so the peers conditional now lives in the launcher
        /// script the unit's ExecStart points at rather than in the cloud-init body. The PROPERTY is
        /// unchanged and is why the conditional was kept verbatim instead of being pushed into
        /// ExecStart: an empty peer list — the normal state of a bootstrap node's first boot — must
        /// omit `--peers` ENTIRELY, so the node falls through to Main's default peer chain rather than
        /// parsing an empty value.
        @Test
        void render_emitsPeersFlagOnlyWhenPeersListNonEmpty() {
            var withPeers = renderJvm(List.of("eu-1-core-0:1.2.3.4:6000"));
            var withoutPeers = renderJvm(List.of());

            assertTrue(withPeers.contains("--peers=${AETHER_PEERS}"),
                       "JVM mode must pass --peers= when AETHER_PEERS is non-empty");
            assertTrue(withoutPeers.contains("PEERS_ARG=\"\""),
                       "JVM mode must default PEERS_ARG to empty so the conditional check evaluates");
            assertTrue(withoutPeers.contains("if [ -n \"${AETHER_PEERS:-}\" ]"),
                       "JVM mode must guard --peers= behind a non-empty check on AETHER_PEERS so the " +
                       "node falls through to Main's default peer chain when peers are unknown");
        }

        /// #1021 — the secret used to be an inline `AETHER_CLUSTER_SECRET="..." java` prefix. It now
        /// reaches the JVM through the unit's EnvironmentFile, which is the same channel with a
        /// different carrier; the property being pinned is still that `Main.resolveClusterSecret`
        /// finds it in the process environment.
        @Test
        void render_emitsClusterSecretIntoTheUnitEnvFile() {
            var script = renderJvm(List.of());

            assertTrue(script.contains("AETHER_CLUSTER_SECRET=${AETHER_CLUSTER_SECRET}"),
                       "JVM mode must write AETHER_CLUSTER_SECRET into the systemd env file so "
                       + "Main.resolveClusterSecret reads it from the process environment (env fallback path)");
            assertTrue(script.contains("EnvironmentFile=-/etc/aether/node.env"),
                       "the unit must read that env file, or the secret never reaches the JVM");
        }

        /// #1021 — the node runs UNDER a unit, so a crash leaves a queryable local trace instead of a
        /// silent absence. `Restart=no` is asserted here as well as in `SystemdUnitTemplateTest`,
        /// because what ships to a VM is this rendered script and a template correct in isolation
        /// proves nothing about what was actually emitted.
        @Test
        void render_launchesTheJvmUnderASystemdUnit_thatDoesNotRestartIt() {
            var script = renderJvm(List.of());

            assertTrue(script.contains("/etc/systemd/system/aether-node.service"), "the unit file must be written");
            assertTrue(script.contains("systemctl daemon-reload"), "systemd must be told to re-read units");
            assertTrue(script.contains("systemctl enable --now aether-node.service"),
                       "the unit must be started AND linked into multi-user.target so a host reboot brings it back");
            assertTrue(script.contains("ExecStart=/opt/aether/run-node.sh"), "the unit must launch the node launcher");
            assertTrue(script.contains("exec java "),
                       "the launcher must exec so systemd's MAINPID is the JVM, not a wrapper shell");
            assertTrue(script.contains("Restart=no"),
                       "terminal-removal membership: a crashed node must NOT restart under the same identity");
            assertFalse(script.contains("Restart=on-failure") || script.contains("Restart=always"),
                        "a restarting policy resurrects a terminally-removed NodeId — see docs/operators/deployment-recovery.md");
            assertFalse(script.contains("nohup java"),
                        "#1021: the bare backgrounded launch is what left systemctl with nothing to report");
        }

        /// The env file carries AETHER_CLUSTER_SECRET, so it gets the same owner-only treatment #287
        /// gave `aether.toml` for the same secret. `chmod` precedes the write, so the bytes are never
        /// briefly world-readable.
        @Test
        void render_writesTheUnitEnvFileOwnerOnly_beforeWritingTheSecretIntoIt() {
            var script = renderJvm(List.of());

            assertTrue(script.contains("chmod 600 /etc/aether/node.env"), "the env file must be owner-only");
            assertTrue(script.indexOf("chmod 600 /etc/aether/node.env") < script.indexOf("AETHER_CLUSTER_SECRET=${AETHER_CLUSTER_SECRET}"),
                       "permissions must be set BEFORE the secret is written, never after");
        }
    }

    private static String renderContainer(List<String> peers) {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BASE).unwrap();
        var source = config.sources().get("eu-1");
        return UserDataTemplate.render(config,
                                       source,
                                       NodeRole.CORE,
                                       "eu-1-core-0",
                                       0,
                                       "test-secret",
                                       clusterName("prod-cluster").unwrap(),
                                       TomlDocument.EMPTY,
                                       List.of(),
                                       peers);
    }

    private static String renderJvm(List<String> peers) {
        var config = ClusterBootstrapConfigParser.parse(JVM_BASE).unwrap();
        var source = config.sources().get("eu-1");
        return UserDataTemplate.render(config,
                                       source,
                                       NodeRole.CORE,
                                       "eu-1-core-0",
                                       0,
                                       "test-secret",
                                       clusterName("prod-cluster").unwrap(),
                                       TomlDocument.EMPTY,
                                       List.of(),
                                       peers);
    }
}
