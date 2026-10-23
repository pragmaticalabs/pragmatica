// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2385: a `forge.toml` that does not load - here, one still setting the renamed `dashboard_port` - stops Forge
/// with exit 1 and a FATAL naming `forge_port`. It used to be logged and replaced by `EmberConfig.DEFAULT`, so
/// Forge started on the default ports with every setting of the file dropped.
class ForgeServerForgeConfigRefusalTest {
    private static final String OLD_KEY = "[cluster]\nnodes = 3\ndashboard_port = 8988\n";
    private static final String NEW_KEY = "[cluster]\nnodes = 3\nforge_port = 8988\n";

    @Test
    void loadForgeConfig_exitsWithOne_onlyForAFileThatDoesNotLoad(@TempDir Path dir) throws Exception {
        var exits = new ArrayList<Integer>();
        var forgeToml = dir.resolve("forge.toml");

        Files.writeString(forgeToml, NEW_KEY);
        assertThat(ForgeServer.loadForgeConfig(startupConfig(Option.some(forgeToml)), exits::add).forgePort()).isEqualTo(8988);
        assertThat(ForgeServer.loadForgeConfig(startupConfig(Option.none()), exits::add).forgePort())
            .as("no --config: the port from the startup flags")
            .isEqualTo(9090);
        assertThat(exits).as("CONTROL: a loadable file and an absent file never exit").isEmpty();

        Files.writeString(forgeToml, OLD_KEY);
        ForgeServer.loadForgeConfig(startupConfig(Option.some(forgeToml)), exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void main_refusesToStart_whenForgeTomlStillSetsDashboardPort(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("forge.toml"), OLD_KEY);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var out = dir.resolve("out.txt");
        var process = new ProcessBuilder(List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"), ForgeServer.class.getName(),
                                                 "--config", dir.resolve("forge.toml").toString()))
            .redirectErrorStream(true)
            .redirectOutput(out.toFile())
            .start();

        var finished = process.waitFor(90, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
        }
        var output = Files.readString(out);

        assertThat(finished).as("main must refuse promptly, not start a cluster; output:\n" + output).isTrue();
        assertThat(process.exitValue()).as("output:\n" + output).isEqualTo(1);
        assertThat(output).contains("FATAL").contains("forge.toml").contains("dashboard_port").contains("forge_port");
        assertThat(output).as("refused BEFORE anything is created: no banner, no cluster start").doesNotContain("AETHER FORGE").doesNotContain("Starting Forge server");
    }

    private static StartupConfig startupConfig(Option<Path> forgeConfig) {
        return new StartupConfig(forgeConfig, Option.none(), Option.none(), false, 9090, 3, 1000);
    }
}
