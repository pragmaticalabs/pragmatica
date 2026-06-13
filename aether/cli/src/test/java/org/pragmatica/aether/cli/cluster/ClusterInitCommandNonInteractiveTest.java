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
}
