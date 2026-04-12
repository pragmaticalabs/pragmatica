package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.DiffPlan;

/// Result of an apply operation. S9
public record ApplyResult(DiffPlan executedPlan, int nodesAdded, int nodesRemoved, int nodesModified) {
    public static ApplyResult applyResult(DiffPlan executedPlan, int nodesAdded, int nodesRemoved, int nodesModified) {
        return new ApplyResult(executedPlan, nodesAdded, nodesRemoved, nodesModified);
    }
}
