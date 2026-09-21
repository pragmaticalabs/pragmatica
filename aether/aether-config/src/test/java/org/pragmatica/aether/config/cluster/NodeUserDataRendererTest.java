// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.config.toml.TomlDocument;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
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
    /// by the first override. The `-jar` assertion is the control: it proves the string examined is
    /// the launcher line and not some other mention of `java`.
    @Nested
    class ExitOnOutOfMemoryIsPinned {
        @Test
        void render_jvmLauncher_carriesExitOnOomOnTheJavaToken() {
            assertExitOnOomIsOnTheJavaToken(renderJvm(""));
        }

        @Test
        void render_jvmLauncher_operatorJvmArgs_followTheFlagAndCannotDisplaceIt() {
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
