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
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.AUDIT_COMMANDS_LIST;


/// `aether cluster audit` — Phase 3 PR-C operator-facing audit channel for
/// `audit.lifecycle.commands` events seen by the target node.
///
/// Wraps `GET /api/audit/commands`. Per the Phase 3 PR-C scope note, the backing buffer is
/// per-node and in-memory — operators should target the leader (`-c <leader-host>`) for
/// cluster-wide visibility.
///
/// Examples:
///   aether cluster audit
///   aether cluster audit --source operator --since 1h
///   aether cluster audit --source reconciler --since 2026-05-23T10:00:00Z --limit 50
///   aether cluster audit --format json
@Command(name = "audit", description = "Show recent audit.lifecycle.commands events seen by the target node")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterAuditCommand implements Callable<Integer> {
    private static final TableSpec AUDIT_TABLE = new TableSpec("Audit Commands",
                                                                List.of(new Column("TIMESTAMP", "timestampMs", 14),
                                                                        new Column("TYPE", "commandType", 18),
                                                                        new Column("PEER", "peerId", 18),
                                                                        new Column("SOURCE", "source", 18),
                                                                        new Column("REASON_TAG", "reasonTag", 14),
                                                                        new Column("JUSTIFICATION", "justificationMessage", 50)),
                                                                "events");

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Option(names = "--source", description = "Filter by emitter source (operator|reconciler|ctm|drain_coordinator|bootstrap|all). Default: all.")
    private String source;

    @Option(names = "--since", description = "Time window (epoch-ms, ISO-8601, or relative like 30s/5m/1h/2d). Default: all entries in buffer.")
    private String since;

    @Option(names = "--limit", description = "Maximum entries to return (default 100, capped by buffer capacity).")
    private Integer limit;

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(AUDIT_COMMANDS_LIST, List.of(), buildQueryString()))
                            .fold(ClusterAuditCommand::onFailure, this::onSuccess);
    }

    private String buildQueryString() {
        var parts = new ArrayList<String>();
        if (source != null && !source.isBlank()) {
            parts.add("source=" + source.trim());
        }
        if (since != null && !since.isBlank()) {
            parts.add("since=" + since.trim());
        }
        if (limit != null && limit > 0) {
            parts.add("limit=" + limit);
        }
        return String.join("&", parts);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), AUDIT_TABLE);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
