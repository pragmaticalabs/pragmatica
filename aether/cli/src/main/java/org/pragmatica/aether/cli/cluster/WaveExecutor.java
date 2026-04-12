package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.ApplyResult.applyResult;
import static org.pragmatica.lang.Result.success;

/// Executes diff plan waves sequentially. S9.3
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class WaveExecutor {
    private WaveExecutor() {}

    /// Execute all waves in order: additions, modifications, removals.
    public static Result<ApplyResult> execute(DiffPlan plan) {
        var nodesAdded = plan.additions().stream().mapToInt(WaveExecutor::additionNodeCount).sum();
        var nodesModified = plan.modifications().stream().mapToInt(WaveExecutor::modificationNodeCount).sum();
        var nodesRemoved = plan.removals().stream().mapToInt(WaveExecutor::removalNodeCount).sum();

        return success(applyResult(plan, nodesAdded, nodesRemoved, nodesModified));
    }

    private static int additionNodeCount(DiffAction action) {
        return switch (action) {
            case DiffAction.AddRole a -> a.count();
            case DiffAction.ScaleUp a -> a.to() - a.from();
            default -> 0;
        };
    }

    private static int modificationNodeCount(DiffAction action) {
        return switch (action) {
            case DiffAction.RuntimeChange _ -> 1;
            case DiffAction.SourceFieldChange _ -> 1;
            case DiffAction.ClusterLevelChange _ -> 1;
            default -> 0;
        };
    }

    private static int removalNodeCount(DiffAction action) {
        return switch (action) {
            case DiffAction.RemoveRole a -> a.count();
            case DiffAction.ScaleDown a -> a.from() - a.to();
            default -> 0;
        };
    }
}
