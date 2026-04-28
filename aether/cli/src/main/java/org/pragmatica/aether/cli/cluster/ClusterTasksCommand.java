// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TASKS_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TASK_REASSIGN;


@Command(name = "tasks", description = "Task group assignment management", subcommands = {ClusterTasksCommand.ReassignCommand.class}) @SuppressWarnings("JBCT-RET-01") class ClusterTasksCommand implements Callable<Integer> {
    private static final TableSpec TASKS_TABLE = new TableSpec("Task Group Assignments",
                                                               List.of(new Column("GROUP", "group", 14),
                                                                       new Column("NODE", "assignedNode", 12),
                                                                       new Column("STATUS", "status", 36),
                                                                       new Column("SINCE", "since", 26)),
                                                               "assignments");

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterHttpClient.fetch(CLUSTER_TASKS_LIST).fold(ClusterTasksCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TASKS_TABLE);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    @Command(name = "reassign", description = "Reassign a task group to a different node") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) static class ReassignCommand implements Callable<Integer> {
        @Option(names = "--group", required = true, description = "Task group to reassign (e.g., SCALING, METRICS)") private String group;

        @Option(names = "--target", required = true, description = "Target node ID to receive the task group") private String targetNode;

        @CommandLine.ParentCommand private ClusterTasksCommand parent;

        @Override public Integer call() {
            return validateInputs().flatMap(this::sendReassignRequest)
                                 .fold(ReassignCommand::onFailure, this::onSuccess);
        }

        private Result<String> validateInputs() {
            return Verify.ensure(group, Verify.Is::present, TasksError.MISSING_GROUP).flatMap(_ -> Verify.ensure(targetNode,
                                                                                                                 Verify.Is::present,
                                                                                                                 TasksError.MISSING_TARGET))
                                .map(_ -> group.toUpperCase());
        }

        private Result<String> sendReassignRequest(String validGroup) {
            var jsonBody = "{\"targetNode\":\"" + escapeJson(targetNode) + "\"}";
            return ClusterHttpClient.put(CLUSTER_TASK_REASSIGN, List.of(validGroup), jsonBody);
        }

        private static String escapeJson(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private int onSuccess(String json) {
            return OutputFormatter.printAction(json,
                                               parent.parent.outputOptions(),
                                               "Task group " + group.toUpperCase() + " reassigned to " + targetNode);
        }

        private static int onFailure(Cause cause) {
            System.err.println("Error: " + cause.message());
            return ExitCode.ERROR;
        }
    }

    enum TasksError implements Cause {
        MISSING_GROUP("Task group name is required (use --group)"),
        MISSING_TARGET("Target node ID is required (use --target)");
        private final String message;
        TasksError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
