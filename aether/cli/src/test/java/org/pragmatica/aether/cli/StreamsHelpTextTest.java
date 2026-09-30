// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// #1480 — `aether streams status|publish|read|delete --help` advertised the bare-name default that
/// #1044 removed ("bare name defaults to system:name:1.0.0"), so following the help produced the very
/// error the same binary raises. These pin the rendered usage text — what the operator actually
/// reads — not the constant behind it.
class StreamsHelpTextTest {
    private static final List<String> ADDRESSED_COMMANDS = List.of("status", "publish", "read", "delete");

    private static CommandLine streams() {
        return new CommandLine(new AetherCli()).getSubcommands()
                                               .get("streams");
    }

    /// picocli wraps long descriptions across indented lines, so whitespace is collapsed before matching.
    private static String usage(CommandLine command) {
        return command.getUsageMessage(CommandLine.Help.Ansi.OFF)
                      .replaceAll("\\s+", " ");
    }

    @Test
    void addressedStreamCommands_helpSaysABareNameIsRefused() {
        ADDRESSED_COMMANDS.forEach(name -> {
            var text = usage(streams().getSubcommands()
                                      .get(name));

            assertThat(text).as("streams " + name + " --help")
                            .contains("namespace:stream:version")
                            .contains("A bare name is refused")
                            .contains("system:<name>:1.0.0")
                            .doesNotContainIgnoringCase("defaults to system");
        });
    }

    /// The whole command tree, so the stale convention cannot survive in a sibling nobody listed.
    /// Positive control: the tree walk must reach the four addressed commands, or an empty walk would
    /// pass this vacuously.
    @Test
    void noCommandInTheTree_advertisesTheRemovedBareNameDefault() {
        var visited = new ArrayList<String>();

        walk(new CommandLine(new AetherCli()), "aether", visited);

        assertThat(visited).contains("aether streams status",
                                     "aether streams publish",
                                     "aether streams read",
                                     "aether streams delete");
    }

    private static void walk(CommandLine command, String path, List<String> visited) {
        visited.add(path);
        assertThat(usage(command)).as(path + " --help")
                                  .doesNotContainIgnoringCase("bare name defaults")
                                  .doesNotContainIgnoringCase("defaults to system:");
        command.getSubcommands()
               .forEach((name, sub) -> {
                   if (sub.getCommandName()
                          .equals(name)) {
                       walk(sub, path + " " + name, visited);
                   }
               });
    }
}
