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
import org.pragmatica.swim.SwimHealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.peer;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.snapshot;


/// Unit tests for the `OnDutyFaulty` reconciliation rule. Validates trigger conditions
/// (ON_DUTY + SWIM FAULTY for ≥ SWIM_FAULTY_DECLARATION × 3) and skip cases.
class OnDutyFaultyTest {
    private static final long BUDGET_MS = (long) (OnDutyFaulty.SWIM_FAULTY_DECLARATION_MS
                                                  * OnDutyFaulty.BUDGET_MULTIPLIER);

    @Test
    void evaluate_emitsForceDecommission_whenOnDutyAndSwimFaultyForBudget() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .build();
        var actions = OnDutyFaulty.onDutyFaulty().evaluate(snap).unwrap();

        assertEquals(1, actions.size());
        var action = actions.get(0);
        assertEquals(node, action.peer());
        assertTrue(action.command() instanceof ForceDecommission);
        assertEquals(StopReason.FORCED, ((ForceDecommission) action.command()).reason());
    }

    @Test
    void evaluate_skips_whenSwimFaultyButBudgetNotMet() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS - 5_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .build();
        var actions = OnDutyFaulty.onDutyFaulty().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenSwimSuspectedNotFaulty() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .swim(node, SwimHealth.SUSPECTED, 0L)
                             .build();
        var actions = OnDutyFaulty.onDutyFaulty().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenSwimAbsent() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .build();
        var actions = OnDutyFaulty.onDutyFaulty().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenLifecycleNotOnDuty() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .build();
        var actions = OnDutyFaulty.onDutyFaulty().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenPeerInActiveSyncHolds() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .syncHold(node)
                             .build();
        var actions = OnDutyFaulty.onDutyFaulty().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    private static NodeLifecycleValue onDutyValue(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt);
    }

    private static NodeLifecycleValue joiningValue(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, updatedAt);
    }
}
