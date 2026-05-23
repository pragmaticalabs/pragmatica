// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.peer;
import static org.pragmatica.aether.deployment.reconciler.rules.SnapshotBuilder.snapshot;


/// Unit tests for the `GenerationLifecycleGap` reconciliation rule. Validates the
/// "Rabia generation member without NodeLifecycleKey entry" trigger and skip cases.
class GenerationLifecycleGapTest {
    @Test
    void evaluate_emitsRecordJoining_whenRabiaMemberHasNoLifecycleEntry() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(60_000L)
                             .generationMember(node)
                             .build();
        var actions = GenerationLifecycleGap.generationLifecycleGap().evaluate(snap).unwrap();

        assertEquals(1, actions.size());
        var action = actions.get(0);
        assertEquals(node, action.peer());
        assertTrue(action.command() instanceof RecordJoining);
    }

    @Test
    void evaluate_skips_whenLifecycleEntryAlreadyPresent() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(60_000L)
                             .generationMember(node)
                             .lifecycle(node, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, 0L))
                             .build();
        var actions = GenerationLifecycleGap.generationLifecycleGap().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenPeerInActiveSyncHolds() {
        var node = peer("node-2");
        var snap = snapshot().nowMs(60_000L)
                             .generationMember(node)
                             .syncHold(node)
                             .build();
        var actions = GenerationLifecycleGap.generationLifecycleGap().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }

    @Test
    void evaluate_skips_whenGenerationMembersEmpty() {
        var snap = snapshot().nowMs(60_000L).build();
        var actions = GenerationLifecycleGap.generationLifecycleGap().evaluate(snap).unwrap();
        assertTrue(actions.isEmpty());
    }
}
