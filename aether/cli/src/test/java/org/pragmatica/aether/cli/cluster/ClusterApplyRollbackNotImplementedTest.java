// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/// Owner ruling (#686): `aether cluster apply --rollback` fails honestly until the wave rollout is
/// decided. The flag used to be accepted and then read the config, call the cluster and finish through
/// the normal apply path, so an operator asking for a rollback got a command that looked like it ran.
///
/// The config path below does not exist on purpose: an honest refusal comes BEFORE any file read or
/// cluster call, so it must not mention the path, and a pre-fix run fails with a file-read error
/// instead of the refusal.
class ClusterApplyRollbackNotImplementedTest {
    private static final String MISSING_CONFIG = "/nonexistent/aether-cluster.toml";

    private record Outcome(int exitCode, String stdout, String stderr) {}

    @Test
    void rollback_isRefusedAsNotImplemented_withNonZeroExit() {
        var outcome = run("--rollback", MISSING_CONFIG);

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--rollback is not implemented").contains("#686");
        assertThat(outcome.stdout()).doesNotContain("Apply complete");
    }

    @Test
    void rollback_refusalComesBeforeAnyConfigRead() {
        var outcome = run("--rollback", MISSING_CONFIG);

        assertThat(outcome.stderr()).doesNotContain(MISSING_CONFIG).doesNotContain("NoSuchFile");
    }

    @Test
    void rollback_withResume_isStillRefused() {
        var outcome = run("--rollback", "--resume", MISSING_CONFIG);

        assertThat(outcome.exitCode()).isEqualTo(ExitCode.ERROR);
        assertThat(outcome.stderr()).contains("--rollback is not implemented");
    }

    /// Control: a command without the flag does NOT print the refusal, so a red above comes from the
    /// flag handling and not from a message every failure shares.
    @Test
    void withoutRollback_noRefusalIsPrinted() {
        var outcome = run(MISSING_CONFIG);

        assertThat(outcome.stderr()).doesNotContain("is not implemented");
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
}
