// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.cli.cluster.ApplyResult.applyResult;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
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

    /// Continues a halted apply from its ledger (spec 4.3): the cluster's commit state decides whether `--resume` may run,
    /// every INTENDED row is looked up at its provider (adopted when found, created never twice), DONE actions are skipped,
    /// a partly done action creates only the remainder, and the apply-label audit runs before the ledger is closed.
    public static Result<ApplyResult> resume(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        return resume(desired,
                      currentStored,
                      productionComputes(desired.cluster().name()),
                      WaveExecutor::execute,
                      ApplyOrchestrator::clusterView);
    }

    static Result<ApplyResult> resume(ClusterBootstrapConfig desired,
                                      ClusterBootstrapConfig currentStored,
                                      Function<String, Result<ComputeProvider>> computeFor,
                                      Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> executor,
                                      Supplier<Option<ApplyCommitRecovery.ClusterConfigView>> view) {
        var clusterName = desired.cluster().name();

        return loadRequiredState(clusterName).flatMap(state -> validateResumeState(state, desired))
                                .flatMap(state -> gate(state, ApplyCommitRecovery.Verb.RESUME, view))
                                .flatMap(state -> continueApply(state, desired, currentStored, computeFor, executor));
    }

    private static Result<ApplyResult> continueApply(ApplyState state,
                                                     ClusterBootstrapConfig desired,
                                                     ClusterBootstrapConfig currentStored,
                                                     Function<String, Result<ComputeProvider>> computeFor,
                                                     Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> executor) {
        var clusterName = desired.cluster().name();
        var recorder = ApplyRecorder.applyRecorder(state);

        if (state.phase() == ApplyPhase.COMMITTED) {
            return ApplyState.delete(clusterName).map(_ -> applyResult(DiffPlan.diffPlan(List.of(),
                                                                                         List.of(),
                                                                                         List.of(),
                                                                                         List.of()),
                                                                       0,
                                                                       0,
                                                                       0));
        }

        return ApplyReconciler.reconcileIntents(recorder, computeFor)
                              .flatMap(_ -> computeValidPlan(desired, currentStored))
                              .flatMap(plan -> executor.apply(plan, currentStored, desired, recorder))
                              .flatMap(result -> ApplyReconciler.audit(recorder, clusterName, computeFor).map(audit -> reported(audit,
                                                                                                                                result)))
                              .onSuccess(_ -> clearFinishedState(clusterName));
    }

    private static ApplyResult reported(ApplyReconciler.AuditReport audit, ApplyResult result) {
        reportAudit(audit);

        return result;
    }

    @Contract
    private static void reportAudit(ApplyReconciler.AuditReport audit) {
        audit.adopted()
             .forEach(item -> System.out.println("Adopted a VM found under the apply label that the ledger did not list: " + item));
        audit.gone().forEach(item -> System.out.println("Recorded as gone (no longer at the provider): " + item));
        audit.relabelled()
             .forEach(item -> System.out.println("WARNING: exists at the provider but no longer carries the apply label (relabelled or foreign), left in the ledger: " + item));
    }

    /// `--rollback`: terminates what the ledger recorded (spec 4.5). The ledger is kept, and the result is a failure, when
    /// any VM is still standing.
    public static Result<ApplyRollback.Report> rollback(ClusterName cluster) {
        return rollback(cluster,
                        productionComputes(cluster),
                        ApplyDrain::drainThroughTheCluster,
                        ApplyOrchestrator::clusterView,
                        System.out::println);
    }

    static Result<ApplyRollback.Report> rollback(ClusterName cluster,
                                                 Function<String, Result<ComputeProvider>> computeFor,
                                                 ApplyRollback.Drain drain,
                                                 Supplier<Option<ApplyCommitRecovery.ClusterConfigView>> view,
                                                 Consumer<String> out) {
        return ApplyState.read(cluster)
                         .mapError(cause -> unreadableState(cluster, cause))
                         .flatMap(state -> state.toResult(new ApplyAborted("No apply state found for cluster '" + cluster
                                                                          + "'. Nothing to roll back.")))
                         .flatMap(state -> gate(state, ApplyCommitRecovery.Verb.ROLLBACK, view))
                         .flatMap(state -> ApplyRollback.orIncomplete(ApplyRollback.rollback(ApplyRecorder.applyRecorder(state),
                                                                                             cluster,
                                                                                             computeFor,
                                                                                             drain,
                                                                                             out)))
                         .flatMap(report -> ApplyState.delete(cluster).map(_ -> report));
    }

    /// Which verb the ledger allows (4.6). A phase the decision moves the ledger to (COMMITTED, ABORTED) is written BEFORE
    /// anything runs or is refused, so the next call starts from the proven state.
    private static Result<ApplyState> gate(ApplyState state,
                                           ApplyCommitRecovery.Verb verb,
                                           Supplier<Option<ApplyCommitRecovery.ClusterConfigView>> view) {
        var decision = ApplyCommitRecovery.decide(state,
                                                  state.phase() == ApplyPhase.COMMITTING
                                                  ? view.get()
                                                  : Option.none());
        var moved = decision.phase() == state.phase()
                    ? success(state)
                    : ApplyState.save(state.withPhase(decision.phase())).map(_ -> state.withPhase(decision.phase()));

        return moved.flatMap(current -> decision.allows(verb)
                                        ? success(current)
                                        : new ApplyAborted("Refused: " + decision.explanation()).result());
    }

    private static Function<String, Result<ComputeProvider>> productionComputes(ClusterName cluster) {
        return provider -> "docker".equals(provider)
                           ? ProviderResolver.resolveDockerComputeWithoutBackup(cluster)
                           : ProviderResolver.resolveCloudComputeForCleanup(provider);
    }

    /// What the cluster holds, or empty when it cannot be read.
    static Option<ApplyCommitRecovery.ClusterConfigView> clusterView() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET)
                                .flatMap(JsonMapper.defaultJsonMapper()::readTree)
                                .flatMap(node -> ClusterBootstrapConfigParser.parse(node.path("tomlContent").asText("")).map(config -> new ApplyCommitRecovery.ClusterConfigView(ClusterBootstrapOrchestrator.computeConfigHash(config),
                                                                                                                                                                                 node.path("configVersion")
                                                                                                                                                                                     .asLong(0))))
                                .option();
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
        return new ApplyAborted("The apply state for cluster '" + clusterName + "' is unreadable: " + cause.message());
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
                                                 Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> executor) {
        var clusterName = desired.cluster().name();

        return ApplyState.read(clusterName)
                         .mapError(cause -> unreadableState(clusterName, cause))
                         .flatMap(_ -> persistInitialAndExecute(plan, stored, desired, executor));
    }

    /// The ledger is written BEFORE the executor runs and a failed write stops the apply: VMs created with no
    /// ledger behind them cannot be resumed, rolled back or even named. A failed executor leaves the state in
    /// place (phase RUNNING) for `--resume`/`--rollback`.
    private static Result<ApplyResult> persistInitialAndExecute(DiffPlan plan,
                                                                ClusterBootstrapConfig stored,
                                                                ClusterBootstrapConfig desired,
                                                                Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> executor) {
        var clusterName = desired.cluster().name();
        var started = startedState(plan, desired);
        // Plan time: a cluster name whose minted node ids cannot be provider labels refuses the whole apply before the ledger exists.
        return ApplyLabels.requireMintable(clusterName,
                                           started.applyId())
                          .flatMap(_ -> ApplyState.save(started))
                          .flatMap(_ -> executor.apply(plan,
                                                       stored,
                                                       desired,
                                                       ApplyRecorder.applyRecorder(started)))
                          .onSuccess(_ -> clearFinishedState(clusterName));
    }

    private static ApplyState startedState(DiffPlan plan, ClusterBootstrapConfig desired) {
        return ApplyState.startedApply(desired.cluster().name(),
                                       ClusterBootstrapOrchestrator.computeConfigHash(desired),
                                       0L,
                                       Instant.now().toString(),
                                       ApplyAction.fromPlan(plan));
    }

    // FER: the apply already succeeded, so a state file that cannot be removed is reported, not failed. Guarantee
    // earned: the exit status tells the truth about the VMs. Mechanism: the leftover file is readable, and the
    // next apply replaces it (executeAndPersist refuses only an UNREADABLE one).
    @Contract
    private static void clearFinishedState(ClusterName clusterName) {
        ApplyState.delete(clusterName).onFailure(cause -> System.err.println("Warning: the apply succeeded but its state file could not be removed: " + cause.message()));
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
