// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.logging;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1077 review BLOCKING-1: `SystemLoggerBridgeTest` proves the bridge on the TEST classpath, where
/// `log4j-api`'s own jar carries `Multi-Release: true`. The shaded `aether-node.jar` did not, so the
/// JVM ignored `META-INF/versions/9/...StackLocator`, log4j-api ran its Java-8 caller lookup (null on
/// Java 25), `ClassLoaderContextSelector` fell through to the `Default` context for SLF4J/`Configurator`/
/// `Main.flushLogs` while the jpl bridge took the classloader-keyed context — two `LoggerContext`s,
/// `Configurator.setLevel(…, DEBUG)` invisible to the bridged loggers, the flush stopping the wrong one.
/// This runs the SHIPPED jar in a subprocess (failsafe, after `package`) and pins one context.
class ShadedJarLoggerContextIT {
    private static final Path SHADED_JAR = Path.of("target", "aether-node.jar");
    private static final Path TEST_CLASSES = Path.of("target", "test-classes");

    @Test
    void shadedJar_declaresMultiRelease_soLog4jUsesItsJava9StackLocator() throws IOException {
        assertThat(SHADED_JAR).as("failsafe runs after package; the shaded jar must exist").exists();

        try (var jar = new JarFile(SHADED_JAR.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Multi-Release"))
                .as("without `Multi-Release: true` the shaded log4j-api runs its Java-8 StackLocator "
                    + "and every bridged System.Logger lands in a second LoggerContext")
                .isEqualTo("true");
        }
    }

    @Test
    void shadedJar_bridgedSystemLogger_sharesTheContextTheNodeLevelsAndFlushes() throws IOException,
                                                                                         InterruptedException {
        var output = runProbe();

        assertThat(output).contains("finder=org.apache.logging.log4j.jpl.Log4jSystemLoggerFinder");
        // Not the selector's context count: the `Default` context lives outside its map, so the count
        // read 1 with the defect present. Identity with what Configurator/Main.flushLogs resolve is the pin.
        assertThat(output).as("the bridged logger's context IS the one Configurator and Main.flushLogs resolve")
                          .contains("sameContext=true");
        assertThat(output).as("Configurator.setLevel(ResourceFactory, DEBUG) — what LogLevelRegistry does — "
                              + "must be visible to the bridged logger")
                          .contains("jplDebugAfterSetLevel=true");
        assertThat(output).as("the ticket's own line, through the node's log4j2.xml, after the runtime level switch")
                          .contains("DEBUG")
                          .contains("No close convention");
    }

    private static String runProbe() throws IOException, InterruptedException {
        var javaBinary = ProcessHandle.current().info().command().orElseThrow();
        var classpath = SHADED_JAR + File.pathSeparator + TEST_CLASSES;
        var process = new ProcessBuilder(javaBinary, "-cp", classpath, ShadedJarLoggerContextProbe.class.getName())
                          .redirectErrorStream(true)
                          .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("probe did not exit within 60s; output so far:\n" + output);
        }

        if (process.exitValue() != 0) {
            fail("probe exited " + process.exitValue() + ":\n" + output);
        }

        return output;
    }
}
