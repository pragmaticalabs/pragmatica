// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.ttm;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.TTM_TRAINING_DATA;


@Command(name = "training-data", description = "Show foundation-model / TTM training data snapshot")
@SuppressWarnings("JBCT-RET-01")
class TtmTrainingDataCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private TtmCommand parent;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetch(TTM_TRAINING_DATA).fold(TtmCliHelper::onFailure,
                                                               json -> OutputFormatter.printQuery(json,
                                                                                                  parent.outputOptions()));
    }
}
