// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Picocli argument-parsing harness for the `aether stream` command surface. Validates
/// command-shape (required positional args, optional flags, subcommand names) without invoking
/// HTTP — `call()` is never reached because `parseArgs` short-circuits before `runLast`.
class StreamCommandArgsTest {

    private static CommandLine commandLine() {
        return new CommandLine(new StreamCommand());
    }

    @Nested
    class ListCommandArgs {

        @Test
        void list_noFlags_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("list");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("list");
        }

        @Test
        void list_namespaceFlag_parsesValue() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("list", "--namespace", "com.example.app");
            var listCommand = (StreamCommand.ListCommand) parsed.subcommand().commandSpec().userObject();
            assertThat(listCommand).isNotNull();
        }
    }

    @Nested
    class ShowCommandArgs {

        @Test
        void show_withAddress_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("show", "com.example.app:orders:1.0.0");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("show");
        }

        @Test
        void show_missingAddress_fails() {
            var cmd = commandLine();
            assertThatThrownBy(() -> cmd.parseArgs("show"))
                    .isInstanceOf(CommandLine.MissingParameterException.class);
        }
    }

    @Nested
    class TailCommandArgs {

        @Test
        void tail_withAddress_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail", "com.example.app:orders:1.0.0");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }

        @Test
        void tail_withInterval_parsesValue() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail", "com.example.app:orders:1.0.0", "--interval", "250");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }

        @Test
        void tail_withFromOffset_parsesValue() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail", "com.example.app:orders:1.0.0", "--from-offset", "42");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }

        @Test
        void tail_withMaxEvents_parsesValue() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail", "com.example.app:orders:1.0.0", "--max-events", "200");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }

        @Test
        void tail_withFollowDefault_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail", "com.example.app:orders:1.0.0", "--follow");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }

        @Test
        void tail_withNoFollow_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail", "com.example.app:orders:1.0.0", "--no-follow");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }

        @Test
        void tail_withAllFlags_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("tail",
                                       "com.example.app:orders:1.0.0",
                                       "--interval", "100",
                                       "--from-offset", "10",
                                       "--max-events", "50",
                                       "--no-follow");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("tail");
        }
    }

    @Nested
    class DeleteCommandArgs {

        @Test
        void delete_withAddress_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("delete", "com.example.app:orders:1.0.0");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("delete");
        }

        @Test
        void delete_withForceFlag_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("delete", "com.example.app:orders:1.0.0", "--force");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("delete");
        }

        @Test
        void delete_withShortForceFlag_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("delete", "com.example.app:orders:1.0.0", "-f");
            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("delete");
        }
    }

    @Nested
    class GroupCreateCommandArgs {

        @Test
        void groupCreate_withAddressAndGroup_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("group", "create", "com.example.app:orders:1.0.0", "my-group");
            assertThat(parsed.subcommand().subcommand().commandSpec().name()).isEqualTo("create");
        }

        @Test
        void groupCreate_missingGroupId_fails() {
            var cmd = commandLine();
            assertThatThrownBy(() -> cmd.parseArgs("group", "create", "com.example.app:orders:1.0.0"))
                    .isInstanceOf(CommandLine.MissingParameterException.class);
        }

        @Test
        void groupCreate_withInitialPosition_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("group",
                                       "create",
                                       "com.example.app:orders:1.0.0",
                                       "my-group",
                                       "--initial-position",
                                       "earliest");
            assertThat(parsed.subcommand().subcommand().commandSpec().name()).isEqualTo("create");
        }
    }

    @Nested
    class GroupDeleteCommandArgs {

        @Test
        void groupDelete_withAddressAndGroup_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("group", "delete", "com.example.app:orders:1.0.0", "my-group");
            assertThat(parsed.subcommand().subcommand().commandSpec().name()).isEqualTo("delete");
        }

        @Test
        void groupDelete_withForceFlag_parsesSuccessfully() {
            var cmd = commandLine();
            var parsed = cmd.parseArgs("group", "delete", "com.example.app:orders:1.0.0", "my-group", "--force");
            assertThat(parsed.subcommand().subcommand().commandSpec().name()).isEqualTo("delete");
        }
    }

    @Nested
    class UnknownSubcommand {

        @Test
        void unknownSubcommand_fails() {
            var cmd = commandLine();
            assertThatThrownBy(() -> cmd.parseArgs("nonexistent"))
                    .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }
}
