package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Displays aggregated cluster status from the management API.
///
/// Default output is a human-readable table. Use `--format json` for raw JSON.
@Command(name = "status", description = "Show cluster status")
@SuppressWarnings("JBCT-RET-01")
class ClusterStatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/status")
                                .fold(ClusterStatusCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions());
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
