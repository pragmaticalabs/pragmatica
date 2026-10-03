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
        var config = AetherConfig.aetherConfig(Environment.DOCKER);

        new DockerGenerator().generate(config, outputDir).onFailure(cause -> fail(cause.message()));
        var compose = read(outputDir.resolve("docker-compose.yml"));

        assertThat(compose).as("control: a healthcheck is rendered").contains("healthcheck:");
        assertThat(compose).contains("\"wget\", \"--spider\", \"-q\", \"http://localhost:")
                  .contains("/health/live\"]");
        assertThat(compose).doesNotContain("\"curl\"").doesNotContain("/health\"]");
    }

    @Test
    void generate_statusScript_curlsARouteTheNodeServes(@TempDir Path outputDir) {
        var config = AetherConfig.aetherConfig(Environment.DOCKER);

        new DockerGenerator().generate(config, outputDir).onFailure(cause -> fail(cause.message()));
        var statusScript = read(outputDir.resolve("status.sh"));

        assertThat(statusScript).as("control: the status script probes node health").contains("/health/");
        assertThat(statusScript).contains("/health/ready").doesNotContain("/health 2>");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return fail("could not read " + path, e);
        }
    }
}
