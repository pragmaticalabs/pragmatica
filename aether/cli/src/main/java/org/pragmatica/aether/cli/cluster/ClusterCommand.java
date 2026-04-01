package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/// Cluster lifecycle management command group.
///
/// Provides subcommands for managing the cluster registry:
/// listing registered clusters, switching active context, and removing entries.
@Command(name = "cluster",
description = "Cluster lifecycle management",
subcommands = {ClusterBootstrapCommand.class, ClusterListCommand.class, ClusterUseCommand.class, ClusterRemoveCommand.class, ClusterStatusCommand.class, ClusterExportCommand.class, ClusterApplyCommand.class, ClusterDrainCommand.class, ClusterDestroyCommand.class, ClusterScaleCommand.class, ClusterUpgradeCommand.class})
@Contract public class ClusterCommand implements Runnable {
    @CommandLine.ParentCommand private AetherCli parent;

    OutputOptions outputOptions() {
        return parent.outputOptions();
    }

    @Contract @Override public void run() {
        CommandLine.usage(this, System.out);
    }
}
