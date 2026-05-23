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
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TASKS_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TASK_REASSIGN;


@Command(name = "tasks",
         description = "Task group assignment management",
         subcommands = {ClusterTasksCommand.ListCommand.class, ClusterTasksCommand.StatusCommand.class, ClusterTasksCommand.ReassignCommand.class}) @SuppressWarnings("JBCT-RET-01") class ClusterTasksCommand implements Callable<Integer> {
    private static final TableSpec TASKS_TABLE = new TableSpec("Task Group Assignments",
                                                               List.of(new Column("GROUP", "group", 14),
                                                                       new Column("NODE", "assignedTo", 12),
                                                                       new Column("STATUS", "status", 36),
                                                                       new Column("SINCE", "assignedAt", 26)),
                                                               "assignments");

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override public Integer call() {
        return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_TASKS_LIST))
                                           .fold(ClusterTasksCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TASKS_TABLE);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    @Command(name = "list", description = "List all task group assignments") @SuppressWarnings("JBCT-RET-01") static class ListCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private ClusterTasksCommand parent;

        @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

        @Override public Integer call() {
            return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_TASKS_LIST))
                                               .fold(ListCommand::onFailure, this::onSuccess);
        }

        private int onSuccess(String json) {
            return OutputFormatter.printQuery(json, parent.parent.outputOptions(), TASKS_TABLE);
        }

        private static int onFailure(Cause cause) {
            System.err.println("Error: " + cause.message());
            return ExitCode.ERROR;
        }
    }

    @Command(name = "status", description = "Show assignment for a single task group") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"}) static class StatusCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Task group name (e.g., SCALING, METRICS)") private String group;

        @CommandLine.ParentCommand private ClusterTasksCommand parent;

        @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

        @Override public Integer call() {
            return clusterTarget.applyOverrides().flatMap(_ -> validateGroup())
                                               .flatMap(this::fetchAndFilter)
                                               .fold(this::onFailure, this::onSuccess);
        }

        private Result<String> validateGroup() {
            return Verify.ensure(group, Verify.Is::present, TasksError.MISSING_GROUP).map(_ -> group.toUpperCase());
        }

        private Result<String> fetchAndFilter(String upperGroup) {
            return ClusterHttpClient.fetch(CLUSTER_TASKS_LIST).flatMap(json -> filterByGroup(json, upperGroup));
        }

        private int onSuccess(String json) {
            return OutputFormatter.printQuery(json, parent.parent.outputOptions(), TASKS_TABLE);
        }

        private int onFailure(Cause cause) {
            if (cause == TasksError.GROUP_NOT_FOUND) {
                System.err.println("Error: task group '" + group + "' not found");
                return ExitCode.ERROR;
            }
            System.err.println("Error: " + cause.message());
            return ExitCode.ERROR;
        }
    }

    @Command(name = "reassign", description = "Reassign a task group to a different node") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) static class ReassignCommand implements Callable<Integer> {
        @Option(names = "--group", required = true, description = "Task group to reassign (e.g., SCALING, METRICS)") private String group;

        @Option(names = "--target", required = true, description = "Target node ID to receive the task group") private String targetNode;

        @CommandLine.ParentCommand private ClusterTasksCommand parent;

        @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

        @Override public Integer call() {
            return clusterTarget.applyOverrides().flatMap(_ -> validateInputs())
                                               .flatMap(this::sendReassignRequest)
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

    /// Hand-rolled extractor: locates the `{ ... }` record whose `"group":"<UPPER>"`
    /// marker is present, then wraps it back into the `{"assignments":[...]}` envelope
    /// so the existing `TASKS_TABLE` rendering (and `--format value --field
    /// assignments.0.<col>`) keeps working unchanged. Jackson is intentionally avoided
    /// here — the response is a flat list of records whose only nested structures are
    /// scalar fields, so a simple brace-depth counter suffices. Mirrors the
    /// `parsePushStatus` precedent in `AetherCli`.
    static Result<String> filterByGroup(String json, String upperGroup) {
        var marker = "\"group\":\"" + upperGroup + "\"";
        var idx = json.indexOf(marker);
        if (idx <0) {return TasksError.GROUP_NOT_FOUND.result();}
        var objStart = json.lastIndexOf('{', idx);
        if (objStart <0) {return TasksError.MALFORMED_RESPONSE.result();}
        var objEnd = findMatchingBrace(json, objStart);
        if (objEnd <0) {return TasksError.MALFORMED_RESPONSE.result();}
        return Result.success("{\"assignments\":[" + json.substring(objStart, objEnd + 1) + "]}");
    }

    @SuppressWarnings("JBCT-RET-01") private static int findMatchingBrace(String json, int openIdx) {
        var depth = 0;
        for (var i = openIdx; i <json.length(); i++) {
            var ch = json.charAt(i);
            if (ch == '{') {depth++;}
            if (ch == '}') {
                depth--;
                if (depth == 0) {return i;}
            }
        }
        return - 1;
    }

    enum TasksError implements Cause {
        MISSING_GROUP("Task group name is required (use --group)"),
        MISSING_TARGET("Target node ID is required (use --target)"),
        GROUP_NOT_FOUND("Task group not found in cluster"),
        MALFORMED_RESPONSE("Could not parse /api/cluster/tasks response");

        private final String message;
        TasksError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
