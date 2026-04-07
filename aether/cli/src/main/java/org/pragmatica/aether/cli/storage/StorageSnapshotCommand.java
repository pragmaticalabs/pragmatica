package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_SNAPSHOT;


/// Forces a metadata snapshot on the specified storage instance.
/// Triggers the snapshot on the connected node.
@Command(name = "snapshot", description = "Force a metadata snapshot") @SuppressWarnings("JBCT-RET-01") class StorageSnapshotCommand implements Callable<Integer> {
    @CommandLine.ParentCommand private StorageCommand parent;

    @CommandLine.Parameters(index = "0", description = "Storage instance name") private String name;

    @Override public Integer call() {
        return ClusterHttpClient.post(STORAGE_SNAPSHOT,
                                      List.of(name),
                                      "{}")
        .fold(StorageCliHelper::onFailure,
              json -> OutputFormatter.printAction(json,
                                                  parent.outputOptions(),
                                                  "Snapshot triggered: " + name));
    }
}
