// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class NodeUserDataRendererTest {
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
            %s

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            runtime = "bare-metal"
            """;

    /// #966 — a node that exhausts its heap must die, not hang: without `-XX:+ExitOnOutOfMemoryError`
    /// the OOM is caught somewhere (a `catch (Throwable)`, a Netty loop), the SWIM thread keeps
    /// answering pings from the headroom the dead allocators left, the node is never marked FAULTY,
    /// and its membership slot is held indefinitely — ten days in the incident. The flag makes HotSpot
    /// `_exit(3)` on the first unsatisfiable heap/Metaspace allocation, so the existing `kill -9`
    /// recovery path (SUSPECT → FAULTY → terminal removal → CTM auto-heal) runs.
    ///
    /// It is asserted ON THE `java` TOKEN, ahead of the operator's `jvm_args`, because the operator's
    /// args are the part a deployment replaces wholesale; a flag carried inside them would be dropped
    /// by the first override. What is pinned is ORDER, not immunity: HotSpot takes the last occurrence,
    /// so an operator's `-XX:-ExitOnOutOfMemoryError` in `jvm_args` (or `_JAVA_OPTIONS` in the
    /// environment) still disables it — that is their explicit opt-out. The `-jar` assertion is the
    /// control: it proves the string examined is the launcher line and not some other mention of `java`.
    @Nested
    class ExitOnOutOfMemoryIsPinned {
        @Test
        void render_jvmLauncher_carriesExitOnOomOnTheJavaToken() {
            assertExitOnOomIsOnTheJavaToken(renderJvm(""));
        }

        @Test
        void render_jvmLauncher_operatorJvmArgs_comeAfterTheFlag_onTheSameExecLine() {
            var script = renderJvm("jvm_args = \"-Xmx2g -XX:+UseZGC\"");

            assertExitOnOomIsOnTheJavaToken(script);
            assertTrue(script.contains("exec java -XX:+ExitOnOutOfMemoryError -Xmx2g -XX:+UseZGC -jar"),
                       () -> "operator jvm_args must come AFTER the flag, on the same exec line. Got:\n" + script);
        }

        private void assertExitOnOomIsOnTheJavaToken(String script) {
            var launcher = script.indexOf("exec java -XX:+ExitOnOutOfMemoryError ");
            var jar = script.indexOf("-jar /opt/aether/aether-node.jar");

            assertTrue(jar >= 0,
                       () -> "control: the rendered script must contain the node launcher line. Got:\n" + script);
            assertTrue(launcher >= 0 && launcher < jar,
                       () -> "#966: the JVM-mode launcher must exec java with -XX:+ExitOnOutOfMemoryError on the "
                            + "java token, before -jar, so an exhausted heap kills the node instead of leaving it "
                            + "answering SWIM pings from a dead process. See "
                            + "aether/docs/operators/deployment-recovery.md §4.5. Got:\n" + script);
        }
    }

    private static final String CONTAINER_BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 5
            """;

    /// #1519 — #1390 made the node refuse to boot without an absolute `cluster.consensus_path`, and no
    /// shipped config set one: 5 of 5 cloud nodes aborted. The cloud and SSH source-type defaults now
    /// set it to [NodeUserDataRenderer#CONSENSUS_PATH], and the rendered user-data must keep that path on
    /// the VM disk: created before launch, and bind-mounted at the same path into the container, so a
    /// recreated container (the bootstrap re-launch recreates it) still finds its journal. Each test
    /// renders from the config the production composer builds, so removing the `consensus_path` line
    /// from `defaults/aether-cloud.toml` reddens it. `ShippedTemplateControlStorageTest` (aether/node)
    /// resolves the same composed config through the node's own boot gate.
    @Nested
    class DurableControlStateIsOnTheVmDisk {
        @Test
        void composedCloudConfig_setsConsensusPath_insideTheNodeStateDir() {
            var composed = composedCloud();

            assertEquals(Option.some(NodeUserDataRenderer.CONSENSUS_PATH).toString(),
                         composed.getString("cluster", "consensus_path").toString(),
                         "defaults/aether-cloud.toml must set cluster.consensus_path (#1519)");
            assertTrue(NodeUserDataRenderer.CONSENSUS_PATH.startsWith(NodeUserDataRenderer.NODE_STATE_DIR + "/"),
                       "consensus_path must live inside the mounted node state dir");
        }

        @Test
        void composedSshConfig_setsConsensusPath_insideTheNodeStateDir() {
            var composed = Result.all(DefaultNodeConfig.globalDefault(),
                                      DefaultNodeConfig.sourceTypeDefault(SourceType.SSH))
                                 .map((global, typeDefault) -> NodeConfigComposer.compose(global,
                                                                                          typeDefault,
                                                                                          Option.none(),
                                                                                          TomlDocument.EMPTY))
                                 .unwrap();

            assertEquals(Option.some(NodeUserDataRenderer.CONSENSUS_PATH).toString(),
                         composed.getString("cluster", "consensus_path").toString(),
                         "defaults/aether-ssh.toml must set cluster.consensus_path (#1519)");
        }

        @Test
        void render_container_writesConsensusPath_createsStateDir_andBindMountsIt() {
            var script = renderContainer();
            var install = script.indexOf(NodeUserDataRenderer.CONTAINER_STATE_DIR_INSTALL + "\n");
            var run = script.indexOf("docker run -d");
            var mount = script.indexOf("    -v /var/lib/aether:/var/lib/aether \\\n");

            assertTrue(script.contains("consensus_path = \"/var/lib/aether/aether-control\""),
                       () -> "the written aether.toml must carry consensus_path. Got:\n" + script);
            assertTrue(install >= 0 && run >= 0 && install < run,
                       () -> "the state dir must be created (uid 1000) before docker run. Got:\n" + script);
            assertTrue(mount > run,
                       () -> "docker run must bind-mount the VM-disk state dir at the same path. Got:\n" + script);
        }

        @Test
        void render_jvm_writesConsensusPath_andCreatesStateDirBeforeTheUnitStarts() {
            var jvmConfig = ClusterBootstrapConfigParser.parse(JVM_BASE.formatted("")).unwrap();
            var script = NodeUserDataRenderer.render(jvmConfig,
                                                     jvmConfig.sources().get("eu-1"),
                                                     NodeRole.CORE,
                                                     "eu-1-core-0",
                                                     0,
                                                     "test-secret",
                                                     clusterName("prod-cluster").unwrap(),
                                                     composedCloud(),
                                                     List.of(),
                                                     List.of());
            var install = script.indexOf(NodeUserDataRenderer.JVM_STATE_DIR_INSTALL + "\n");
            var start = script.indexOf("systemctl enable --now");

            assertTrue(script.contains("consensus_path = \"/var/lib/aether/aether-control\""),
                       () -> "the written aether.toml must carry consensus_path. Got:\n" + script);
            assertTrue(install >= 0 && start >= 0 && install < start,
                       () -> "the state dir must be created before the unit starts. Got:\n" + script);
        }

        private static TomlDocument composedCloud() {
            var config = ClusterBootstrapConfigParser.parse(CONTAINER_BASE).unwrap();

            return ReplacementNodeConfigComposer.compose(config, config.sources().get("eu-1"), Option.some("secret"))
                                                .unwrap();
        }

        private static String renderContainer() {
            var config = ClusterBootstrapConfigParser.parse(CONTAINER_BASE).unwrap();

            return NodeUserDataRenderer.render(config,
                                               config.sources().get("eu-1"),
                                               NodeRole.CORE,
                                               "eu-1-core-0",
                                               0,
                                               "test-secret",
                                               clusterName("prod-cluster").unwrap(),
                                               composedCloud(),
                                               List.of(),
                                               List.of());
        }
    }

    private static String renderJvm(String runtimeExtra) {
        var config = ClusterBootstrapConfigParser.parse(JVM_BASE.formatted(runtimeExtra)).unwrap();

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
