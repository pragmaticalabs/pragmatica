// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_KEYS_REVOKE;


@Command(name = "revoke-key", description = "Revoke an API key")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterRevokeKeyCommand implements Callable<Integer> {
    private static final long DEFAULT_GRACE_PERIOD_MS = 300_000;

    @Parameters(index = "0", description = "Key ID to revoke")
    private String keyId;

    @Option(names = "--immediate", description = "Skip grace period")
    private boolean immediate;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> sendRevokeRequest())
                            .fold(ClusterRevokeKeyCommand::onFailure, this::onSuccess);
    }

    private Result<String> sendRevokeRequest() {
        var gracePeriodMs = immediate
                            ? 0
                            : DEFAULT_GRACE_PERIOD_MS;
        var json = "{\"immediate\":" + immediate
                 + ",\"gracePeriodMs\":" + gracePeriodMs
                 + ",\"operatorHint\":\"cli-revoke-key\"}";

        return ClusterHttpClient.post(CLUSTER_KEYS_REVOKE, List.of(keyId), json);
    }

    private int onSuccess(String json) {
        var message = immediate
                      ? "Key " + keyId + " revoked immediately."
                      : "Key " + keyId + " revoked with grace period.";
        return OutputFormatter.printAction(json, parent.outputOptions(), message);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
