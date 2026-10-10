// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;

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
        var exitCode = runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "5");

        assertThat(exitCode).isZero();

        var secretLines = out.toString()
                              .lines()
                              .filter(line -> line.contains("AETHER_CLUSTER_SECRET:"))
                              .toList();

        assertThat(secretLines).isNotEmpty();
        assertThat(secretLines).allMatch(line -> line.matches(".*\\$\\{AETHER_CLUSTER_SECRET:\\?[^}]+}.*"));
    }

    /// #1543 F2: scaffold -> apply -> upgrade. The nodes' source label must equal the source of the config that is applied, or the
    /// first replacement is refused ("No configured source for replacement default"). Through the CLI, as an operator runs it.
    @Test
    void call_dockerComposeTemplate_everyNodeCarriesAetherSource_defaultDocker() {
        assertThat(runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "5")).isZero();
        assertThat(out.toString().lines().filter(line -> line.contains("AETHER_SOURCE:")).toList()).containsExactly("    AETHER_SOURCE: \"docker\"");
    }

    @Test
    void call_dockerComposeTemplate_sourceOption_namesTheSourceOfTheConfig() {
        assertThat(runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "5", "--source", "primary")).isZero();
        assertThat(out.toString()).contains("    AETHER_SOURCE: \"primary\"");
    }

    @Test
    void call_dockerComposeTemplate_invalidSource_isRefused_andEmitsNothing() {
        var err = new ByteArrayOutputStream();
        var original = System.err;

        System.setErr(new PrintStream(err));
        try {
            assertThat(runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "5", "--source", "Bad Source!")).isNotZero();
        } finally {
            System.setErr(original);
        }

        assertThat(err.toString()).contains("Invalid --source");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void call_dockerComposeTemplate_emitsClusterSecretAsRequiredShellReference() {
        var exitCode = runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "5");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .contains("AETHER_CLUSTER_SECRET: \"${AETHER_CLUSTER_SECRET:?export AETHER_CLUSTER_SECRET before docker-compose up}\"");
    }

    /// Pins the picocli-registered option list, not another doc's word — #825 was header text
    /// (`--format`) drifting from a flag picocli had already renamed to `--template`. Reading
    /// `CommandSpec.options()` off a live `CommandLine` means a future rename fails this test
    /// instead of silently re-drifting a doc or a generated header.
    @Test
    void call_scaffoldCommand_picocliOptionListHasTemplateNotFormat() {
        var optionNames = new CommandLine(new ClusterScaffoldCommand()).getCommandSpec()
                                                                        .options()
                                                                        .stream()
                                                                        .flatMap(option -> Arrays.stream(option.names()))
                                                                        .toList();

        assertThat(optionNames).contains("--template")
                                .doesNotContain("--format");
    }

    /// #1019 round-1 review, M6. `ClusterScaffoldCommand#render` floors `--nodes` at the supported
    /// minimum, and NOTHING pinned it: the round-1 mutation putting the floor back to `< 3` left every
    /// test in the module green. A scaffolded compose file is a single tier of fixed nodes, so the
    /// consensus minimum is the right floor for it — and 4 is the value that discriminates, since it
    /// is refused by the supported minimum and accepted by the structural one.
    @Test
    void call_nodesBelowTheSupportedMinimum_isRefused() {
        assertThat(runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "4")).isNotZero();
        assertThat(runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "3")).isNotZero();
    }

    /// The positive half of the boundary, so the test above cannot pass by refusing everything.
    @Test
    void call_nodesAtTheSupportedMinimum_isAccepted() {
        assertThat(runScaffold("--name", "us-prod", "--template", "docker-compose", "--nodes", "5")).isZero();
    }

    private static int runScaffold(String... args) {
        return new CommandLine(new ClusterScaffoldCommand()).execute(args);
    }
}
