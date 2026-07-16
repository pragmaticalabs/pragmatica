// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.ttm;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.TTM_STATUS;


@Command(name = "status", description = "Show foundation-model / TTM runtime status")
@SuppressWarnings("JBCT-RET-01")
class TtmStatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private TtmCommand parent;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetch(TTM_STATUS).fold(TtmCliHelper::onFailure,
                                                        json -> OutputFormatter.printQuery(json, parent.outputOptions()));
    }
}
