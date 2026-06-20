// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_PROVISIONING_GET;


@Command(name = "provisioning", description = "Show leader provisioning diagnostics (why a core-membership deficit is or is not being filled)")
@SuppressWarnings("JBCT-RET-01")
class ClusterProvisioningCommand implements Callable<Integer> {
    private static final TableSpec TABLE_SPEC = new TableSpec("Provisioning Diagnostics",
                                                              List.of(new Column("LEADER", "leader", 7),
                                                                      new Column("CONFIGURED", "configuredCoreCount", 10),
                                                                      new Column("COUNTED", "countedCoreMembers", 8),
                                                                      new Column("EFFECTIVE", "effective", 9),
                                                                      new Column("DEFICIT", "deficit", 7),
                                                                      new Column("ARMED", "armedForProvisioning", 6),
                                                                      new Column("FULL", "reachedFullMembership", 6),
                                                                      new Column("QUORUM", "quorumSafe", 7),
                                                                      new Column("BREAKER", "circuitBreaker.tripped", 8),
                                                                      new Column("REASON", "lastReason", 40)),
                                                              null);

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_PROVISIONING_GET))
                            .fold(ClusterProvisioningCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TABLE_SPEC);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
