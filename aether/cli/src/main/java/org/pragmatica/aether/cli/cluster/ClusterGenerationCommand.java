// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormat;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_GENERATION;


@Command(name = "generation", description = "Show current cluster generation snapshot")
class ClusterGenerationCommand implements Callable<Integer> {
    private static final TableSpec MEMBERS_TABLE = new TableSpec("Core Members",
                                                                 List.of(new Column("NODE", "nodeId", 18),
                                                                         new Column("LIFECYCLE", "lifecycle", 12),
                                                                         new Column("HEALTH", "healthHint", 12),
                                                                         new Column("HOST", "host", 24),
                                                                         new Column("PORT", "port", 6)),
                                                                 "core.members");

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Contract
    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_GENERATION))
                            .fold(ClusterGenerationCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return parent.outputOptions()
                     .format() == OutputFormat.TABLE
               ? printTableSummary(json)
               : OutputFormatter.printQuery(json, parent.outputOptions(), MEMBERS_TABLE);
    }

    private int printTableSummary(String json) {
        return OutputFormatter.MAPPER.readTree(json).fold(ClusterGenerationCommand::onFailure,
                                                          ClusterGenerationCommand::renderSummary);
    }

    private static int renderSummary(JsonNode node) {
        var out = System.out;

        out.printf("Epoch:              %s%n", epochString(node));
        out.printf("Mode:               %s%n", textField(node, "mode"));
        out.printf("Quiescence:         %s%n", textField(node, "quiescence"));
        out.printf("Rabia term:         %d%n", longField(node, "rabiaTerm"));
        out.printf("Desired core size:  %d%n", intField(childNode(node, "core"), "desiredSize"));
        out.printf("Core members:       %d%n", arraySize(childNode(node, "core"), "members"));
        out.printf("Communities:        %d%n", arraySize(node, "communities"));
        out.printf("Partitions:         %d%n", arraySize(node, "partitions"));

        return ExitCode.SUCCESS;
    }

    private static String epochString(JsonNode root) {
        var epochNode = root.get("epoch");

        return epochNode == null || epochNode.isNull()
               ? "(unavailable)"
               : epochNode.get("rabiaTerm")
                          .asLong() + ":" + epochNode.get("localCounter")
                                                     .asLong();
    }

    // RET-06: `root` is a nullable Jackson JsonNode (childNode returns null for absent nodes) —
    // a framework boundary, not a business optional.
    @SuppressWarnings("JBCT-RET-06")
    private static String textField(JsonNode root, String field) {
        var value = root == null
                    ? null
                    : root.get(field);

        return value == null
               ? ""
               : value.asText();
    }

    // RET-06: `root` is a nullable Jackson JsonNode — framework boundary, not a business optional.
    @SuppressWarnings("JBCT-RET-06")
    private static long longField(JsonNode root, String field) {
        var value = root == null
                    ? null
                    : root.get(field);

        return value == null
               ? 0L
               : value.asLong();
    }

    // RET-06: `root` is a nullable Jackson JsonNode — framework boundary, not a business optional.
    @SuppressWarnings("JBCT-RET-06")
    private static int intField(JsonNode root, String field) {
        var value = root == null
                    ? null
                    : root.get(field);

        return value == null
               ? 0
               : value.asInt();
    }

    // RET-06: `root` is a nullable Jackson JsonNode — framework boundary, not a business optional.
    @SuppressWarnings("JBCT-RET-06")
    private static JsonNode childNode(JsonNode root, String field) {
        return root == null
               ? null
               : root.get(field);
    }

    private static int arraySize(JsonNode root, String field) {
        var array = childNode(root, field);

        return array == null || !array.isArray()
               ? 0
               : array.size();
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
