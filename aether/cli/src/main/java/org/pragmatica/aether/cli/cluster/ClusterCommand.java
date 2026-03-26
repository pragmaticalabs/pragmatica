package org.pragmatica.aether.cli.cluster;

import picocli.CommandLine.Command;

/// Cluster lifecycle management command group.
///
/// Provides subcommands for managing the cluster registry:
/// listing registered clusters, switching active context, and removing entries.
@Command(name = "cluster",
description = "Cluster lifecycle management",
subcommands = {ClusterListCommand.class,
ClusterUseCommand.class,
ClusterRemoveCommand.class,
ClusterStatusCommand.class,
ClusterExportCommand.class,
ClusterApplyCommand.class,
ClusterDrainCommand.class,
ClusterDestroyCommand.class,
ClusterScaleCommand.class,
ClusterUpgradeCommand.class})
@SuppressWarnings("JBCT-RET-01")
public class ClusterCommand implements Runnable {
    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }
}
