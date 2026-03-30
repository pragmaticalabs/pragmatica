package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.lang.Cause;

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
        var path = nodeId != null
                   ? "/api/storage"
                   : "/api/cluster/storage";

        return ClusterHttpClient.fetchFromCluster(path)
                                .fold(StorageListCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions());
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
