// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODE_DRAIN;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_SHUTDOWN;


@Command(name = "destroy", description = "Destroy the active cluster (drain + shutdown all nodes)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterDestroyCommand implements Callable<Integer> {
    private static final int DRAIN_POLL_INTERVAL_MS = 2000;
    private static final int DRAIN_TIMEOUT_SECONDS = 120;
    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    static Function<String, org.pragmatica.lang.Option<BootstrapState>> stateLoader = BootstrapStatePersistence::load;

    static Function<BootstrapState, Result<Unit>> resourceCleaner = BootstrapCleanup::cleanup;

    @Option(names = "--yes", description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @Option(names = "--keep-resources", description = "Skip cloud resource termination (registry only)")
    private boolean keepResources;

    @Option(names = "--cluster", description = "Override active cluster — destroy named cluster instead (CLI > active-context)")
    private String clusterNameOverride;

    @Contract
    void setKeepResources(boolean value) {
        this.keepResources = value;
    }

    @Contract
    void setClusterNameOverride(String value) {
        this.clusterNameOverride = value;
    }

    @Override
    public Integer call() {
        if (!isOverrideAcceptable()) {
            System.err.println("Invalid --cluster value: '" + clusterNameOverride
                              + "': must match ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

            return ExitCode.USAGE;
        }

        return ClusterRegistry.load()
                              .flatMap(this::executeDestroy)
                              .fold(ClusterDestroyCommand::onFailure, v -> v);
    }

    private boolean isOverrideAcceptable() {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {
            return true;
        }

        return CLUSTER_NAME_PATTERN.matcher(clusterNameOverride).matches();
    }

    private Result<Integer> executeDestroy(ClusterRegistry registry) {
        return resolveTarget(registry).flatMap(entry -> destroyCluster(registry, entry));
    }

    private Result<ClusterRegistry.ClusterEntry> resolveTarget(ClusterRegistry registry) {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {
            return registry.current()
                           .toResult(ClusterHttpClient.HttpError.NO_ACTIVE_CLUSTER);
        }

        return Result.success(findOrSynthesizeEntry(registry, clusterNameOverride));
    }

    private static ClusterRegistry.ClusterEntry findOrSynthesizeEntry(ClusterRegistry registry, String name) {
        return registry.entries()
                       .stream()
                       .filter(e -> e.name()
                                     .equals(name))
                       .findFirst()
                       .orElseGet(() -> new ClusterRegistry.ClusterEntry(name,
                                                                         "",
                                                                         org.pragmatica.lang.Option.none()));
    }

    private Result<Integer> destroyCluster(ClusterRegistry registry, ClusterRegistry.ClusterEntry entry) {
        if (!skipConfirmation && !confirmDestruction(entry.name())) {
            System.out.println("Aborted.");

            return Result.success(ExitCode.SUCCESS);
        }

        applyEndpointOverride(entry);

        return performDestruction(registry, entry);
    }

    private static void applyEndpointOverride(ClusterRegistry.ClusterEntry entry) {
        if (entry.endpoint() == null || entry.endpoint().isBlank()) {
            return;
        }

        ClusterHttpClient.setEndpointOverride(entry.endpoint());
    }

    private Result<Integer> performDestruction(ClusterRegistry registry, ClusterRegistry.ClusterEntry entry) {
        var nodeIds = fetchNodeIds();
        var drainResults = drainAllNodes(nodeIds);
        var shutdownResults = shutdownAllNodes(nodeIds);
        var cleanupOk = cleanupCloudResources(entry.name());

        return removeRegistryEntry(registry, entry.name()).map(_ -> printSummary(entry.name(),
                                                                                 nodeIds,
                                                                                 drainResults,
                                                                                 shutdownResults,
                                                                                 cleanupOk));
    }

    boolean cleanupCloudResources(String clusterName) {
        if (keepResources) {
            System.out.println("--keep-resources: skipping cloud resource termination.");

            return true;
        }

        return stateLoader.apply(clusterName)
                          .fold(() -> warnNoState(clusterName),
                                this::runCleanup);
    }

    private static boolean warnNoState(String clusterName) {
        System.out.printf("No bootstrap state for cluster '%s' — skipping resource cleanup.%n", clusterName);

        return true;
    }

    private boolean runCleanup(BootstrapState state) {
        if (state.createdResources().isEmpty()) {
            System.out.println("Bootstrap state has no created resources — nothing to clean up.");

            return true;
        }

        System.out.printf("Cleaning up %d created resources from bootstrap state...%n",
                          state.createdResources().size());

        return resourceCleaner.apply(state)
                              .onFailure(c -> System.err.println("Resource cleanup failed: " + c.message()))
                              .onSuccess(_ -> System.out.println("Resource cleanup complete."))
                              .isSuccess();
    }

    private static boolean confirmDestruction(String clusterName) {
        System.out.printf("This will destroy cluster '%s' and shut down all nodes.%n", clusterName);
        var input = new org.pragmatica.aether.cli.Prompt().prompt("Type the cluster name to confirm", "");

        return clusterName.equals(input);
    }

    private List<String> fetchNodeIds() {
        return ClusterHttpClient.fetch(NODE_LIFECYCLE_LIST)
                                .flatMap(MAPPER::readTree)
                                .map(ClusterDestroyCommand::extractNodeIds)
                                .or(List.of());
    }

    private static List<String> extractNodeIds(JsonNode root) {
        var result = new ArrayList<String>();

        if (!root.isArray()) {
            return List.of();
        }

        for (var node : root) {
            var nodeId = node.path("nodeId").asText("");

            if (!nodeId.isEmpty()) {
                result.add(nodeId);
            }
        }

        return List.copyOf(result);
    }

    private List<NodeResult> drainAllNodes(List<String> nodeIds) {
        var results = new ArrayList<NodeResult>();

        for (var nodeId : nodeIds) {
            System.out.printf("Draining node %s...%n", nodeId);
            var result = drainSingleNode(nodeId);

            results.add(result);
        }

        return List.copyOf(results);
    }

    private NodeResult drainSingleNode(String nodeId) {
        var drainResult = ClusterHttpClient.post(NODE_DRAIN, List.of(nodeId), "{}");

        if (drainResult.isFailure()) {
            System.err.printf("  Failed to drain %s: %s%n", nodeId, drainResult.fold(Cause::message, v -> v));

            return new NodeResult(nodeId, false);
        }

        var success = waitForDecommissioned(nodeId);

        if (success) {
            System.out.printf("  Node %s decommissioned.%n", nodeId);
        } else {
            System.err.printf("  Node %s did not decommission in time.%n", nodeId);
        }

        return new NodeResult(nodeId, success);
    }

    private static boolean waitForDecommissioned(String nodeId) {
        var deadline = System.currentTimeMillis() + (long) DRAIN_TIMEOUT_SECONDS * 1000;

        while (System.currentTimeMillis() < deadline) {
            var state = ClusterHttpClient.fetch(NODE_LIFECYCLE_GET, List.of(nodeId)).flatMap(MAPPER::readTree).map(node -> node.path("state")
                                                                                                                               .asText("UNKNOWN")).or("UNKNOWN");

            if ("DECOMMISSIONED".equals(state)) {
                return true;
            }

            sleepQuietly();
        }

        return false;
    }

    private List<NodeResult> shutdownAllNodes(List<String> nodeIds) {
        var results = new ArrayList<NodeResult>();

        for (var nodeId : nodeIds) {
            System.out.printf("Shutting down node %s...%n", nodeId);
            var result = ClusterHttpClient.post(NODE_SHUTDOWN, List.of(nodeId), "{}");
            var success = result.isSuccess();

            if (!success) {
                System.err.printf("  Failed to shutdown %s.%n", nodeId);
            }

            results.add(new NodeResult(nodeId, success));
        }

        return List.copyOf(results);
    }

    private static Result<ClusterRegistry> removeRegistryEntry(ClusterRegistry registry, String name) {
        if (!registryContains(registry, name)) {
            return Result.success(registry);
        }

        return registry.remove(name)
                       .flatMap(updated -> updated.save()
                                                  .map(_ -> updated));
    }

    private static boolean registryContains(ClusterRegistry registry, String name) {
        return registry.entries()
                       .stream()
                       .anyMatch(e -> e.name()
                                       .equals(name));
    }

    private static int printSummary(String clusterName,
                                    List<String> nodeIds,
                                    List<NodeResult> drainResults,
                                    List<NodeResult> shutdownResults,
                                    boolean cleanupSucceeded) {
        System.out.println();
        System.out.printf("Cluster '%s' destruction summary:%n", clusterName);
        System.out.printf("  Nodes processed: %d%n", nodeIds.size());
        System.out.printf("  Drains succeeded: %d/%d%n", countSuccesses(drainResults), drainResults.size());
        System.out.printf("  Shutdowns succeeded: %d/%d%n", countSuccesses(shutdownResults), shutdownResults.size());
        System.out.printf("  Cloud resource cleanup: %s%n",
                          cleanupSucceeded
                          ? "ok"
                          : "failed");
        System.out.println("  Registry entry removed.");
        var drainShutdownOk = countSuccesses(drainResults) == drainResults.size() && countSuccesses(shutdownResults) == shutdownResults.size();

        if (!cleanupSucceeded) {
            System.err.println("Warning: cloud resource cleanup failed; orphan resources may remain. "
                              + "Run 'tools/cloud-reaper.sh --cluster " + clusterName
                              + " --destroy' to clean up.");

            return ExitCode.CLEANUP_FAILED;
        }

        if (!drainShutdownOk) {
            System.err.println("Warning: some drain/shutdown operations failed. Check output above.");

            return ExitCode.ERROR;
        }

        System.out.printf("Cluster '%s' destroyed successfully.%n", clusterName);

        return ExitCode.SUCCESS;
    }

    private static long countSuccesses(List<NodeResult> results) {
        return results.stream()
                      .filter(NodeResult::success)
                      .count();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(DRAIN_POLL_INTERVAL_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }

    record NodeResult(String nodeId, boolean success) {}
}
