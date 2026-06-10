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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_JOURNAL;
import static org.pragmatica.lang.Option.option;


/// `aether cluster journal` — dump the per-node FSM/PeerState transition journal
/// (cluster-topology-overhaul spec, Wave 1 Enrichment A; `GET /api/cluster/journal`).
@Command(name = "journal", description = "Dump the per-node membership-FSM / transport-peer transition journal (diagnostic)")
@SuppressWarnings("JBCT-RET-01")
class ClusterJournalCommand implements Callable<Integer> {
    private static final TableSpec JOURNAL_TABLE = new TableSpec("Transition Journal",
                                                                 List.of(new Column("SEQ", "seq", 8),
                                                                         new Column("TIME(MS)", "wallClockMs", 14),
                                                                         new Column("LAYER", "layer", 6),
                                                                         new Column("NODE", "nodeId", 16),
                                                                         new Column("FROM", "from", 14),
                                                                         new Column("TO", "to", 14),
                                                                         new Column("CAUSE", "cause", 32),
                                                                         new Column("INC", "incarnation", 6),
                                                                         new Column("ROLE", "role", 8)),
                                                                 "entries");

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @CommandLine.Option(names = {"--layer"}, description = "Journal layer to dump: fsm or peer (default: both)")
    private String layer;

    @CommandLine.Option(names = {"--limit"}, description = "Maximum number of entries per layer (default: 256)")
    private Integer limit;

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_JOURNAL, List.of(), buildQuery()))
                            .fold(ClusterJournalCommand::onFailure, this::onSuccess);
    }

    private String buildQuery() {
        var parts = new ArrayList<String>();

        option(layer).onPresent(value -> parts.add("layer=" + value));
        option(limit).onPresent(value -> parts.add("limit=" + value));

        return String.join("&", parts);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), JOURNAL_TABLE);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
