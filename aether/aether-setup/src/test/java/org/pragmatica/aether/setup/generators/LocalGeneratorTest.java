// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class LocalGeneratorTest {

    /// #966 — the local `start.sh` launches real node JVMs, so it is a node launcher and carries
    /// `-XX:+ExitOnOutOfMemoryError` on the `java` token like every other one: a node that exhausts
    /// its heap must exit (code 3) rather than hang answering SWIM pings while holding its membership
    /// slot. The count assertion is the control — one launcher per node, each carrying the flag, so a
    /// regression in the per-node template cannot hide behind a single stray mention of the flag.
    @Nested
    class ExitOnOutOfMemoryIsPinned {
        @Test
        void generate_startScript_launchesEveryNodeWithExitOnOomOnTheJavaToken(@TempDir Path outputDir) {
            var config = AetherConfig.aetherConfig(Environment.LOCAL);
            var nodes = config.cluster().nodes();

            new LocalGenerator().generate(config, outputDir)
                                .onFailure(cause -> fail(cause.message()));
            var startScript = read(outputDir.resolve("start.sh"));

            assertThat(startScript)
                .as("control: the generated start.sh launches the node jar")
                .contains("-jar \"$AETHER_JAR\"");
            assertThat(nodes).as("control: the LOCAL default cluster has nodes to launch").isPositive();
            assertThat(occurrences(startScript, "java -XX:+ExitOnOutOfMemoryError -Xmx"))
                .as("#966: every node launch in start.sh must put -XX:+ExitOnOutOfMemoryError on the java token, "
                    + "before -Xmx, so an exhausted heap kills the node instead of leaving it answering SWIM "
                    + "pings from a dead process (deployment-recovery.md §4.5). Got:\n" + startScript)
                .isEqualTo(nodes);
        }
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
