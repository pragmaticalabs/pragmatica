// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.backup;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;


/// #1532 — the change-triggered KV backup.
@Command(name = "backup", description = "KV backup management", subcommands = {BackupDeclareGenesisCommand.class})
@Contract
public class BackupCommand implements Runnable {
    @CommandLine.ParentCommand
    private AetherCli parent;

    OutputOptions outputOptions() {
        return parent.outputOptions();
    }

    @Contract
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
