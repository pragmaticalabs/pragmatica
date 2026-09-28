// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.backup;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormat;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.aether.cli.cluster.ClusterTargetMixin;
import org.pragmatica.lang.Cause;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static org.pragmatica.aether.management.route.ManagementRoute.BACKUP_DECLARE_GENESIS;

/// #1532 — make this cluster's state the KV backup head in place of a backup of ANOTHER lineage. ADMIN.
///
/// A freshly started cluster is gated while the backup head belongs to another cluster history; this is
/// the operator's statement that this cluster's state is the one to keep. It refuses when the head is
/// this cluster's own lineage — and when that head is newer than the cluster, the right action is a
/// restore, not this.
@Command(name = "declare-genesis", description = "Make this cluster's state the KV backup head, superseding a backup of another lineage (ADMIN)")
@SuppressWarnings("JBCT-RET-01")
class BackupDeclareGenesisCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private BackupCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.post(BACKUP_DECLARE_GENESIS, "{}"))
                            .fold(BackupDeclareGenesisCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        if (parent.outputOptions().format() == OutputFormat.JSON) {
            System.out.println(json);

            return ExitCode.SUCCESS;
        }

        System.out.println("Genesis declared; the next backup supersedes the previous head (response: " + json + ")");

        return ExitCode.SUCCESS;
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
