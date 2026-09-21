// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// #966 — every launcher that starts a real aether-node JVM carries `-XX:+ExitOnOutOfMemoryError` on
/// the `java` TOKEN. A node that exhausts its heap must die: without the flag the OOM is caught (a
/// `catch (Throwable)`, a Netty loop), the SWIM thread keeps answering pings from the headroom the dead
/// allocators left, the node is never marked FAULTY, and it holds its membership slot indefinitely —
/// ten days in the incident. With it HotSpot `_exit(3)`s on the first unsatisfiable heap/Metaspace
/// allocation, and the existing `kill -9` recovery (SUSPECT → FAULTY → terminal removal → CTM auto-heal)
/// runs. `OomExitProbeTest` proves the flag does that; this class proves each launcher HAS it.
///
/// WHY THESE ARE TEXT PINS OVER FILES. The launchers are shell scripts, a Dockerfile and heredoc
/// templates — text artifacts with no Java seam to call. The subject under test is therefore the file
/// on disk, read from the repository (as `PublishedImageConfigFailsClosedTest` reads the Dockerfile),
/// so deleting the flag from one file reddens exactly that file's test. The flag must sit on the `java`
/// token and NOT inside `JAVA_OPTS`/`AETHER_JAVA_OPTS`: compose files and operators replace those
/// wholesale (`docker-compose.yml` sets `JAVA_OPTS: "-Xmx256m -XX:+UseZGC"`), and a flag carried there
/// would be dropped by the first override.
///
/// Each test carries its own control — the same launcher line must also name the node jar — so a pin
/// cannot pass on a file whose launcher line it never found. Forge launchers must NOT carry the flag:
/// Forge runs a whole simulated cluster in one JVM, and one OOM would kill every simulated node.
class NodeLauncherExitOnOomPinnedTest {
    private static final String FLAG = "-XX:+ExitOnOutOfMemoryError";
    private static final String NODE_DOCKERFILE = "aether/docker/aether-node/Dockerfile";
    private static final String BUILD_AND_PUSH = "build-and-push.sh";
    private static final String INSTALL_SH = "aether/install.sh";
    private static final String UPGRADE_SH = "aether/upgrade.sh";
    private static final String AETHER_NODE_SH = "aether/script/aether-node.sh";
    private static final String DEMO_CLUSTER_SH = "aether/script/demo-cluster.sh";
    private static final String BUILD_DIST_SH = "aether/dist/build-dist.sh";

    private static final String CLOUD_NODE_JAVA = "aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/CloudNode.java";

    @Nested
    class EveryNodeLauncherCarriesTheFlag {
        /// The published image. `java` and `-jar` sit on different continuation lines of the ENTRYPOINT,
        /// so the pin is on the `java` line and the control on the `-jar` line of the same file.
        @Test
        void nodeDockerfile_entrypointJavaToken_carriesTheFlag() {
            var dockerfile = read(NODE_DOCKERFILE);

            assertThat(dockerfile).as("control: the image entrypoint launches the node jar")
                      .contains("-jar /app/aether-node.jar");
            assertThat(launcherLine(dockerfile, "$JAVA_OPTS")).as("#966: %s ENTRYPOINT must put %s on the java token, BEFORE $JAVA_OPTS",
                                                                  NODE_DOCKERFILE,
                                                                  FLAG)
                      .startsWith("java " + FLAG + " $JAVA_OPTS");
        }

        /// The remote-build path writes its own `Dockerfile.local`.
        @Test
        void buildAndPush_generatedDockerfile_entrypointCarriesTheFlag() {
            var line = launcherLine(read(BUILD_AND_PUSH), "-jar /app/aether-node.jar");

            assertThat(line).as("#966: %s ENTRYPOINT must put %s on the java token, BEFORE ${JAVA_OPTS}",
                                BUILD_AND_PUSH,
                                FLAG)
                      .contains("exec java " + FLAG + " \\${JAVA_OPTS:-");
        }

        @Test
        void installSh_nodeWrapper_carriesTheFlag() {
            assertShellNodeWrapper(INSTALL_SH);
        }

        @Test
        void upgradeSh_nodeWrapper_carriesTheFlag() {
            assertShellNodeWrapper(UPGRADE_SH);
        }

        @Test
        void aetherNodeSh_carriesTheFlag() {
            var line = launcherLine(read(AETHER_NODE_SH), "-jar \"$JAR_FILE\"");

            assertThat(line).as("#966: %s must exec java with %s on the java token", AETHER_NODE_SH, FLAG)
                      .startsWith("exec java " + FLAG + " -jar");
        }

