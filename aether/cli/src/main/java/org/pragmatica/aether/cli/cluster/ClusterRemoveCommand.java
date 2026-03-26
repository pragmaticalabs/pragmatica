package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Unit;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Removes a cluster from the registry.
///
/// If the removed cluster is the current context, the current context is cleared.
/// This command does not destroy the actual cluster -- it only removes the local registry entry.
@Command(name = "remove", description = "Remove a cluster from the registry")
@SuppressWarnings("JBCT-RET-01")
class ClusterRemoveCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Cluster name to remove")
    private String name;

    @Override
    public Integer call() {
        return ClusterRegistry.load()
                              .flatMap(registry -> registry.remove(name))
                              .flatMap(ClusterRegistry::save)
                              .fold(ClusterRemoveCommand::onFailure, this::onSuccess);
    }

    private Integer onSuccess(Unit unit) {
        System.out.println("Removed cluster: " + name);
        return 0;
    }

    private static Integer onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return 1;
    }
}
