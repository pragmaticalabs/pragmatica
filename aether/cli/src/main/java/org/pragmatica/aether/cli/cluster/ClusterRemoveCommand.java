package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Removes a cluster from the registry.
///
/// If the removed cluster is the current context, the current context is cleared.
/// This command does not destroy the actual cluster -- it only removes the local registry entry.
@Command(name = "remove", description = "Remove a cluster from the registry") @SuppressWarnings("JBCT-RET-01") class ClusterRemoveCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Cluster name to remove") private String name;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterRegistry.load().flatMap(registry -> registry.remove(name))
                                   .flatMap(ClusterRegistry::save)
                                   .fold(ClusterRemoveCommand::onFailure,
                                         _ -> onSuccess());
    }

    private int onSuccess() {
        return OutputFormatter.printAction("{\"removed\":\"" + name + "\"}",
                                           parent.outputOptions(),
                                           "Removed cluster: " + name);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
