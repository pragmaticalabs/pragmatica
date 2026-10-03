// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.Environment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class DockerGeneratorTest {
    /// #960 class: the generated healthcheck runs INSIDE the node image, so its tool must exist there
    /// (the image installs `wget`, not `curl`) and its path must be one the node serves. The node
    /// serves `/health/live` and `/health/ready`; bare `/health` is a 404 (measured against a running
    /// cluster).
    @Test
    void generate_composeHealthcheck_usesWgetAgainstARouteTheNodeServes(@TempDir Path outputDir) {
        var compose = generateCompose(outputDir, false);

        assertThat(compose).as("control: a healthcheck is rendered").contains("healthcheck:");
        assertThat(compose).contains("\"wget\", \"--spider\", \"-q\", \"http://localhost:")
                  .contains("/health/live\"]");
        assertThat(compose).doesNotContain("\"curl\"").doesNotContain("/health\"]").doesNotContain("https://");
    }

    /// A TLS-enabled management listener serves HTTPS, so the healthcheck tries HTTPS first (the CA is
    /// self-generated, hence `--no-check-certificate`) and falls back to HTTP, like the node image's
    /// own HEALTHCHECK. Without TLS the plain-HTTP form above must stay (the control).
    @Test
    void generate_tlsEnabled_composeHealthcheckTriesHttpsFirst(@TempDir Path outputDir) {
        var compose = generateCompose(outputDir, true);

        assertThat(compose).contains("wget -q --spider --no-check-certificate https://localhost:")
                  .contains("/health/live || wget -q --spider http://localhost:")
                  .doesNotContain("\"curl\"");
    }

    @Test
    void generate_statusScript_curlsARouteTheNodeServes(@TempDir Path outputDir) {
        var statusScript = generateStatus(outputDir, false);

        assertThat(statusScript).as("control: the status script probes node health").contains("/health/");
        assertThat(statusScript).contains("curl -sk http://localhost:$port/health/ready").doesNotContain("/health 2>");
    }

    @Test
    void generate_tlsEnabled_statusScriptUsesHttps(@TempDir Path outputDir) {
        var statusScript = generateStatus(outputDir, true);

        assertThat(statusScript).contains("curl -sk https://localhost:$port/health/ready");
    }

    private static String generateCompose(Path outputDir, boolean tls) {
        generate(outputDir, tls);

        return read(outputDir.resolve("docker-compose.yml"));
    }

    private static String generateStatus(Path outputDir, boolean tls) {
        generate(outputDir, tls);

        return read(outputDir.resolve("status.sh"));
    }

    private static void generate(Path outputDir, boolean tls) {
        var config = AetherConfig.builder().withEnvironment(Environment.DOCKER).tls(tls).build();

        assertThat(config.tlsEnabled()).as("control: the config under test has the requested TLS mode").isEqualTo(tls);
        new DockerGenerator().generate(config, outputDir).onFailure(cause -> fail(cause.message()));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return fail("could not read " + path, e);
        }
    }
}
