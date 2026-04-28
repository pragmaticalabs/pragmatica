// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_STATUS;


@Command(name = "status", description = "Show cluster status") @SuppressWarnings("JBCT-RET-01") class ClusterStatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_STATUS).fold(ClusterStatusCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions());
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}
