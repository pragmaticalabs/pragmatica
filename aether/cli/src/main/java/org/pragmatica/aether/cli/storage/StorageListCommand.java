package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.lang.Option;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Lists storage instances, either cluster-wide or on a specific node.
///
/// Without `--node`, queries the cluster-wide aggregation endpoint.
/// With `--node`, queries the per-node endpoint on the specified node.
@Command(name = "list", description = "List storage instances")
@SuppressWarnings("JBCT-RET-01")
class StorageListCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private StorageCommand parent;

    @CommandLine.Option(names = "--node", description = "Target specific node")
    private String nodeId;

    @Override
    public Integer call() {
        var path = Option.option(nodeId)
                         .fold(() -> "/api/cluster/storage",
                               _ -> "/api/storage");

        return ClusterHttpClient.fetchFromCluster(path)
                                .fold(StorageCliHelper::onFailure,
                                      json -> OutputFormatter.printQuery(json, parent.outputOptions()));
    }
}
