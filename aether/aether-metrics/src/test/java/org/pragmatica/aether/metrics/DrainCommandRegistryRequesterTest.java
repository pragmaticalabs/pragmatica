// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.DrainCommandRegistry.Requester;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2315 F3: an entry is identified by the life of the target and by who asked; a clear by [DrainId] refuses every other id.
class DrainCommandRegistryRequesterTest {
    private static final NodeId NODE = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();

    @Test
    void twoRequestersOfOneNode_holdTwoEntriesUnderTwoIds() {
        var operator = registry.requestDrain(NODE, Requester.OPERATOR).unwrap();
        var manager = registry.requestDrain(NODE, Requester.MANAGER).unwrap();

        assertThat(manager).isNotEqualTo(operator);
        assertThat(registry.drainTargets()).as("one target, not one per entry").containsExactly(NODE);
    }

    @Test
    void sameRequesterAskingAgain_getsItsHeldId_andTheDefaultRequesterIsTheOperator() {
        var manager = registry.requestDrain(NODE, Requester.MANAGER).unwrap();

        assertThat(registry.requestDrain(NODE, Requester.MANAGER).unwrap()).isEqualTo(manager);
        assertThat(registry.requestDrain(NODE).unwrap()).as("the one-argument request is the operator's")
                  .isNotEqualTo(manager);
        assertThat(registry.requestDrain(NODE).unwrap()).isEqualTo(registry.requestDrain(NODE, Requester.OPERATOR)
                                                                           .unwrap());
    }

    @Test
    void clearByOneRequestersId_leavesTheOthersEntry() {
        var operator = registry.requestDrain(NODE, Requester.OPERATOR).unwrap();
        var manager = registry.requestDrain(NODE, Requester.MANAGER).unwrap();

        registry.clearDrain(operator);
        assertThat(registry.isDrainRequested(NODE)).isTrue();
        assertThat(registry.pinOf(NODE).unwrap().id()).isEqualTo(manager);
        registry.clearDrain(manager);
        assertThat(registry.isDrainRequested(NODE)).isFalse();
    }

    @Test
    void clearByAnIdNoEntryHolds_isRefused() {
        registry.requestDrain(NODE, Requester.OPERATOR);
        registry.requestDrain(NODE, Requester.MANAGER);
        registry.requestDrain(OTHER);
        registry.clearDrain(DrainId.drainId(NODE, 9_999L));
        registry.clearDrain(DrainId.drainId(NODE,
                                            registry.pinOf(OTHER).unwrap().id().sequence()));
        assertThat(registry.drainTargets()).containsExactlyInAnyOrder(NODE, OTHER);
        assertThat(registry.pinOf(NODE).isPresent()).isTrue();
    }

    @Test
    void edgeClear_endsEveryRequestersEntryOfTheNode_andOnlyThatNode() {
        registry.requestDrain(NODE, Requester.OPERATOR);
        registry.requestDrain(NODE, Requester.MANAGER);
        registry.requestDrain(OTHER, Requester.MANAGER);
        registry.clearEndedLife(NODE, 0L);
        assertThat(registry.drainTargets()).containsExactly(OTHER);
    }
}
