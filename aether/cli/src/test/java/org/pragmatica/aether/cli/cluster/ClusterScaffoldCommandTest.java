// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression tests for `aether cluster scaffold`'s cluster-secret handling (#684): the emitted
/// compose file must never carry a literal secret value, only a
/// `${AETHER_CLUSTER_SECRET:?...}` reference the operator resolves at `docker compose up` time.
/// The shape check asserts on every `AETHER_CLUSTER_SECRET:` line, not just the absence of one
/// known literal — a different hardcoded value, or a reference missing the `:?` fail-fast form,
/// fails it too. Exercises `ClusterScaffoldCommand.call()` end-to-end via picocli, not
/// `DockerComposeTemplate` directly, so the assertion holds for what the CLI actually prints.
class ClusterScaffoldCommandTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void redirectStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(out));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void call_dockerComposeTemplate_everyClusterSecretLineIsARequiredShellReference() {
        var exitCode = runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "3");

        assertThat(exitCode).isZero();

        var secretLines = out.toString()
                              .lines()
                              .filter(line -> line.contains("AETHER_CLUSTER_SECRET:"))
                              .toList();

        assertThat(secretLines).isNotEmpty();
        assertThat(secretLines).allMatch(line -> line.matches(".*\\$\\{AETHER_CLUSTER_SECRET:\\?[^}]+}.*"));
    }

    @Test
    void call_dockerComposeTemplate_emitsClusterSecretAsRequiredShellReference() {
        var exitCode = runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "3");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .contains("AETHER_CLUSTER_SECRET: \"${AETHER_CLUSTER_SECRET:?export AETHER_CLUSTER_SECRET before docker-compose up}\"");
    }

    private static int runScaffold(String... args) {
        return new CommandLine(new ClusterScaffoldCommand()).execute(args);
    }
}
