// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;


/// Covers `aether cluster init --non-interactive` flag (P-NEW-G, 2026-05-21).
/// Validates that the flag forces batch mode, applies a sensible `--target` default
/// (`docker`) when not provided, and fails fast on missing required fields rather
/// than dropping into the interactive wizard. Unblocks TC-07-J3 in
/// `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
class ClusterInitCommandNonInteractiveTest {

    @Nested
    class WithFullDockerFlags {

        @Test
        void nonInteractive_writesConfigFile(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var exit = new CommandLine(new ClusterCommand())
                              .execute("init",
                                       "--non-interactive",
                                       "--name", "test-cluster",
                                       "--nodes", "5",
                                       "--output", output.toString());

            assertThat(exit).isEqualTo(0);
            assertThat(Files.exists(output)).isTrue();
        }

        @Test
        void nonInteractive_withExplicitTarget_writesConfigFile(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var exit = new CommandLine(new ClusterCommand())
                              .execute("init",
                                       "--non-interactive",
                                       "--target", "docker",
                                       "--name", "test-cluster",
                                       "--nodes", "3",
                                       "--output", output.toString());

            assertThat(exit).isEqualTo(0);
            assertThat(Files.exists(output)).isTrue();
        }
    }

    @Nested
    class MissingRequiredFlags {

        @Test
        void nonInteractive_missingNodes_failsFast(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var exit = new CommandLine(new ClusterCommand())
                              .execute("init",
                                       "--non-interactive",
                                       "--name", "test-cluster",
                                       "--output", output.toString());

            // Should NOT drop into the interactive wizard — must report missing-field failure.
            assertThat(exit).isNotEqualTo(0);
            assertThat(Files.exists(output)).isFalse();
        }
    }

    @Nested
    class BatchModeViaTargetStillWorks {

        @Test
        void batchMode_withoutNonInteractiveFlag_stillWorks(@TempDir Path tmp) {
            // Backward-compatibility: passing --target alone still triggers batch mode.
            var output = tmp.resolve("cluster-config.toml");
            var exit = new CommandLine(new ClusterCommand())
                              .execute("init",
                                       "--target", "docker",
                                       "--name", "test-cluster",
                                       "--nodes", "3",
                                       "--output", output.toString());

            assertThat(exit).isEqualTo(0);
            assertThat(Files.exists(output)).isTrue();
        }
    }

    /// The cloud batch path, driven end to end through picocli.
    ///
    /// Each guard is asserted on the MESSAGE, not merely on a non-zero exit: every failure exits
    /// non-zero, so an exit code alone cannot tell one guard from another and every test here would
    /// pass for the wrong reason.
    @Nested
    class CloudBatchModeGuards {

        private static final String KEY = "~/.ssh/id_ed25519.pub";

        /// Everything except the flag under test, so the named guard is the only thing that can fire.
        private static String[] cloudArgs(Path output, String... extra) {
            var base = new java.util.ArrayList<>(java.util.List.of("init",
                                                                   "--non-interactive",
                                                                   "--target", "cloud",
                                                                   "--provider", "hetzner",
                                                                   "--region", "hel1",
                                                                   "--instance-type", "cpx32",
                                                                   "--credential-env", "HCLOUD_TOKEN",
                                                                   "--ssh-public-key", KEY,
                                                                   "--admin-cidr", "203.0.113.0/24",
                                                                   "--nodes", "3",
                                                                   "--name", "prod-eu",
                                                                   "--output", output.toString()));

            base.addAll(java.util.List.of(extra));

            return base.toArray(new String[0]);
        }

        private static String runCapturingStderr(String... args) {
            var captured = new java.io.ByteArrayOutputStream();
            var original = System.err;

            System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));

            try {
                new CommandLine(new ClusterCommand()).execute(args);
            } finally {
                System.setErr(original);
            }

            return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        private static String[] without(Path output, String flag) {
            var all = java.util.Arrays.asList(cloudArgs(output));
            var idx = all.indexOf(flag);
            var kept = new java.util.ArrayList<>(all);

            kept.subList(idx, idx + 2).clear();

            return kept.toArray(new String[0]);
        }

        /// The R6 gap: `--ssh-key` names the PRIVATE key and did nothing here, silently. Every other
        /// required flag is supplied, so this can only fail because the flag was refused.
        @Test
        void cloudInit_withSshKey_isRefused_ratherThanSilentlyIgnored(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var err = runCapturingStderr(cloudArgs(output, "--ssh-key", "~/.ssh/id_ed25519"));

            assertThat(err).contains("--ssh-key")
                           .contains("--ssh-public-key");
            assertThat(Files.exists(output)).isFalse();
        }

        @Test
        void cloudInit_withoutSshPublicKey_namesTheFlag(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var err = runCapturingStderr(without(output, "--ssh-public-key"));

            assertThat(err).contains("--ssh-public-key");
            assertThat(Files.exists(output)).isFalse();
        }

        @Test
        void cloudInit_withoutAdminCidr_namesTheFlag(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var err = runCapturingStderr(without(output, "--admin-cidr"));

            assertThat(err).contains("--admin-cidr");
            assertThat(Files.exists(output)).isFalse();
        }

        @Test
        void cloudInit_withoutRegion_namesTheFlagAndTheResidencyReason(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var err = runCapturingStderr(without(output, "--region"));

            assertThat(err).contains("--region")
                           .contains("jurisdiction");
            assertThat(Files.exists(output)).isFalse();
        }

        @Test
        void cloudInit_withoutInstanceType_namesTheFlagAndTheCatalogue(@TempDir Path tmp) {
            var output = tmp.resolve("cluster-config.toml");
            var err = runCapturingStderr(without(output, "--instance-type"));

            assertThat(err).contains("--instance-type")
                           .contains("catalogue");
            assertThat(Files.exists(output)).isFalse();
        }

        /// The positive case, and the one that proves the guards are not merely refusing everything:
        /// a complete cloud invocation writes a file carrying BOTH the admin-scoped firewall rules
        /// (ports 22 and 8080, absent before this change on the STANDARD default) and the SSH
        /// section `SshKeyResolver` reads.
        @Test
        void cloudInit_withEveryRequiredFlag_writesABootstrappableConfig(@TempDir Path tmp) throws Exception {
            var output = tmp.resolve("cluster-config.toml");
            var exit = new CommandLine(new ClusterCommand()).execute(cloudArgs(output));

            assertThat(exit).isEqualTo(0);

            var toml = Files.readString(output);

            assertThat(toml).contains("[infrastructure.ssh]")
                            .contains("public_key_file")
                            .contains("port = 22")
                            .contains("port = 8080")
                            .contains("203.0.113.0/24");
        }
    }
}
