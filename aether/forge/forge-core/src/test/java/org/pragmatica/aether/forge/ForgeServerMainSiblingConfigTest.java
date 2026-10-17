// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.ConfigLoader;

import static org.assertj.core.api.Assertions.assertThat;

/// #909: the real `ForgeServer.main` in a child JVM refuses to start (exit 1, FATAL naming the file and `jwks_url`) when the `aether.toml` next to
/// its `--config` file asked for `security_mode = "jwt"` without a `jwks_url`. Before this it ran with security NONE.
class ForgeServerMainSiblingConfigTest {
    private static final String JWT_WITHOUT_JWKS = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"jwt\"\n";
    private static final String VALID = JWT_WITHOUT_JWKS + "jwks_url = \"https://auth.example.com/jwks.json\"\n";

    @Test
    void fixtures_areWhatTheTestClaims() {
        assertThat(ConfigLoader.loadFromString(VALID).isSuccess()).as("CONTROL: the same file with jwks_url loads").isTrue();
        assertThat(ConfigLoader.loadFromString(JWT_WITHOUT_JWKS).isFailure()).as("CONTROL: without jwks_url it does not").isTrue();
    }

    @Test
    void main_refusesToStart_whenTheSiblingAetherTomlDoesNotLoad(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("forge.toml"), "");
        Files.writeString(dir.resolve("aether.toml"), JWT_WITHOUT_JWKS);
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
        assertThat(output).contains("FATAL").contains("aether.toml").contains("jwks_url");
        assertThat(output).as("refused BEFORE anything is created: no banner, no cluster start").doesNotContain("AETHER FORGE").doesNotContain("Starting Forge server");
    }
}
