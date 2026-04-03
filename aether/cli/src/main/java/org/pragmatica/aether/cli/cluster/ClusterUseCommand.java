package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Switches the active cluster context to the specified name.
///
/// The named cluster must already exist in the registry.
@Command(name = "use", description = "Switch active cluster context") @SuppressWarnings("JBCT-RET-01") class ClusterUseCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Cluster name to activate") private String name;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterRegistry.load().flatMap(registry -> registry.use(name))
                                   .flatMap(ClusterRegistry::save)
                                   .fold(ClusterUseCommand::onFailure,
                                         _ -> onSuccess());
    }

    private int onSuccess() {
        return OutputFormatter.printAction("{\"context\":\"" + name + "\"}",
                                           parent.outputOptions(),
                                           "Switched to cluster context: " + name);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
