package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.ApplyResult.applyResult;
import static org.pragmatica.lang.Result.success;

/// Desired-state reconciliation orchestrator. S9
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class ApplyOrchestrator {
    private ApplyOrchestrator() {}

    /// Apply desired config to running cluster.
    /// Returns the apply result with node counts, or an error.
    public static Result<ApplyResult> apply(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        return computeValidPlan(desired, currentStored)
            .flatMap(ApplyOrchestrator::executeOrEmpty);
    }

    /// Dry-run: compute and format the plan without executing.
    public static Result<String> dryRun(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        return computeValidPlan(desired, currentStored)
            .map(plan -> ClusterBootstrapConfigDiff.formatPlan(plan, currentStored, desired));
    }

    private static Result<DiffPlan> computeValidPlan(ClusterBootstrapConfig desired, ClusterBootstrapConfig currentStored) {
        return rejectImmutableChanges(ClusterBootstrapConfigDiff.diff(currentStored, desired));
    }

    private static Result<DiffPlan> rejectImmutableChanges(DiffPlan plan) {
        return plan.hasImmutableChanges()
               ? new ClusterConfigError.ImmutableFieldChange(plan.immutable().getFirst().description()).result()
               : success(plan);
    }

    private static Result<ApplyResult> executeOrEmpty(DiffPlan plan) {
        return plan.isEmpty()
               ? success(applyResult(plan, 0, 0, 0))
               : WaveExecutor.execute(plan);
    }
}
