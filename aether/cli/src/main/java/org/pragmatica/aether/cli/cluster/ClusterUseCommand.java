package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Unit;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/// Switches the active cluster context to the specified name.
///
/// The named cluster must already exist in the registry.
@Command(name = "use", description = "Switch active cluster context")
@SuppressWarnings("JBCT-RET-01")
class ClusterUseCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Cluster name to activate")
    private String name;

    @Override
    public Integer call() {
        return ClusterRegistry.load()
                              .flatMap(registry -> registry.use(name))
                              .flatMap(ClusterRegistry::save)
                              .fold(ClusterUseCommand::onFailure, this::onSuccess);
    }

    private Integer onSuccess(Unit unit) {
        System.out.println("Switched to cluster context: " + name);
        return 0;
    }

    private static Integer onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return 1;
    }
}
