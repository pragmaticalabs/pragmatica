// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;


@Command(name = "storage", description = "Storage instance management", subcommands = {StorageListCommand.class, StorageStatusCommand.class, StorageSnapshotCommand.class}) @Contract public class StorageCommand implements Runnable {
    @CommandLine.ParentCommand private AetherCli parent;

    OutputOptions outputOptions() {
        return parent.outputOptions();
    }

    @Contract@Override public void run() {
        CommandLine.usage(this, System.out);
    }
}
