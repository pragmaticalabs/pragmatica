// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormat;
import org.pragmatica.lang.Cause;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_AWAIT_QUIESCED;


@Command(name = "await-quiesced", description = "Wait until cluster reaches the given epoch and quiesces")
@SuppressWarnings("JBCT-RET-01")
class ClusterAwaitQuiescedCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Option(names = "--epoch", description = "Required epoch in T:C form (e.g. 7:142)", required = true)
    private String epoch;

    @Option(names = "--timeout", description = "Timeout (default 30s, max 120s)", defaultValue = "30s")
    private String timeout;

    @Override
    public Integer call() {
        var query = "epoch=" + epoch + "&timeout=" + timeout;

        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.post(CLUSTER_AWAIT_QUIESCED,
                                                                 List.of(),
                                                                 query,
                                                                 "{}"))
                            .fold(ClusterAwaitQuiescedCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        if (parent.outputOptions().format() == OutputFormat.JSON) {
            System.out.println(json);

            return ExitCode.SUCCESS;
        }

        System.out.println("Quiesced at " + epoch + " (response: " + json + ")");

        return ExitCode.SUCCESS;
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
