// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.List;

import static org.pragmatica.aether.cli.cluster.ApplyResult.applyResult;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-EX-01"})
public final class ApplyOrchestrator {
    private ApplyOrchestrator() {}

    public static Result<ApplyResult> apply(ClusterBootstrapConfig desired,
                                            ClusterBootstrapConfig currentStored,
                                            boolean skipConfirmation) {
        return checkClusterHealth(currentStored).flatMap(_ -> computeValidPlan(desired, currentStored))
                                 .flatMap(plan -> presentPlanAndConfirm(plan, currentStored, desired, skipConfirmation))
                                 .flatMap(plan -> executeWithState(plan, currentStored, desired));
    }

    public static Result<ApplyResult> apply(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        return apply(desired, currentStored, true);
    }

    public static Result<String> dryRun(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        return computeValidPlan(desired, currentStored).map(plan -> ClusterBootstrapConfigDiff.formatPlan(plan,
                                                                                                          currentStored,
                                                                                                          desired));
    }

    public static Result<ApplyResult> resume(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        var clusterName = desired.cluster().name();

        return ApplyState.load(clusterName)
                         .toResult(new ApplyAborted("No apply state found for cluster '" + clusterName
                                                   + "'. Nothing to resume."))
                         .flatMap(state -> validateResumeState(state, desired))
                         .flatMap(_ -> computeValidPlan(desired, currentStored))
                         .flatMap(plan -> executeWithState(plan, currentStored, desired));
    }

    public static Result<ApplyResult> rollback(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        var clusterName = desired.cluster().name();

        return ApplyState.load(clusterName)
                         .toResult(new ApplyAborted("No apply state found for cluster '" + clusterName
                                                   + "'. Nothing to rollback."))
                         .flatMap(state -> executeRollback(state, currentStored, desired));
    }

    private static Result<DiffPlan> computeValidPlan(ClusterBootstrapConfig desired,
                                                     ClusterBootstrapConfig currentStored) {
        return rejectImmutableChanges(ClusterBootstrapConfigDiff.diff(currentStored, desired));
    }

    private static Result<DiffPlan> rejectImmutableChanges(DiffPlan plan) {
        return plan.hasImmutableChanges()
               ? new ClusterConfigError.ImmutableFieldChange(plan.immutable().getFirst().description()).result()
               : success(plan);
    }

    private static Result<Unit> checkClusterHealth(ClusterBootstrapConfig stored) {
        var addresses = collectKnownAddresses(stored);

        if (addresses.isEmpty()) {
            return Result.unitResult();
        }

        var address = addresses.getFirst();
        var managementPort = stored.operations().ports().management();

        ClusterHttpClient.checkClusterHealth(address, managementPort).onFailure(cause -> warnHealthCheckFailed(cause));

        return Result.unitResult();
    }

    private static List<String> collectKnownAddresses(ClusterBootstrapConfig stored) {
        return stored.sources()
                     .values()
                     .stream()
                     .flatMap(s -> s.roles()
                                    .values()
                                    .stream())
                     .flatMap(r -> r.hosts()
                                    .stream()
                                    .flatMap(List::stream))
                     .toList();
    }

    @Contract
    private static void warnHealthCheckFailed(Cause cause) {
        System.err.println("Warning: could not verify cluster health: " + cause.message()
                          + ". Run 'aether cluster status' for details.");
    }

    private static Result<DiffPlan> presentPlanAndConfirm(DiffPlan plan,
                                                          ClusterBootstrapConfig stored,
                                                          ClusterBootstrapConfig desired,
                                                          boolean skipConfirmation) {
        if (plan.isEmpty()) {
            return success(plan);
        }

        var planText = ClusterBootstrapConfigDiff.formatPlan(plan, stored, desired);

        printPlan(planText);
        if (skipConfirmation) {
            return success(plan);
        }

        return readConfirmation()
               ? success(plan)
               : new ApplyAborted("Apply cancelled by user.").result();
    }

    @Contract
    private static void printPlan(String planText) {
        System.out.println();
        System.out.println("Apply plan:");
        System.out.println(planText);
        System.out.println();
    }

    private static boolean readConfirmation() {
        return new org.pragmatica.aether.cli.Prompt().confirm("Apply these changes?", false);
    }

    private static Result<ApplyResult> executeWithState(DiffPlan plan,
                                                        ClusterBootstrapConfig stored,
                                                        ClusterBootstrapConfig desired) {
        return plan.isEmpty()
               ? success(applyResult(plan, 0, 0, 0))
               : executeAndPersist(plan, stored, desired);
    }

    private static Result<ApplyResult> executeAndPersist(DiffPlan plan,
                                                         ClusterBootstrapConfig stored,
                                                         ClusterBootstrapConfig desired) {
        var clusterName = desired.cluster().name();
        var configHash = ClusterBootstrapOrchestrator.computeConfigHash(desired);
        var state = ApplyState.initialState(clusterName,
                                            configHash,
                                            Instant.now().toString());

        ApplyState.save(state);

        return WaveExecutor.execute(plan, stored, desired)
                           .onSuccess(_ -> ApplyState.delete(clusterName))
                           .onFailure(_ -> markStateFailed(state));
    }

    @Contract
    private static void markStateFailed(ApplyState state) {
        var failed = state.withWaveStatus(state.currentWaveIndex(), ApplyState.WaveStatus.FAILED);

        ApplyState.save(failed);
    }

    private static Result<ApplyState> validateResumeState(ApplyState state, ClusterBootstrapConfig desired) {
        var currentHash = ClusterBootstrapOrchestrator.computeConfigHash(desired);

        if (!currentHash.equals(state.configHash())) {
            return new ApplyAborted("Config has changed since last apply (hash mismatch). Use fresh apply or restore the original config.").result();
        }

        return success(state);
    }

    private static Result<ApplyResult> executeRollback(ApplyState state,
                                                       ClusterBootstrapConfig stored,
                                                       ClusterBootstrapConfig desired) {
        logRollback(state);
        var clusterName = state.clusterName();
        var destroyedCount = state.destroyedNodeIds().size();
        var createdCount = state.createdResources().size();

        if (destroyedCount == 0 && createdCount == 0) {
            ApplyState.delete(clusterName);

            return success(applyResult(ClusterBootstrapConfigDiff.diff(stored, desired), 0, 0, 0));
        }

        destroyCreatedResources(state);
        ApplyState.delete(clusterName);

        return success(applyResult(ClusterBootstrapConfigDiff.diff(stored, desired), 0, createdCount, 0));
    }

    @Contract
    private static void logRollback(ApplyState state) {
        System.out.println("Rolling back apply for cluster '" + state.clusterName() + "'");
        System.out.println("  Created resources to destroy: " + state.createdResources().size());
        System.out.println("  Destroyed nodes to note: " + state.destroyedNodeIds().size());
    }

    @Contract
    private static void destroyCreatedResources(ApplyState state) {
        for (var resource : state.createdResources()) {
            System.out.println("  Rollback: destroying " + resource.description());
        }
    }

    public record ApplyAborted(String detail) implements Cause {
        @Override
        public String message() {
            return detail;
        }
    }
}
