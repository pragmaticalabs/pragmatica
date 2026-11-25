// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.lang.Contract;

import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;


/// `aether generate-completion` — the same command and output as picocli's
/// `AutoComplete.GenerateCompletion`, minus its `mixinStandardHelpOptions = true` (#2261).
///
/// `AetherCli` declares `-h`/`--help` with `scope = INHERIT` so that every subcommand answers it; picocli's
/// own class carries a second `-h`/`--help` through the standard mixin, and the two collide at
/// construction (`DuplicateOptionAnnotationsException`). The inherited option supplies `-h`/`--help`
/// here; the `--version` that mixin added is not carried over, since this command prints no version of its own.
@Command(name = "generate-completion", description = {"Generate bash/zsh completion script for ${PARENT-COMMAND-FULL-NAME:-the parent command of this command}.", "Run the following command to give `${PARENT-COMMAND-FULL-NAME:-$PARENTCOMMAND}` TAB completion in the current shell:", "", "  source <(${PARENT-COMMAND-FULL-NAME:-$PARENTCOMMAND} ${COMMAND-NAME})", ""}, optionListHeading = "Options:%n", helpCommand = true)
class GenerateCompletionCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Contract
    @Override
    public void run() {
        var script = AutoComplete.bash(spec.parent().name(),
                                       spec.root().commandLine());
        var out = spec.commandLine().getOut();
        // Not println: a Windows line separator breaks completion scripts in strange ways.
        out.print(script);
        out.print('\n');
        out.flush();
    }
}
