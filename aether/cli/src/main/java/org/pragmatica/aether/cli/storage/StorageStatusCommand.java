// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.storage;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_STORAGE_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_GET;


/// Displays detailed status of a named storage instance.
///
/// Without `--node`, queries the cluster-wide detail endpoint.
/// With `--node`, queries the per-node detail endpoint on the specified node.
@Command(name = "status", description = "Show storage instance status") @SuppressWarnings("JBCT-RET-01") class StorageStatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand private StorageCommand parent;

    @CommandLine.Parameters(index = "0", description = "Storage instance name") private String name;

    @CommandLine.Option(names = "--node", description = "Target specific node") private String nodeId;

    @Override public Integer call() {
        ManagementRoute route = Option.option(nodeId).fold(() -> CLUSTER_STORAGE_GET, _ -> STORAGE_GET);
        return ClusterHttpClient.fetch(route,
                                       List.of(name))
        .fold(StorageCliHelper::onFailure,
              json -> OutputFormatter.printQuery(json,
                                                 parent.outputOptions()));
    }
}
