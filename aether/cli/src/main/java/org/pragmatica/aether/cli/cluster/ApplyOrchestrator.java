// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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

        return loadRequiredState(clusterName).flatMap(state -> validateResumeState(state, desired))
                                .flatMap(_ -> computeValidPlan(desired, currentStored))
                                .flatMap(plan -> executeWithState(plan, currentStored, desired));
    }

    /// Absent state is "nothing to resume"; a state that is present but refused (see
    /// [ApplyState#read]) fails naming the refusal, so the operator is told why rather than that
    /// there was nothing.
    private static Result<ApplyState> loadRequiredState(ClusterName clusterName) {
        return ApplyState.read(clusterName)
                         .mapError(cause -> unreadableState(clusterName, cause))
                         .flatMap(state -> state.toResult(new ApplyAborted("No apply state found for cluster '" + clusterName
                                                                          + "'. Nothing to resume.")));
    }

    private static Cause unreadableState(ClusterName clusterName, Cause cause) {
        return new ApplyAborted("The apply state for cluster '" + clusterName
                               + "' is unreadable and was left untouched: " + cause.message());
    }

    private static Result<DiffPlan> computeValidPlan(ClusterBootstrapConfig desired,
                                                     ClusterBootstrapConfig currentStored) {
        return rejectImmutableChanges(ClusterBootstrapConfigDiff.diff(currentStored, desired));
    }

    private static Result<DiffPlan> rejectImmutableChanges(DiffPlan plan) {
        return plan.hasImmutableChanges()
               ? new ClusterConfigError.ImmutableFieldChange(immutableFieldName(plan.immutable().getFirst())).result()
               : success(plan);
    }

    // #578 review Issue 2: field() names the config key, description() is a full sentence —
    // description() here made the message read "Field 'cluster.name is immutable and cannot be
    // changed' is immutable after bootstrap." See ClusterConfigRoutes.buildImmutableChangeError
    // for the matching fix on the route side.
    private static String immutableFieldName(DiffAction action) {
        return action instanceof DiffAction.ImmutableFieldChange(var field)
               ? field
               : action.description();
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
        return executeAndPersist(plan, stored, desired, WaveExecutor::execute);
    }

    /// A fresh apply replaces a halted apply's state, but only one it could READ: an unreadable file may
    /// hold a newer CLI's handles on resources, and overwriting it would erase them, so the apply is
    /// refused before the file is touched or anything is executed.
    static Result<ApplyResult> executeAndPersist(DiffPlan plan,
                                                 ClusterBootstrapConfig stored,
                                                 ClusterBootstrapConfig desired,
                                                 Fn3<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig> executor) {
        var clusterName = desired.cluster().name();

        return ApplyState.read(clusterName)
                         .mapError(cause -> unreadableState(clusterName, cause))
                         .flatMap(_ -> persistInitialAndExecute(plan, stored, desired, executor));
    }

    private static Result<ApplyResult> persistInitialAndExecute(DiffPlan plan,
                                                                ClusterBootstrapConfig stored,
                                                                ClusterBootstrapConfig desired,
                                                                Fn3<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig> executor) {
        var clusterName = desired.cluster().name();
        var configHash = ClusterBootstrapOrchestrator.computeConfigHash(desired);
        var state = ApplyState.initialState(clusterName,
                                            configHash,
                                            Instant.now().toString());

        ApplyState.save(state);

        return executor.apply(plan, stored, desired)
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

    public record ApplyAborted(String detail) implements Cause {
        @Override
        public String message() {
            return detail;
        }
    }
}
