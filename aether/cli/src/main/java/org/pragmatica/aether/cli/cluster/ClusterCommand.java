// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;


@Command(name = "cluster", description = "Cluster lifecycle management", subcommands = {ClusterInitCommand.class, ClusterBootstrapCommand.class, ClusterScaffoldCommand.class, ClusterListCommand.class, ClusterUseCommand.class, ClusterRemoveCommand.class, ClusterStatusCommand.class, ClusterExportCommand.class, ClusterApplyCommand.class, ClusterDrainCommand.class, ClusterDestroyCommand.class, ClusterScaleCommand.class, ClusterUpgradeCommand.class, ClusterMigrateCommand.class, ClusterTasksCommand.class, ClusterTopologyCommand.class, ClusterGovernorsCommand.class, ClusterGenerationCommand.class, ClusterAwaitQuiescedCommand.class, ClusterCreateKeyCommand.class, ClusterRotateKeyCommand.class, ClusterRevokeKeyCommand.class, ClusterListKeysCommand.class, ClusterAuditCommand.class})
@Contract
public class ClusterCommand implements Runnable {
    @CommandLine.ParentCommand
    private AetherCli parent;

    OutputOptions outputOptions() {
        return parent.outputOptions();
    }

    @Contract
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
