// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: the built-in artifact store archives instead of deleting. `aether artifacts archive` is the command;
/// `delete` stays as an alias so existing scripts keep working, and both reach the same command.
class ArtifactArchiveCommandTest {
    @Test
    void artifactsCommand_exposesArchive_andKeepsDeleteAsAnAlias() {
        var artifacts = new CommandLine(new AetherCli.ArtifactCommand());

        var archive = artifacts.getSubcommands().get("archive");

        assertThat(archive).isNotNull();
        assertThat(artifacts.getSubcommands().get("delete")).as("alias resolves to the same command").isSameAs(archive);
        assertThat(archive.getCommandSpec().aliases()).containsExactly("delete");
    }

    @Test
    void archiveHelp_statesTheRetentionRule_andTheKeysAreKept() {
        var description = String.join(" ", new CommandLine(new AetherCli.ArtifactCommand()).getSubcommands()
                                                                                          .get("archive")
                                                                                          .getCommandSpec()
                                                                                          .usageMessage()
                                                                                          .description());

        assertThat(description).contains("retention").contains("7 days").contains("keys are kept");
    }

    @Test
    void deployAndPushHelp_stateTheWriteOnceAndNoSnapshotRules() {
        var subcommands = new CommandLine(new AetherCli.ArtifactCommand()).getSubcommands();

        for (var name : new String[]{"deploy", "push"}) {
            var description = String.join(" ", subcommands.get(name).getCommandSpec().usageMessage().description());

            assertThat(description).as(name).contains("409").contains("SNAPSHOT").contains("400");
        }
    }
}
