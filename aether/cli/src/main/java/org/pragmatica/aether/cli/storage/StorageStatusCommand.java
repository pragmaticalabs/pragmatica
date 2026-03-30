package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.lang.Option;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Displays detailed status of a named storage instance.
///
/// Without `--node`, queries the cluster-wide detail endpoint.
/// With `--node`, queries the per-node detail endpoint on the specified node.
@Command(name = "status", description = "Show storage instance status")
@SuppressWarnings("JBCT-RET-01")
class StorageStatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private StorageCommand parent;

    @CommandLine.Parameters(index = "0", description = "Storage instance name")
    private String name;

    @CommandLine.Option(names = "--node", description = "Target specific node")
    private String nodeId;

    @Override
    public Integer call() {
        var path = Option.option(nodeId)
                         .fold(() -> "/api/cluster/storage/" + name,
                               _ -> "/api/storage/" + name);

        return ClusterHttpClient.fetchFromCluster(path)
                                .fold(StorageCliHelper::onFailure,
                                      json -> OutputFormatter.printQuery(json, parent.outputOptions()));
    }
}
