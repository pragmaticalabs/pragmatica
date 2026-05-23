// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ReconcilerRulesConfig;
import org.pragmatica.aether.config.RuleSpec;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.swim.SwimHealth;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.peer;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.snapshot;


/// Unit tests for the `JoiningTimeout` reconciliation rule. Validates trigger conditions
/// (JOINING + SWIM Faulty/absent + budget exceeded) and the skip cases (activeSyncHolds,
/// budget unmet, SWIM healthy, lifecycle state not JOINING).
class JoiningTimeoutTest {
    private static final long JOIN_DEADLINE_MS = 60_000L;
    private static final long BUDGET_MS = (long) (JOIN_DEADLINE_MS * JoiningTimeout.BUDGET_MULTIPLIER);

    @Test
    void evaluate_emitsForceDecommission_whenJoiningAndSwimFaultyAndBudgetExceeded() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();

        assertEquals(1, actions.size());
        var action = actions.get(0);
        assertEquals(node, action.peer());
        assertTrue(action.command() instanceof ForceDecommission);
        assertEquals(StopReason.FORCED, ((ForceDecommission) action.command()).reason());
    }

    @Test
    void evaluate_emitsForceDecommission_whenJoiningAndSwimAbsentAndBudgetExceeded() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();

        assertEquals(1, actions.size());
    }

    @Test
    void evaluate_skips_whenBudgetNotYetExceeded() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS - 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenSwimHealthy() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .swim(node, SwimHealth.HEALTHY, 0L)
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenLifecycleNotJoining() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, onDutyValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenPeerInActiveSyncHolds() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .syncHold(node)
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenRuleDisabledInConfig() {
        var node = peer("node-2");
        var ruleSpec = RuleSpec.disabled();
        var snap = snapshot().nowMs(BUDGET_MS + 1_000L)
                             .lifecycle(node, joiningValue(0L))
                             .swim(node, SwimHealth.FAULTY, 0L)
                             .rulesConfig(new ReconcilerRulesConfig(ruleSpec,
                                                                     RuleSpec.dryRun(),
                                                                     RuleSpec.dryRun(),
                                                                     RuleSpec.dryRun(),
                                                                     RuleSpec.dryRun(),
                                                                     RuleSpec.dryRun(),
                                                                     RuleSpec.dryRun()))
                             .build();
        var actions = JoiningTimeout.joiningTimeout().evaluate(snap).unwrap();
        assertEquals(List.of(), actions);
    }

    private static NodeLifecycleValue joiningValue(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, updatedAt);
    }

    private static NodeLifecycleValue onDutyValue(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt);
    }
}
