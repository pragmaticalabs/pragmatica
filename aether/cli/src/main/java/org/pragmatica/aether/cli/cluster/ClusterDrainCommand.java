// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.DestructiveAction;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODE_DRAIN;


@Command(name = "drain", description = "Drain a node (evacuate slices)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterDrainCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Node ID to drain")
    private String nodeId;

    @Option(names = "--wait", description = "Fail unless completion can be observed: through the cluster endpoint it cannot, so this reports why after the drain is accepted")
    private boolean waitForCompletion;

    @Option(names = {"--yes", "--force"}, description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        if (!DestructiveAction.destructiveAction().confirm(skipConfirmation,
                                                           "This will drain node " + nodeId
                                                          + " (evacuate all its slices).")) {
            System.out.println("Aborted.");

            return ExitCode.SUCCESS;
        }

        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.post(NODE_DRAIN,
                                                                 List.of(nodeId),
                                                                 "{}"))
                            .flatMap(MAPPER::readTree)
                            .fold(this::onFailure, this::onDrainInitiated);
    }

    private int onDrainInitiated(JsonNode root) {
        var success = root.path("success").asBoolean(false);
        var state = root.path("state").asText("UNKNOWN");
        var message = root.path("message").asText("");

        if (!success) {
            return handleDrainRejection(state, message);
        }

        System.out.printf("Drain initiated for node %s (state: %s)%n", nodeId, state);
        if (waitForCompletion) {
            System.err.println(new DrainCompletion.NotObservable(nodeId).message());

            return ExitCode.ERROR;
        }

        return ExitCode.SUCCESS;
    }

    private static int handleDrainRejection(String state, String message) {
        if (state.contains("DRAINING")) {
            System.out.printf("Node already %s: %s%n", state, message);

            return ExitCode.SUCCESS;
        }

        System.err.printf("Failed to drain: %s%n", message);

        return ExitCode.ERROR;
    }

    private int onFailure(Cause cause) {
        return OutputFormatter.printError(cause, parent.outputOptions());
    }
}
