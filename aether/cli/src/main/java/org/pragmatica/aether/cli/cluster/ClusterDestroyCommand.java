package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

/// Destroys the active cluster: drains all nodes, shuts them down, and removes the registry entry.
///
/// Requires interactive confirmation unless `--yes` is provided. Each node is drained sequentially,
/// then shut down. The local registry entry is removed on completion.
@Command(name = "destroy", description = "Destroy the active cluster (drain + shutdown all nodes)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterDestroyCommand implements Callable<Integer> {
    private static final int DRAIN_POLL_INTERVAL_MS = 2000;
    private static final int DRAIN_TIMEOUT_SECONDS = 120;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Option(names = "--yes", description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @Override public Integer call() {
        return ClusterRegistry.load().flatMap(this::executeDestroy)
                                   .fold(ClusterDestroyCommand::onFailure, v -> v);
    }

    private Result<Integer> executeDestroy(ClusterRegistry registry) {
        return registry.current().toResult(ClusterHttpClient.HttpError.NO_ACTIVE_CLUSTER)
                               .flatMap(entry -> destroyCluster(registry, entry));
    }

    private Result<Integer> destroyCluster(ClusterRegistry registry, ClusterRegistry.ClusterEntry entry) {
        if ( !skipConfirmation && !confirmDestruction(entry.name())) {
            System.out.println("Aborted.");
            return Result.success(ExitCode.SUCCESS);
        }
        return performDestruction(registry, entry);
    }

    private Result<Integer> performDestruction(ClusterRegistry registry, ClusterRegistry.ClusterEntry entry) {
        var nodeIds = fetchNodeIds();
        var drainResults = drainAllNodes(nodeIds);
        var shutdownResults = shutdownAllNodes(nodeIds);
        return removeRegistryEntry(registry, entry.name())
        .map(_ -> printSummary(entry.name(), nodeIds, drainResults, shutdownResults));
    }

    private static boolean confirmDestruction(String clusterName) {
        System.out.printf("This will destroy cluster '%s' and shut down all nodes.%n", clusterName);
        System.out.print("Type the cluster name to confirm: ");
        System.out.flush();
        var input = readLineFromConsole();
        return clusterName.equals(input.trim());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String readLineFromConsole() {
        try {
            var bytes = System.in.readNBytes(256);
            return new String(bytes).trim();
        }

























        catch (Exception _) {
            return "";
        }
    }

    private List<String> fetchNodeIds() {
        return ClusterHttpClient.fetchFromCluster("/api/nodes/lifecycle").flatMap(MAPPER::readTree)
                                                 .map(ClusterDestroyCommand::extractNodeIds)
                                                 .or(List.of());
    }

    private static List<String> extractNodeIds(JsonNode root) {
        var result = new ArrayList<String>();
        if ( !root.isArray()) {
        return List.of();}
        for ( var node : root) {
            var nodeId = node.path("nodeId").asText("");
            if ( !nodeId.isEmpty()) {
            result.add(nodeId);}
        }
        return List.copyOf(result);
    }

    private List<NodeResult> drainAllNodes(List<String> nodeIds) {
        var results = new ArrayList<NodeResult>();
        for ( var nodeId : nodeIds) {
            System.out.printf("Draining node %s...%n", nodeId);
            var result = drainSingleNode(nodeId);
            results.add(result);
        }
        return List.copyOf(results);
    }

    private NodeResult drainSingleNode(String nodeId) {
        var drainResult = ClusterHttpClient.postToCluster("/api/node/drain/" + nodeId, "{}");
        if ( drainResult.isFailure()) {
            System.err.printf("  Failed to drain %s: %s%n", nodeId, drainResult.fold(Cause::message, v -> v));
            return new NodeResult(nodeId, false);
        }
        var success = waitForDecommissioned(nodeId);
        if ( success) {
        System.out.printf("  Node %s decommissioned.%n", nodeId);} else
        {
        System.err.printf("  Node %s did not decommission in time.%n", nodeId);}
        return new NodeResult(nodeId, success);
    }

    private static boolean waitForDecommissioned(String nodeId) {
        var deadline = System.currentTimeMillis() + (long) DRAIN_TIMEOUT_SECONDS * 1000;
        while ( System.currentTimeMillis() < deadline) {
            var state = ClusterHttpClient.fetchFromCluster("/api/node/lifecycle/" + nodeId).flatMap(MAPPER::readTree)
                                                          .map(node -> node.path("state").asText("UNKNOWN"))
                                                          .or("UNKNOWN");
            if ( "DECOMMISSIONED".equals(state)) {
            return true;}
            sleepQuietly();
        }
        return false;
    }

    private List<NodeResult> shutdownAllNodes(List<String> nodeIds) {
        var results = new ArrayList<NodeResult>();
        for ( var nodeId : nodeIds) {
            System.out.printf("Shutting down node %s...%n", nodeId);
            var result = ClusterHttpClient.postToCluster("/api/node/shutdown/" + nodeId, "{}");
            var success = result.isSuccess();
            if ( !success) {
            System.err.printf("  Failed to shutdown %s.%n", nodeId);}
            results.add(new NodeResult(nodeId, success));
        }
        return List.copyOf(results);
    }

    private static Result<ClusterRegistry> removeRegistryEntry(ClusterRegistry registry, String name) {
        return registry.remove(name).flatMap(updated -> updated.save().map(_ -> updated));
    }

    private static int printSummary(String clusterName,
                                    List<String> nodeIds,
                                    List<NodeResult> drainResults,
                                    List<NodeResult> shutdownResults) {
        System.out.println();
        System.out.printf("Cluster '%s' destruction summary:%n", clusterName);
        System.out.printf("  Nodes processed: %d%n", nodeIds.size());
        System.out.printf("  Drains succeeded: %d/%d%n", countSuccesses(drainResults), drainResults.size());
        System.out.printf("  Shutdowns succeeded: %d/%d%n", countSuccesses(shutdownResults), shutdownResults.size());
        System.out.println("  Registry entry removed.");
        var allSucceeded = countSuccesses(drainResults) == drainResults.size() &&
        countSuccesses(shutdownResults) == shutdownResults.size();
        if ( !allSucceeded) {
            System.err.println("Warning: some operations failed. Check output above.");
            return ExitCode.ERROR;
        }
        System.out.printf("Cluster '%s' destroyed successfully.%n", clusterName);
        return ExitCode.SUCCESS;
    }

    private static long countSuccesses(List<NodeResult> results) {
        return results.stream().filter(NodeResult::success)
                             .count();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(DRAIN_POLL_INTERVAL_MS);
        }

























        catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    record NodeResult(String nodeId, boolean success){}
}
