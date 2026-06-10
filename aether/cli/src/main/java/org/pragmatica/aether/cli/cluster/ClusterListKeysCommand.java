// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_AUDIT;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_LIST;


@Command(name = "list-keys", description = "List cluster API keys")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterListKeysCommand implements Callable<Integer> {
    @Option(names = "--audit", description = "Show audit trail")
    private boolean showAudit;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> fetchKeys())
                            .fold(ClusterListKeysCommand::onFailure, this::onSuccess);
    }

    private Result<String> fetchKeys() {
        var keysResult = ClusterHttpClient.fetch(CLUSTER_KEYS_LIST);

        if (!showAudit) {
            return keysResult;
        }

        return keysResult.flatMap(this::appendAudit);
    }

    private Result<String> appendAudit(String keysJson) {
        return ClusterHttpClient.fetch(CLUSTER_KEYS_AUDIT).map(auditJson -> "{\"keys\":" + keysJson
                                                                           + ",\"audit\":" + auditJson
                                                                           + "}");
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions());
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
