// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.storage;

import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_SNAPSHOT;


@Command(name = "snapshot", description = "Force a metadata snapshot")
@SuppressWarnings("JBCT-RET-01")
class StorageSnapshotCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private StorageCommand parent;

    @CommandLine.Parameters(index = "0", description = "Storage instance name")
    private String name;

    @Override
    public Integer call() {
        return ClusterHttpClient.post(STORAGE_SNAPSHOT,
                                      List.of(name),
                                      "{}")
                                .fold(StorageCliHelper::onFailure,
                                      json -> OutputFormatter.printAction(json,
                                                                          parent.outputOptions(),
                                                                          "Snapshot triggered: " + name));
    }
}
