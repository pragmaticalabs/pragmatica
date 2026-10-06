// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 rule 3: worker surplus selection honours a replacement pairing. Without one it is newest-first.
class SurplusWorkerVictimsTest {
    private static final List<String> WORKERS = List.of("pool-worker-0", "pool-worker-1", "pool-worker-2", "pool-worker-r9");

    private static NodeReplacementIndex paired(String original, String replacement, NodeReplacementPhase phase) {
        var index = NodeReplacementIndex.nodeReplacementIndex();

        index.put(new NodeReplacementKey(new NodeId(original)), new NodeReplacementValue(new NodeId(replacement), "worker", phase, 0L));
        return index;
    }

    /// Control: no pairing, newest first — the surge worker `-r9` is the victim, which is today's behaviour.
    @Test
    void surplusWorkerVictims_withoutPairing_isNewestFirst() {
        assertThat(ClusterTopologyManagerRecord.surplusWorkerVictims(WORKERS, 1, NodeReplacementIndex.nodeReplacementIndex()))
            .containsExactly("pool-worker-r9");
        assertThat(ClusterTopologyManagerRecord.surplusWorkerVictims(WORKERS, 2, NodeReplacementIndex.nodeReplacementIndex()))
            .containsExactly("pool-worker-r9", "pool-worker-2");
    }

    /// The +1 replacement is surge, not surplus: nothing is terminated, and no other worker is taken in its place.
    @Test
    void surplusWorkerVictims_pairedIncomingReplacement_isNotTerminated() {
        assertThat(ClusterTopologyManagerRecord.surplusWorkerVictims(WORKERS, 1, paired("pool-worker-1", "pool-worker-r9", NodeReplacementPhase.JOINING)))
            .isEmpty();
    }

    @Test
    void surplusWorkerVictims_pairedOriginalDueForRetirement_isTakenFirst() {
        assertThat(ClusterTopologyManagerRecord.surplusWorkerVictims(WORKERS, 1, paired("pool-worker-1", "pool-worker-r9", NodeReplacementPhase.RETIRING_OLD)))
            .containsExactly("pool-worker-1");
    }

    /// A genuine surplus alongside a live pairing takes neither paired node.
    @Test
    void surplusWorkerVictims_genuineSurplusBesideAPairing_spareBothPairedNodes() {
        var workers = List.of("pool-worker-0", "pool-worker-1", "pool-worker-2", "pool-worker-r5", "pool-worker-r9");

        assertThat(ClusterTopologyManagerRecord.surplusWorkerVictims(workers, 2, paired("pool-worker-2", "pool-worker-r9", NodeReplacementPhase.CANARY)))
            .containsExactly("pool-worker-r5");
    }

    @Test
    void surplusWorkerVictims_terminalPairing_isInert() {
        assertThat(ClusterTopologyManagerRecord.surplusWorkerVictims(WORKERS, 1, paired("pool-worker-1", "pool-worker-r9", NodeReplacementPhase.DONE)))
            .containsExactly("pool-worker-r9");
    }
}
