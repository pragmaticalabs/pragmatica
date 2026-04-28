// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.lang.Option;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_STORAGE_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_LIST;


@Command(name = "list", description = "List storage instances") @SuppressWarnings("JBCT-RET-01") class StorageListCommand implements Callable<Integer> {
    @CommandLine.ParentCommand private StorageCommand parent;

    @CommandLine.Option(names = "--node", description = "Target specific node") private String nodeId;

    @Override public Integer call() {
        ManagementRoute route = Option.option(nodeId).fold(() -> CLUSTER_STORAGE_LIST, _ -> STORAGE_LIST);
        return ClusterHttpClient.fetch(route)
                                      .fold(StorageCliHelper::onFailure,
                                            json -> OutputFormatter.printQuery(json,
                                                                               parent.outputOptions()));
    }
}
