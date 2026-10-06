// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 part D: what each replacement phase lets the reaper, the voter selection and worker surplus do.
class NodeReplacementIndexTest {
    private static final NodeId OLD = new NodeId("old");
    private static final NodeId NEW = new NodeId("new");

    private static NodeReplacementIndex pairedIn(NodeReplacementPhase phase) {
        var index = NodeReplacementIndex.nodeReplacementIndex();

        index.put(new NodeReplacementKey(OLD), new NodeReplacementValue(NEW, "core", phase, 0L));
        return index;
    }

    @Test
    void emptyIndex_answersNothing() {
        var index = NodeReplacementIndex.nodeReplacementIndex();

        assertThat(index.retirementProtected()).isEmpty();
        assertThat(index.voterSwaps()).isEmpty();
        assertThat(index.surgeReplacements()).isEmpty();
        assertThat(index.retiringOriginals()).isEmpty();
    }

    @Test
    void beforeTheSwap_bothAreProtected_andNoSwapIsAuthorized() {
        for (var phase : Set.of(NodeReplacementPhase.PROVISIONING, NodeReplacementPhase.JOINING)) {
            var index = pairedIn(phase);

            assertThat(index.retirementProtected()).as(phase.name()).containsExactlyInAnyOrder(OLD, NEW);
            assertThat(index.voterSwaps()).as(phase.name()).isEmpty();
            assertThat(index.surgeReplacements()).as(phase.name()).containsExactly(NEW);
        }
    }

    @Test
    void fromSwappingOn_theSwapIsAuthorized_andTheOriginalStaysProtectedUntilRetiring() {
        for (var phase : Set.of(NodeReplacementPhase.SWAPPING, NodeReplacementPhase.CANARY, NodeReplacementPhase.DRAINING_OLD)) {
            var index = pairedIn(phase);

            assertThat(index.voterSwaps()).as(phase.name()).isEqualTo(Map.of(OLD, NEW));
            assertThat(index.retirementProtected()).as(phase.name()).containsExactlyInAnyOrder(OLD, NEW);
            assertThat(index.retiringOriginals()).as(phase.name()).isEmpty();
        }
    }

    @Test
    void retiringOld_releasesOnlyTheOriginal() {
        var index = pairedIn(NodeReplacementPhase.RETIRING_OLD);

        assertThat(index.retirementProtected()).containsExactly(NEW);
        assertThat(index.retiringOriginals()).containsExactly(OLD);
        assertThat(index.surgeReplacements()).isEmpty();
        assertThat(index.voterSwaps()).isEqualTo(Map.of(OLD, NEW));
    }

    @Test
    void terminalDoneAndRolledBack_areInert() {
        for (var phase : Set.of(NodeReplacementPhase.DONE, NodeReplacementPhase.ROLLED_BACK)) {
            var index = pairedIn(phase);

            assertThat(index.retirementProtected()).as(phase.name()).isEmpty();
            assertThat(index.voterSwaps()).as(phase.name()).isEmpty();
            assertThat(index.surgeReplacements()).as(phase.name()).isEmpty();
            assertThat(index.retiringOriginals()).as(phase.name()).isEmpty();
        }
    }

    /// An unknown phase (a newer peer's) and a kept-both failure retire nothing and swap nothing.
    @Test
    void unknownAndKeptBoth_protectBoth_andAuthorizeNothing() {
        for (var phase : Set.of(NodeReplacementPhase.UNKNOWN, NodeReplacementPhase.FAILED_KEPT_BOTH)) {
            var index = pairedIn(phase);

            assertThat(index.retirementProtected()).as(phase.name()).containsExactlyInAnyOrder(OLD, NEW);
            assertThat(index.voterSwaps()).as(phase.name()).isEmpty();
        }
    }

    @Test
    void restore_readsOnlyPairings_andRemoveForgetsOne() {
        var index = NodeReplacementIndex.nodeReplacementIndex();

        index.restore(Map.of(new NodeReplacementKey(OLD), new NodeReplacementValue(NEW, "core", NodeReplacementPhase.SWAPPING, 0L),
                             "unrelated", "value"));
        assertThat(index.voterSwaps()).isEqualTo(Map.of(OLD, NEW));

        index.remove(new NodeReplacementKey(OLD));
        assertThat(index.voterSwaps()).isEmpty();
    }
}