        @Test
        void demoClusterSh_nodeLaunch_carriesTheFlag() {
            var line = launcherLine(read(DEMO_CLUSTER_SH), "-jar \"$NODE_JAR\"");

            assertThat(line).as("#966: %s must launch each node with %s on the java token", DEMO_CLUSTER_SH, FLAG)
                      .startsWith("java " + FLAG + " -jar");
        }

        /// `create_launcher` is shared by the node, CLI and forge distributions, so the flag lives in
        /// the NODE component's baked-in option literal (a build-time constant, not an env variable)
        /// and the forge component's literal must stay without it.
        @Test
        void buildDistSh_nodeComponentOptions_carryTheFlag_andForgeDoesNot() {
            var script = read(BUILD_DIST_SH);
            var nodeJar = script.indexOf("\"$AETHER_DIR/node/target/aether-node.jar\"");
            var nodeOpts = script.indexOf("\"" + FLAG + " -XX:+UseZGC -Xmx512m\"");
            var cliBuild = script.indexOf("\"aether-cli\"");

            assertThat(nodeJar).as("control: %s builds the node component", BUILD_DIST_SH).isNotNegative();
            assertThat(nodeOpts).as("#966: %s node component java_opts must start with %s", BUILD_DIST_SH, FLAG)
                      .isGreaterThan(nodeJar)
                      .isLessThan(cliBuild);
            assertThat(script).as("forge must never carry the flag: one OOM would kill an in-JVM simulated cluster")
                      .contains("\"-XX:+UseZGC -Xmx1g\"")
                      .doesNotContain(FLAG + " -XX:+UseZGC -Xmx1g");
        }

        /// The cloud test harness launches a real node on a real VM; it is a launcher like the others.
        @Test
        void cloudNodeHarness_startCommand_carriesTheFlag() {
            var line = launcherLine(read(CLOUD_NODE_JAVA), "nohup java ");

            assertThat(line).as("#966: %s startNode must put %s on the java token", CLOUD_NODE_JAVA, FLAG)
                      .contains("nohup java " + FLAG + " -Xmx");
        }

        private void assertShellNodeWrapper(String script) {
            var content = read(script);
            var nodeLine = launcherLine(content, "-jar \"$INSTALL_DIR/lib/aether-node.jar\"");
            var forgeLine = launcherLine(content, "-jar \"$INSTALL_DIR/lib/aether-forge.jar\"");

            assertThat(nodeLine).as("#966: %s aether-node wrapper must exec java with %s on the java token, "
                                   + "BEFORE ${AETHER_JAVA_OPTS}",
                                    script,
                                    FLAG)
                      .startsWith("exec java " + FLAG + " ");
            assertThat(forgeLine).as("forge wrapper in %s must never carry the flag", script).doesNotContain(FLAG);
        }
    }

    /// The first line of `content` containing `marker`, trimmed. Failing here is the "launcher line not
    /// found" case, which must read differently from "flag missing".
    private static String launcherLine(String content, String marker) {
        List<String> hits = content.lines().filter(line -> line.contains(marker)).map(String::strip).toList();

        assertThat(hits).as("control: a launcher line containing %s", marker).isNotEmpty();

        return hits.getFirst();
    }

    private static String read(String relativePath) {
        try {
            return Files.readString(repositoryRoot().resolve(relativePath));
        } catch (Exception e) {
            return fail("could not read " + relativePath, e);
        }
    }

    /// Walks up from the compiled test class rather than trusting `user.dir`, validated against two
    /// marker paths so a walk that stops one directory short cannot resolve to a non-existent file.
    private static Path repositoryRoot() {
        var current = codeSourceLocation();

        while (current != null) {
            if (Files.isRegularFile(current.resolve(NODE_DOCKERFILE)) && Files.isRegularFile(current.resolve(BUILD_AND_PUSH))) {
                return current;
            }

            current = current.getParent();
        }

        return fail("repository root not found above " + codeSourceLocation()
                   + " — no ancestor holds both " + NODE_DOCKERFILE
                   + " and " + BUILD_AND_PUSH);
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(NodeLauncherExitOnOomPinnedTest.class.getProtectionDomain()
                                                                .getCodeSource()
                                                                .getLocation()
                                                                .toURI());
        } catch (Exception e) {
            return fail("cannot locate the test's own code source", e);
        }
    }
}
