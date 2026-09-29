// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import org.pragmatica.config.toml.TomlDocument;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// #1650: a node's `AETHER_SOURCE` and `AETHER_ZONE` are ITS OWN -- the source it is rendered from -- never
    /// the rendering host's. Workers are rendered on the leader, and a node learns its source only from this
    /// variable (`Main` → the SWIM `source` label → its community); inherited, every core-provisioned worker
    /// came up as the leader's source, or as `default`, and a multi-source cluster collapsed into one community.
    @Nested
    class NodeOwnSourceAndZone {
        private static final String TWO_SOURCES = """
                config_version = "1.0.0"

                [cluster]
                name = "prod-cluster"
                version = "1.0.0"

                [runtime.bare-metal]
                type = "jvm"

                [runtime.containers]
                type = "container"

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"
                zone = "nbg1"

                [source.eu-1.core]
                count = 3
                runtime = "bare-metal"

                [source.eu-2]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"
                zone = "fsn1"

                [source.eu-2.worker]
                count = 2
                runtime = "%s"

                [source.eu-3]
                type = "cloud"
                provider = "hetzner"
                region = "eu-central"
                zones = ["hel1", "fsn1"]

                [source.eu-3.worker]
                count = 2
                runtime = "bare-metal"
                """;

        /// The host env names ANOTHER source and zone (a leader's own). The emitted values are the node's.
        @Test
        void emitIdentityEnv_hostEnvCarriesAnotherSource_emitsTheNodesOwnSourceAndZone() {
            var emitted = emitWith(Map.of("AETHER_SOURCE", "leader-source", "AETHER_ZONE", "leader-zone"),
                                   Option.some("fsn1"));

            assertEquals("eu-2", emitted.get("AETHER_SOURCE"), () -> "AETHER_SOURCE must be the node's source: " + emitted);
            assertEquals("fsn1", emitted.get("AETHER_ZONE"), () -> "AETHER_ZONE must be the node's zone: " + emitted);
        }

        /// A node whose landing zone is not known must NOT inherit the host's zone -- it is left absent.
        @Test
        void emitIdentityEnv_zoneNotKnown_leavesZoneAbsent_evenWhenTheHostHasOne() {
            var emitted = emitWith(Map.of("AETHER_ZONE", "leader-zone"), Option.none());

            assertFalse(emitted.containsKey("AETHER_ZONE"), () -> "no inherited zone: " + emitted);
            assertEquals("eu-2", emitted.get("AETHER_SOURCE"));
        }

        @Test
        void render_jvmWorker_stampsItsSourceAndSingleZone_inTheEnvFile() {
            var script = renderWorker("eu-2", "bare-metal");

            assertTrue(script.contains("\nAETHER_SOURCE=eu-2\n"), () -> "JVM env file must carry the node's source. Got:\n" + script);
            assertTrue(script.contains("\nAETHER_ZONE=fsn1\n"), () -> "JVM env file must carry the node's zone. Got:\n" + script);
        }

        @Test
        void render_containerWorker_stampsItsSourceAndSingleZone_onTheDockerRun() {
            var script = renderWorker("eu-2", "containers");

            assertTrue(script.contains("-e AETHER_SOURCE=\"eu-2\""), () -> "docker run must carry the node's source. Got:\n" + script);
            assertTrue(script.contains("-e AETHER_ZONE=\"fsn1\""), () -> "docker run must carry the node's zone. Got:\n" + script);
        }

        /// A multi-zone source is rotated through AFTER rendering, so the zone is unknown: stamped source, no zone.
        @Test
        void render_multiZoneSource_stampsSource_andLeavesZoneAbsent() {
            var script = renderWorker("eu-3", "bare-metal");

            assertTrue(script.contains("\nAETHER_SOURCE=eu-3\n"), () -> "source is always stamped. Got:\n" + script);
            assertFalse(script.contains("AETHER_ZONE="), () -> "an unknown landing zone must stay absent. Got:\n" + script);
        }

        private Map<String, String> emitWith(Map<String, String> hostEnv, Option<String> zone) {
            var emitted = new LinkedHashMap<String, String>();

            NodeUserDataRenderer.emitIdentityEnv((name, value) -> record(emitted, name, value),
                                                 clusterName("prod-cluster").unwrap(),
                                                 NodeRole.WORKER,
                                                 SourceName.sourceName("eu-2").unwrap(),
                                                 zone,
                                                 Option.none(),
                                                 hostEnv::get);

            return emitted;
        }

        private static Unit record(Map<String, String> emitted, String name, String value) {
            assertFalse(emitted.containsKey(name), () -> name + " emitted twice");
            emitted.put(name, value);

            return Unit.unit();
        }

        private String renderWorker(String source, String runtime) {
            var config = ClusterBootstrapConfigParser.parse(TWO_SOURCES.formatted(runtime)).unwrap();

            return NodeUserDataRenderer.render(config,
                                               config.sources().get(source),
                                               NodeRole.WORKER,
                                               source + "-worker-0",
                                               0,
                                               "test-secret",
                                               clusterName("prod-cluster").unwrap(),
                                               TomlDocument.EMPTY,
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
