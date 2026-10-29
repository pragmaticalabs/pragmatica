// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.environment.ClusterName;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// `aether cluster apply --rollback` is wired (#686, replaces the pin that it was refused): it reads the ledger of the
/// config's cluster and terminates what it recorded. These pins drive the command itself; the rollback rules are in
/// [ApplyRollbackTest].
class ClusterApplyRollbackCommandTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("rb-cli").unwrap();

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    @TempDir
    Path dir;

    private record Outcome(int exitCode, String stdout, String stderr) {}

    private Path config() throws Exception {
        return Files.writeString(dir.resolve("aether-cluster.toml"), """
                config_version = "1.0.0"
                [cluster]
                name = "rb-cli"
                version = "1.0.0"
                [source.local]
                type = "docker"
                [source.local.core]
                count = 1
                """);
    }

    private static Outcome run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;

        try (var capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             var capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);

            var exit = new CommandLine(new ClusterApplyCommand()).execute(args);

            return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void rollback_withNoLedger_failsSayingThereIsNothingToRollBack_andIsNoLongerRefusedAsNotImplemented() throws Exception {
        var outcome = run("--rollback", config().toString());

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("Nothing to roll back").doesNotContain("not implemented");
    }

    @Test
    void rollback_ofALedgerWithNoVms_completes_andRemovesTheLedger() throws Exception {
        ledger.recorder(CLUSTER);

        var outcome = run("--rollback", config().toString());

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.SUCCESS);
        assertThat(outcome.stdout()).contains("Rollback complete");
        assertThat(ApplyState.read(CLUSTER).unwrap().isPresent()).as("the ledger is closed").isFalse();
    }
}
