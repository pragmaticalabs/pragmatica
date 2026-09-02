// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_MEMBERSHIP_GET;


@Command(name = "membership", description = "Show this node's LOCAL membership view (per-peer FSM state, quorum-loss self-drain signal, "
                                          + "and core-absence fence countdown; the two fence summaries are root fields — use --format json)")
@SuppressWarnings("JBCT-RET-01")
class ClusterMembershipCommand implements Callable<Integer> {
    // Per-peer rows drawn from the `members` array; the strict/threshold/below/armed summary fields and
    // the #590 `coreAbsence` object live at the response root and are visible in `--format json`. The
    // view is PER-NODE local — the table shows the membership picture as seen by whichever node served
    // the request, and for core-absence that is the whole point: the node whose countdown you want is
    // the one the core is losing, so you query it directly rather than asking the leader.
    private static final TableSpec TABLE_SPEC = new TableSpec("Local Membership View",
                                                              List.of(new Column("NODE", "nodeId", 16),
                                                                      new Column("STATE", "state", 12),
                                                                      new Column("ROLE", "role", 10),
                                                                      new Column("INCARNATION", "incarnation", 11),
                                                                      new Column("STRICT-CORE", "strictCore", 11),
                                                                      new Column("COUNTED", "countsTowardEffective", 8)),
                                                              "members");

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_MEMBERSHIP_GET))
                            .fold(ClusterMembershipCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TABLE_SPEC);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
