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
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_OWNERSHIP_GET;


@Command(name = "ownership", description = "Show this node's LOCAL committed ownership view (owner NodeId + fence Epoch per partition/key) for a domain (community|dht|stream)")
@SuppressWarnings("JBCT-RET-01")
class ClusterOwnershipCommand implements Callable<Integer> {
    // One row per committed ownership atom from the `entries` array; `identity` is the
    // domain-specific partition/key, `owner` the committed owner NodeId, the committed fence Epoch is
    // split into its (rabiaTerm, localCounter) columns, and the responding node's LOCAL per-domain
    // epoch high-water is split into HW-TERM/HW-CTR. FENCED is `true` when the high-water is strictly
    // after the committed epoch — the deposed-owner window in which this node has observed a newer
    // epoch than the committed owner shows (so the committed owner would be rejected as stale here);
    // in steady state high-water equals the committed epoch and FENCED is `false`. The view is
    // PER-NODE local — the table shows ownership + fence state as applied by whichever node served the
    // request. `--format json` exposes the full nested `epoch`/`highWater` objects, the `fenced` flag,
    // plus the `domain` at the response root.
    private static final TableSpec TABLE_SPEC = new TableSpec("Local Ownership View",
                                                              List.of(new Column("IDENTITY", "identity", 24),
                                                                      new Column("OWNER", "owner", 16),
                                                                      new Column("EPOCH-TERM", "epoch.rabiaTerm", 11),
                                                                      new Column("EPOCH-CTR", "epoch.localCounter", 10),
                                                                      new Column("HW-TERM", "highWater.rabiaTerm", 8),
                                                                      new Column("HW-CTR", "highWater.localCounter", 7),
                                                                      new Column("FENCED", "fenced", 7)),
                                                              "entries");

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Parameters(index = "0", paramLabel = "<domain>", description = "Ownership domain: community, dht, or stream")
    private String domain;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_OWNERSHIP_GET,
                                                                  List.of(domain)))
                            .fold(ClusterOwnershipCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TABLE_SPEC);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
