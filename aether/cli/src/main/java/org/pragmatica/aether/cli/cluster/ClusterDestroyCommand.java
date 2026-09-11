// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODE_DRAIN;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_SHUTDOWN;
import static org.pragmatica.lang.Option.option;


@Command(name = "destroy", description = "Destroy the active cluster (drain + shutdown all nodes)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterDestroyCommand implements Callable<Integer> {
    /// Package-visible so a test asserts the announced ceiling against **the constant that enforces it**
    /// rather than against a restated literal — the same arrangement as
    /// [BootstrapCleanup#FIREWALL_DELETE_ATTEMPTS], and the reason #994's "servers are still detaching" is
    /// the cautionary case: an announcement that can drift from the code is a false diagnostic waiting to
    /// happen.
    static final int DRAIN_POLL_INTERVAL_MS = 2000;
    static final int DRAIN_TIMEOUT_SECONDS = 120;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// #994 verification finding SF-1 — carries `Result<Option<…>>` rather than `Option<…>`, so
    /// **an UNREADABLE ledger is distinguishable from an ABSENT one.** Under the old `Option` seam a torn
    /// `bootstrap-state.json` arrived as empty, which `cleanupCloudResources` read as "no bootstrap state
    /// — skipping resource cleanup", returned `true` for, and then removed the registry entry and exited 0
    /// over servers that were still billing. That is reachable by exactly the failure the incidents ended
    /// in: the operator killing bootstrap mid-write.
    ///
    /// `org.pragmatica.lang.Option` is spelled out because the simple name `Option` in this file is
    /// picocli's `@Option` annotation — the same reason the previous declaration was fully qualified.
    static Function<ClusterName, Result<org.pragmatica.lang.Option<BootstrapState>>> stateLoader = BootstrapStatePersistence::read;

    static Function<BootstrapState, Result<Unit>> resourceCleaner = BootstrapCleanup::cleanup;

    /// #481 — cluster-scoped ssh-key sweep seam, called AFTER the state-based cleanup so recorded keys
    /// already deleted are tolerated. Deletes account keys named `aether-bootstrap-<cluster>-*` (the
    /// delimiter boundary) that `resourceCleaner` misses (reused / unrecorded keys). Injectable like
    /// `resourceCleaner`/`stateLoader` so tests exercise it without a real cloud call.
    static BiFunction<BootstrapState, ClusterName, Result<Unit>> sshKeySweeper = BootstrapCleanup::sweepClusterSshKeys;

    /// RFC-0017 stage 6 / C3 — cluster-scoped VM sweep seam. Reaps the cluster-labelled VMs the
    /// bootstrap state never recorded (stage-5 cluster-provisioned workers, auto-heal
    /// replacements); selector is built from the cluster name, never a bare filter.
    static BiFunction<BootstrapState, ClusterName, Result<Unit>> vmSweeper = BootstrapCleanup::sweepClusterVms;

    /// #521 — registry-removal seam, injectable like `resourceCleaner`/`stateLoader` so a test can assert
    /// that a FAILED cloud cleanup leaves the entry in place (the operator's only remaining handle on VMs
    /// that are still billing) without writing to the real `~/.aether/clusters.toml`.
    static BiFunction<ClusterRegistry, ClusterName, Result<ClusterRegistry>> registryRemover = ClusterDestroyCommand::removeRegistryEntry;

    @Option(names = "--yes", description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @Option(names = "--keep-resources", description = "Skip cloud resource termination (registry only)")
    private boolean keepResources;

    /// Raw picocli-bound text, deliberately NOT a [ClusterName]: picocli assigns it before any Aether
    /// code runs, so the parse happens in [#isOverrideAcceptable] / [#destroyCluster] where a rejection
    /// can be reported as a usage error.
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

        return ClusterName.PATTERN.matcher(clusterNameOverride).matches();
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

    /// The registry entry's name is the LAST place a raw `String` cluster name survives on this path —
    /// `ClusterRegistry` is a persisted TOML index whose section keys are the names, and it stays
    /// `String`-typed. It is parsed HERE, immediately before it becomes a destroy selector, because
    /// everything downstream (`aether-cluster=<name>` VM sweep, `aether-bootstrap-<name>-` key sweep,
    /// the state-file path) is a scoping decision that must not run on an unparseable name.
    private Result<Integer> destroyCluster(ClusterRegistry registry, ClusterRegistry.ClusterEntry entry) {
        return ClusterName.clusterName(entry.name()).flatMap(clusterName -> destroyNamed(registry, entry, clusterName));
    }

    private Result<Integer> destroyNamed(ClusterRegistry registry,
                                         ClusterRegistry.ClusterEntry entry,
                                         ClusterName clusterName) {
        if (!skipConfirmation && !confirmDestruction(clusterName)) {
            System.out.println("Aborted.");

            return Result.success(ExitCode.ERROR);
        }

        applyEndpointOverride(entry);

        return performDestruction(registry, clusterName);
    }

    private static void applyEndpointOverride(ClusterRegistry.ClusterEntry entry) {
        if (entry.endpoint() == null || entry.endpoint().isBlank()) {
            return;
        }

        ClusterHttpClient.setEndpointOverride(entry.endpoint());
    }

    /// #995 — against a healthy 3-node cloud cluster this emitted NOTHING for ~2.5 minutes and deleted
    /// nothing, and the operator killed it with three paid servers still running. Nothing here was
    /// announced: the first statement is a node-list HTTP request that can block for the whole
    /// [ClusterHttpClient#REQUEST_TIMEOUT] (130s by default) and whose failure was discarded by
    /// `.or(List.of())`; with an empty node list the drain and shutdown loops then print nothing either.
    /// So the command's entire observable output could begin more than two minutes in.
    ///
    /// Every phase now announces itself BEFORE it blocks, names the ceiling it may wait for, and reports
    /// its own failure. Silence is what invited the intervention that produced a half-destroyed cluster;
    /// an operator must be able to tell "working" from "wedged" without reading this file.
    ///
    /// #994 verification finding SF-3 — package-visible because **no test reached this method at all.**
    /// The three phase methods were each driven individually and every test entering through `call()`
    /// returned early (invalid `--cluster`, or an aborted confirmation), so deleting
    /// `announceDestroyPlan(clusterName)` from here — #995's entire "say so before the wait begins"
    /// deliverable — left all 724 tests green (measured, probe V3). The PHASE SEQUENCE was unpinned for the
    /// same reason: the order these five run in is the property the announcement describes, and nothing
    /// checked it.
    Result<Integer> performDestruction(ClusterRegistry registry, ClusterName clusterName) {
        announceDestroyPlan(clusterName);
        var nodeIds = fetchNodeIds();
        var drainResults = drainAllNodes(nodeIds);
        var shutdownResults = shutdownAllNodes(nodeIds);
        var cleanupOk = cleanupCloudResources(clusterName);

        return finalizeDestruction(registry, clusterName, cleanupOk, nodeIds, drainResults, shutdownResults);
    }

    /// #995 expectation 3 — "if it can take minutes, say so before the wait begins". The figures are read
    /// from the constants that actually bound the waits, so the estimate cannot drift away from the code.
    @Contract
    private void announceDestroyPlan(ClusterName clusterName) {
        System.out.printf("Destroying cluster '%s' in %d phases. This can take minutes: node enumeration"
                         + " waits up to %ds, each node's drain up to %ds, and cloud resource deletion is"
                         + " paced by the provider.%n",
                          clusterName,
                          DestroyPhase.values().length,
                          requestTimeoutSeconds(),
                          DRAIN_TIMEOUT_SECONDS);
    }

    /// #995 — the destroy pipeline's phases, printed in the same `[Phase n/m: NAME]` shape
    /// [ClusterBootstrapOrchestrator#logPhase] uses for bootstrap, so an operator reading a bootstrap
    /// transcript and a destroy transcript reads one format rather than two.
    enum DestroyPhase {
        ENUMERATE_NODES,
        DRAIN_NODES,
        SHUTDOWN_NODES,
        CLOUD_CLEANUP,
        REGISTRY
    }

    @Contract
    static void logPhase(DestroyPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n",
                          phase.ordinal() + 1,
                          DestroyPhase.values().length,
                          phase.name(),
                          message);
    }

    /// The real ceiling on a single management request, read from [ClusterHttpClient#REQUEST_TIMEOUT]
    /// rather than restated: an announced timeout that does not match the one in force is the same class
    /// of false diagnostic as #994's "servers are still detaching".
    private static long requestTimeoutSeconds() {
        return option(ClusterHttpClient.REQUEST_TIMEOUT.get()).map(Duration::toSeconds)
                     .or(0L);
    }

    /// #521 — registry honesty. The registry entry is the operator's only handle on a cluster whose VMs may
    /// still be billing, so it is removed ONLY once cloud cleanup has actually succeeded; a failed cleanup
    /// keeps it so `aether cluster destroy` can simply be re-run. `--keep-resources` routes `cleanupOk` to
    /// true by design — skipping termination is the explicitly acknowledged path there, and removing the
    /// entry is correct.
    static Result<Integer> finalizeDestruction(ClusterRegistry registry,
                                               ClusterName clusterName,
                                               boolean cleanupOk,
                                               List<String> nodeIds,
                                               List<NodeResult> drainResults,
                                               List<NodeResult> shutdownResults) {
        if (!cleanupOk) {
            logPhase(DestroyPhase.REGISTRY,
                     "Keeping the registry entry — cloud cleanup failed, and the entry is the operator's"
                    + " remaining handle on resources that may still be billing");

            return Result.success(printSummary(clusterName, nodeIds, drainResults, shutdownResults, false, false));
        }

        logPhase(DestroyPhase.REGISTRY, "Removing the registry entry for '" + clusterName + "'");

        return registryRemover.apply(registry, clusterName)
                              .map(_ -> printSummary(clusterName, nodeIds, drainResults, shutdownResults, true, true));
    }

    boolean cleanupCloudResources(ClusterName clusterName) {
        logPhase(DestroyPhase.CLOUD_CLEANUP,
                 "Deleting cloud resources recorded at bootstrap, then sweeping cluster-labelled VMs and"
                + " SSH keys. Each delete is reported as it is issued; a firewall still in use is retried"
                + " up to " + BootstrapCleanup.FIREWALL_DELETE_ATTEMPTS
                + " times, " + (BootstrapCleanup.FIREWALL_DELETE_RETRY_MILLIS / 1000)
                + "s apart");
        if (keepResources) {
            System.out.println("--keep-resources: skipping cloud resource termination.");

            return true;
        }

        return stateLoader.apply(clusterName)
                          .map(state -> state.fold(() -> warnNoState(clusterName), this::runCleanup))
                          .onFailure(cause -> warnUnreadableState(clusterName, cause))
                          .or(false);
    }

    /// #994 verification finding SF-1 — an unreadable ledger is a cleanup FAILURE, not an empty cluster.
    /// Returning `false` is what keeps the registry entry (#521's property: the entry is the operator's
    /// remaining handle on resources that may still be billing) and exits non-zero, so `destroy` can be
    /// re-run once the file is repaired or the reaper has finished the job. The alternative — the previous
    /// behaviour — was "destroyed successfully", exit 0, entry gone, servers running.
    @Contract
    private static void warnUnreadableState(ClusterName clusterName, Cause cause) {
        System.err.printf("  WARN: the bootstrap state file for cluster '%s' exists but cannot be read: %s%n",
                          clusterName,
                          cause.message());
        System.err.printf("  REFUSING to report cleanup as done: an unreadable ledger is NOT an empty one, and every"
                         + " resource it recorded may still be billing. The registry entry is kept so this can be"
                         + " retried. Finish teardown with: tools/cloud-reaper.sh --cluster %s --destroy%n",
                          clusterName);
    }

    private static boolean warnNoState(ClusterName clusterName) {
        System.out.printf("No bootstrap state for cluster '%s' — skipping resource cleanup.%n", clusterName);

        return true;
    }

    private boolean runCleanup(BootstrapState state) {
        var cleanupOk = runResourceCleanup(state);
        // RFC-0017 stage 6 / C3 — VM sweep AFTER the state-based cleanup (cores die first, killing
        // the worker reconciler that would otherwise re-provision what the sweep reaps) and BEFORE
        // the key sweep. Catches cluster-provisioned workers and auto-heal replacements the
        // bootstrap state never recorded.
        var vmSweepOk = runVmSweep(state);
        var sweepOk = runSshKeySweep(state);

        return cleanupOk
               && vmSweepOk
               && sweepOk;
    }

    private boolean runVmSweep(BootstrapState state) {
        return vmSweeper.apply(state,
                               state.clusterName())
                        .onFailure(c -> System.err.println("VM sweep failed: " + c.message()))
                        .onSuccess(_ -> System.out.println("VM sweep complete."))
                        .isSuccess();
    }

    private boolean runResourceCleanup(BootstrapState state) {
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

    private boolean runSshKeySweep(BootstrapState state) {
        return sshKeySweeper.apply(state,
                                   state.clusterName())
                            .onFailure(c -> System.err.println("SSH-key sweep failed: " + c.message()))
                            .onSuccess(_ -> System.out.println("SSH-key sweep complete."))
                            .isSuccess();
    }

    private static boolean confirmDestruction(ClusterName clusterName) {
        System.out.printf("This will destroy cluster '%s' and shut down all nodes.%n", clusterName);
        var input = new org.pragmatica.aether.cli.Prompt().prompt("Type the cluster name to confirm", "");

        return clusterName.value()
                          .equals(input);
    }

    /// #995 — this is the call that produced the observed silence: one management request, up to
    /// [#requestTimeoutSeconds] before it gives up, and its failure was swallowed by `.or(List.of())`
    /// with no message at all. It now says what it is about to wait for and for how long, and reports a
    /// failure instead of continuing as if the cluster had no nodes.
    List<String> fetchNodeIds() {
        logPhase(DestroyPhase.ENUMERATE_NODES,
                 String.format("Listing cluster nodes from %s (one request, timeout %ds)",
                               ClusterHttpClient.resolveEndpoint().or("<no endpoint resolved>"),
                               requestTimeoutSeconds()));

        return ClusterHttpClient.fetch(NODE_LIFECYCLE_LIST)
                                .flatMap(MAPPER::readTree)
                                .map(ClusterDestroyCommand::extractNodeIds)
                                .onFailure(ClusterDestroyCommand::warnNodeEnumerationFailed)
                                .onSuccess(ClusterDestroyCommand::reportNodesFound)
                                .or(List.of());
    }

    @Contract
    private static void reportNodesFound(List<String> nodeIds) {
        System.out.printf("  %d node(s) reported by the cluster.%n", nodeIds.size());
    }

    /// Names the consequence, not just the error: with no node list the drain and shutdown phases have
    /// nothing to act on, so the nodes are destroyed WITHOUT a graceful drain. That is a different
    /// outcome from a successful destroy and the operator has to be told, because cloud cleanup still
    /// proceeds from the bootstrap ledger and the command can still exit 0.
    @Contract
    private static void warnNodeEnumerationFailed(Cause cause) {
        System.err.println("  WARN: could not list cluster nodes: " + cause.message());
        System.err.println("  Proceeding to cloud resource cleanup with an EMPTY node list — drain and"
                          + " shutdown are skipped, so nodes are deleted without a graceful drain."
                          + " Resources recorded at bootstrap are still reaped.");
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

    List<NodeResult> drainAllNodes(List<String> nodeIds) {
        logPhase(DestroyPhase.DRAIN_NODES, drainAnnouncement(nodeIds.size()));
        var results = new ArrayList<NodeResult>();

        for (var nodeId : nodeIds) {
            System.out.printf("Draining node %s (waiting up to %ds for DECOMMISSIONED, polling every %dms)...%n",
                              nodeId,
                              DRAIN_TIMEOUT_SECONDS,
                              DRAIN_POLL_INTERVAL_MS);
            var result = drainSingleNode(nodeId);

            results.add(result);
        }

        return List.copyOf(results);
    }

    private static String drainAnnouncement(int nodeCount) {
        return nodeCount == 0
               ? "Nothing to drain — the node list is empty"
               : String.format("Draining %d node(s), up to %ds each (worst case %ds total)",
                               nodeCount,
                               DRAIN_TIMEOUT_SECONDS,
                               (long) nodeCount * DRAIN_TIMEOUT_SECONDS);
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
            var state = ClusterHttpClient.fetch(NODE_LIFECYCLE_GET,
                                                List.of(nodeId))
                                         .flatMap(MAPPER::readTree)
                                         .map(node -> node.path("state")
                                                          .asText("UNKNOWN"))
                                         .or("UNKNOWN");

            if ("DECOMMISSIONED".equals(state)) {
                return true;
            }

            sleepQuietly();
        }

        return false;
    }

    List<NodeResult> shutdownAllNodes(List<String> nodeIds) {
        logPhase(DestroyPhase.SHUTDOWN_NODES,
                 nodeIds.isEmpty()
                 ? "Nothing to shut down — the node list is empty"
                 : String.format("Shutting down %d node(s)", nodeIds.size()));
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

    private static Result<ClusterRegistry> removeRegistryEntry(ClusterRegistry registry, ClusterName name) {
        if (!registryContains(registry, name.value())) {
            return Result.success(registry);
        }

        return registry.remove(name.value())
                       .flatMap(updated -> updated.save()
                                                  .map(_ -> updated));
    }

    private static boolean registryContains(ClusterRegistry registry, String name) {
        return registry.entries()
                       .stream()
                       .anyMatch(e -> e.name()
                                       .equals(name));
    }

    private static int printSummary(ClusterName clusterName,
                                    List<String> nodeIds,
                                    List<NodeResult> drainResults,
                                    List<NodeResult> shutdownResults,
                                    boolean cleanupSucceeded,
                                    boolean registryEntryRemoved) {
        System.out.println();
        System.out.printf("Cluster '%s' destruction summary:%n", clusterName);
        System.out.printf("  Nodes processed: %d%n", nodeIds.size());
        System.out.printf("  Drains succeeded: %d/%d%n", countSuccesses(drainResults), drainResults.size());
        System.out.printf("  Shutdowns succeeded: %d/%d%n", countSuccesses(shutdownResults), shutdownResults.size());
        System.out.printf("  Cloud resource cleanup: %s%n",
                          cleanupSucceeded
                          ? "ok"
                          : "failed");
        System.out.printf("  Registry entry: %s%n",
                          registryEntryRemoved
                          ? "removed"
                          : "KEPT (cloud cleanup failed — retry 'aether cluster destroy --cluster " + clusterName
                           + " --yes')");
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
