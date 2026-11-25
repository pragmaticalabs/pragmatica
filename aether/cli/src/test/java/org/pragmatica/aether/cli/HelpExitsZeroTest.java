// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// #2261 — `aether cluster apply --help` printed usage, then exited 2 with "Missing required
/// parameter: '<configFile>'": only the root command declared `--help` (via `mixinStandardHelpOptions`),
/// so a subcommand with a required parameter treated `--help` as an unknown option and the missing
/// parameter was reported. Scripts and docs gates use `--help` to check that a command exists, so every
/// command in the tree must answer it with usage on stdout and exit 0. Each command is executed through
/// the real parser, not inspected as a spec, because the failure is a parse-time one.
///
/// Executing a command whose `--help` is NOT honoured runs the command. `cluster init` then starts its
/// wizard, whose `Prompt` wraps `System.in` when the command object is constructed, and a surefire fork's
/// stdin never reaches EOF: the build hung on "Cluster name:" until the host killed it (m1 of #2261, a
/// mutant with `usageHelp` off). So `System.in` is an immediate EOF while the tree is built and run, which
/// turns that regression into a red with a message; the class-level timeout is the backstop, generous
/// because a loaded host must not turn a slow tree walk into a failure.
@Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class HelpExitsZeroTest {
    private record Outcome(int exitCode, String stdout, String stderr) {}

    private static Outcome runHelp(List<String> path, String helpFlag) {
        var originalIn = System.in;

        System.setIn(new ByteArrayInputStream(new byte[0]));
        try {
            return runWithEmptyStdin(path, helpFlag);
        } finally {
            System.setIn(originalIn);
        }
    }

    private static Outcome runWithEmptyStdin(List<String> path, String helpFlag) {
        var out = new StringWriter();
        var err = new StringWriter();
        var cmd = new CommandLine(new AetherCli());

        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        var args = new ArrayList<>(path);

        if (!helpFlag.isEmpty()) {
            args.add(helpFlag);
        }

        var exitCode = cmd.execute(args.toArray(String[]::new));

        return new Outcome(exitCode, out.toString(), err.toString());
    }

    private static void walk(CommandLine command, List<String> path, List<List<String>> visited) {
        visited.add(List.copyOf(path));
        command.getSubcommands()
               .forEach((name, child) -> {
                   if (!name.equals(child.getCommandName())) {
                       return;
                   }

                   var next = new ArrayList<>(path);

                   next.add(name);
                   walk(child, next, visited);
               });
    }

    private static List<List<String>> commandTree() {
        var visited = new ArrayList<List<String>>();

        var originalIn = System.in;

        System.setIn(new ByteArrayInputStream(new byte[0]));
        try {
            walk(new CommandLine(new AetherCli()), List.of(), visited);
        } finally {
            System.setIn(originalIn);
        }
        return visited;
    }

    @Test
    void clusterApply_helpExitsZeroWithUsage() {
        var outcome = runHelp(List.of("cluster", "apply"), "--help");

        assertThat(outcome.exitCode()).as("stderr: " + outcome.stderr())
                                    .isZero();
        assertThat(outcome.stdout()).contains("Usage: aether cluster apply");
        assertThat(outcome.stderr()).doesNotContain("Missing required parameter");
    }

    /// Positive control: the walk must reach commands with a required parameter and deep leaves,
    /// otherwise an empty or shallow walk would pass the loop below vacuously.
    @Test
    void everyCommandInTheTree_helpExitsZeroWithUsage() {
        var tree = commandTree();
        var failures = new ArrayList<String>();

        assertThat(tree).contains(List.of(),
                                  List.of("cluster", "apply"),
                                  List.of("nodes", "drain"),
                                  List.of("streams", "consumer-group", "join"));
        assertThat(tree.size()).as("walked commands")
                               .isGreaterThan(100);
        tree.forEach(path -> {
            var outcome = runHelp(path, "--help");

            if (outcome.exitCode() != 0 || !outcome.stdout().contains("Usage: ")) {
                failures.add("aether " + String.join(" ", path) + " --help: exit " + outcome.exitCode()
                             + ", stdout=" + outcome.stdout().lines().findFirst().orElse("<empty>")
                             + ", stderr=" + outcome.stderr().lines().findFirst().orElse("<empty>"));
            }
        });
        assertThat(failures).as("commands whose --help did not exit 0 with usage")
                            .isEmpty();
    }

    @Test
    void everyCommandInTheTree_shortHelpFlagExitsZero() {
        var failures = new ArrayList<String>();

        commandTree().forEach(path -> {
            var outcome = runHelp(path, "-h");

            if (outcome.exitCode() != 0) {
                failures.add("aether " + String.join(" ", path) + " -h: exit " + outcome.exitCode());
            }
        });
        assertThat(failures).isEmpty();
    }

    @Test
    void rootVersionFlag_stillWorks() {
        var outcome = runHelp(List.of(), "--version");

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.stdout()).isNotBlank();
    }

    /// The picocli command this replaced is what `aether/install.sh` runs to install shell completion,
    /// so its output must survive the change that dropped its own help mixin.
    @Test
    void generateCompletion_stillPrintsTheBashCompletionScript() {
        var outcome = runHelp(List.of("generate-completion"), "");

        assertThat(outcome.exitCode()).as("stderr: " + outcome.stderr())
                                      .isZero();
        assertThat(outcome.stdout()).contains("_complete_aether");
    }
}
