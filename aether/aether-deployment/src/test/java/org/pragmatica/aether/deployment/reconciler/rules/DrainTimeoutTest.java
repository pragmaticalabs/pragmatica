// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.peer;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.snapshot;


/// Unit tests for the `DrainTimeout` reconciliation rule. Validates trigger conditions
/// (DRAINING + DRAIN_DEADLINE × 1.5 elapsed) and skip cases.
class DrainTimeoutTest {
    private static final long DRAIN_DEADLINE_MS = 60_000L;
    private static final long BUDGET_MS = (long) (DRAIN_DEADLINE_MS * DrainTimeout.BUDGET_MULTIPLIER);

    @Test
    void evaluate_emitsForceDecommissionWithDrainFailed_whenDrainingPastBudget() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, drainingValue(0L))
                             .build();
        var actions = DrainTimeout.drainTimeout().evaluate(snap).unwrap();

        assertEquals(1, actions.size());
        var action = actions.get(0);
        assertEquals(node, action.peer());
        assertTrue(action.command() instanceof ForceDecommission);
        assertEquals(StopReason.DRAIN_FAILED, ((ForceDecommission) action.command()).reason());
    }

    @Test
    void evaluate_skips_whenBudgetNotMet() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS - 5_000L)
                             .lifecycle(node, drainingValue(0L))
                             .build();
        var actions = DrainTimeout.drainTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenLifecycleNotDraining() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .build();
        var actions = DrainTimeout.drainTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenPeerInActiveSyncHolds() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, drainingValue(0L))
                             .syncHold(node)
                             .build();
        var actions = DrainTimeout.drainTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    private static NodeLifecycleValue drainingValue(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, updatedAt);
    }

    private static NodeLifecycleValue onDutyValue(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt);
    }
}
