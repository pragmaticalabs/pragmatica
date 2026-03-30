package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Forces a metadata snapshot on the specified storage instance.
///
/// Triggers the snapshot on the connected node (or on a specific node
/// when `--node` is provided).
@Command(name = "snapshot", description = "Force a metadata snapshot")
@SuppressWarnings("JBCT-RET-01")
class StorageSnapshotCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private StorageCommand parent;

    @CommandLine.Parameters(index = "0", description = "Storage instance name")
    private String name;

    @CommandLine.Option(names = "--node", description = "Target specific node")
    private String nodeId;

    @Override
    public Integer call() {
        var path = "/api/storage/" + name + "/snapshot";

        return ClusterHttpClient.postToCluster(path, "{}")
                                .fold(StorageSnapshotCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), "Snapshot triggered: " + name);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
