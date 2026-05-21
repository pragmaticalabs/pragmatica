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
import picocli.CommandLine.Parameters;


@Command(name = "remove", description = "Remove a cluster from the registry")
@SuppressWarnings("JBCT-RET-01")
class ClusterRemoveCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Cluster name to remove")
    private String name;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Override
    public Integer call() {
        return ClusterRegistry.load()
                              .flatMap(registry -> registry.remove(name))
                              .flatMap(ClusterRegistry::save)
                              .fold(ClusterRemoveCommand::onFailure,
                                    _ -> onSuccess());
    }

    private int onSuccess() {
        return OutputFormatter.printAction("{\"removed\":\"" + name + "\"}",
                                           parent.outputOptions(),
                                           "Removed cluster: " + name);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
