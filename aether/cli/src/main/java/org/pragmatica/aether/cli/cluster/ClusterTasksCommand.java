package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/// Lists and manages task group assignments across cluster nodes.
///
/// Default subcommand lists current assignments by calling `GET /api/cluster/tasks`.
/// The `reassign` subcommand triggers manual reassignment via `PUT /api/cluster/tasks/{group}/reassign`.
@Command(name = "tasks", description = "Task group assignment management", subcommands = {ClusterTasksCommand.ReassignCommand.class}) @SuppressWarnings("JBCT-RET-01") class ClusterTasksCommand implements Callable<Integer> {
    private static final TableSpec TASKS_TABLE = new TableSpec("Task Group Assignments",
                                                               List.of(new Column("GROUP", "group", 14),
                                                                       new Column("NODE", "assignedNode", 12),
                                                                       new Column("STATUS", "status", 36),
                                                                       new Column("SINCE", "since", 26)),
                                                               "assignments");

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/tasks")
                                                 .fold(ClusterTasksCommand::onFailure, this::onSuccess);
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
            if (group == null || group.isBlank()) {return new TasksError.MissingGroup().result();}
            if (targetNode == null || targetNode.isBlank()) {return new TasksError.MissingTarget().result();}
            return Result.success(group.toUpperCase());
        }

        private Result<String> sendReassignRequest(String validGroup) {
            var path = "/api/cluster/tasks/" + validGroup + "/reassign";
            var jsonBody = "{\"targetNode\":\"" + escapeJson(targetNode) + "\"}";
            return ClusterHttpClient.putToCluster(path, jsonBody);
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

    sealed interface TasksError extends Cause {
        record MissingGroup() implements TasksError {
            @Override public String message() {
                return "Task group name is required (use --group)";
            }
        }

        record MissingTarget() implements TasksError {
            @Override public String message() {
                return "Target node ID is required (use --target)";
            }
        }
    }
}
