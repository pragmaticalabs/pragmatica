package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputOptions;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Storage instance management command group.
///
/// Provides subcommands for listing, inspecting, and managing
/// storage instances across the cluster.
@Command(name = "storage",
description = "Storage instance management",
subcommands = {StorageListCommand.class,
StorageStatusCommand.class,
StorageSnapshotCommand.class})
@SuppressWarnings("JBCT-RET-01")
public class StorageCommand implements Runnable {
    @CommandLine.ParentCommand
    private AetherCli parent;

    OutputOptions outputOptions() {
        return parent.outputOptions();
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
