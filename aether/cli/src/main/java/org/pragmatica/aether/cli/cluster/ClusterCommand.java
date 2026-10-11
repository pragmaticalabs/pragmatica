// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;


@Command(name = "cluster", description = "Cluster lifecycle management", subcommands = {ClusterInitCommand.class, ClusterBootstrapCommand.class, ClusterScaffoldCommand.class, ClusterListCommand.class, ClusterUseCommand.class, ClusterRemoveCommand.class, ClusterStatusCommand.class, ClusterProvisioningCommand.class, ClusterMembershipCommand.class, ClusterOwnershipCommand.class, ClusterCommunitiesCommand.class, ClusterExportCommand.class, ClusterApplyCommand.class, ClusterDrainCommand.class, ClusterDestroyCommand.class, ClusterScaleCommand.class, ClusterUpgradeCommand.class, ClusterMigrateCommand.class, ClusterTopologyCommand.class, ClusterJournalCommand.class, ClusterGovernorsCommand.class, ClusterGenerationCommand.class, ClusterAwaitQuiescedCommand.class, ClusterCreateKeyCommand.class, ClusterRotateKeyCommand.class, ClusterRotateGossipKeyCommand.class, ClusterRevokeKeyCommand.class, ClusterListKeysCommand.class})
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
