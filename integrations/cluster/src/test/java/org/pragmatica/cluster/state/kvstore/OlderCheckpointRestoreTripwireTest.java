// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.infrastructure.OlderCheckpointCluster;
import org.pragmatica.consensus.rabia.infrastructure.OlderCheckpointCluster.Applied;
import org.pragmatica.consensus.rabia.infrastructure.TestCluster.StringKey;

import static org.assertj.core.api.Assertions.assertThat;

/// TW-O13 of the worker-replica-feed design (W-OBS): the crash restart under ONE cluster identity.
///
/// A core persists its state only on pause, stop and restore, never on commit (`RabiaEngine.saveState`). A cluster that
/// restarts from such a checkpoint has lost every slot committed after it, and when it commits again it REUSES those slot
/// numbers for different batches. Nothing at this commit detects that across a restart (only `detectBootFutureHistory`, which
/// compares a restarting node with what its peers report). A replica that followed the first history by slot number alone
/// cannot tell the two apart; the design's anchor (the last 8 `(slot, batchId)` pairs) is what makes it refuse.
///
/// The harness is `OlderCheckpointCluster`: real engines, real `KVStore`, in-process network. The checkpoint comes from a real
/// pause (so the saved state is the engine's own), the restart seeds every node with it, and each store records what it applied
/// as `(slot, batchId)`. The enabled half pins the premise (slot reuse with different ids, undetected). The probe that decides
/// what a single-pair and an 8-pair anchor accept is P-0 and is not part of this PR.
class OlderCheckpointRestoreTripwireTest {
    private static Map<Long, String> idsBySlot(List<Applied> history) {
        return history.stream().collect(Collectors.toMap(Applied::slot, Applied::batchId));
    }

    @Test
    void restartFromAnOlderCheckpoint_reusesSlotsWithDifferentBatches_andNothingDetectsIt_today() {
        var first = OlderCheckpointCluster.fresh().awaitActive();

        first.commit("common-0", "kept").commit("common-1", "kept");
        var checkpoints = first.pauseAndCheckpoint();
        var checkpointSlot = checkpoints.get(first.node(1).id()).lastCommittedPhase().value();

        first.commit("first-history-a", "old-a").commit("first-history-b", "old-b");
        var firstHistory = first.node(1).store().applied();

        first.close();

        var second = OlderCheckpointCluster.restartedFrom(checkpoints).awaitActive();

        try {
            second.commit("second-history-a", "new-a").commit("second-history-b", "new-b");
            var secondHistory = second.node(1).store().applied();
            var firstIds = idsBySlot(firstHistory);
            var secondIds = idsBySlot(secondHistory);
            var reused = secondIds.keySet().stream().filter(firstIds::containsKey).toList();

            assertThat(checkpointSlot).as("precondition: the checkpoint is a real mid-history one (after the first commits, before the later ones)")
                                      .isPositive()
                                      .isLessThan(firstHistory.getLast().slot() + 1);
            assertThat(reused).as("TW-O13: the restarted cluster commits at slots the first history already used").isNotEmpty();
            assertThat(reused).as("and at each of them the batch is a different one: same slot, different batch id")
                              .allSatisfy(slot -> assertThat(secondIds.get(slot)).isNotEqualTo(firstIds.get(slot)));
            assertThat(second.node(1).store().get(new StringKey("first-history-a")).isPresent())
                    .as("the restarted cluster no longer holds what the first history committed after the checkpoint")
                    .isFalse();
            second.nodes().forEach(node -> assertThat(node.engine().stateTransferFailure().isEmpty() && node.engine().ownRestoreFailure().isEmpty())
                    .as("nothing across the restart flags the divergence on %s", node.id())
                    .isTrue());
            second.nodes().forEach(node -> assertThat(node.bootFutureHistoryEvents())
                    .as("and neither does the one detector the design names, RabiaEngine.detectBootFutureHistory (it compares a restarting node with what its peers report, here they report the same checkpoint), on %s", node.id())
                    .isEmpty());
        } finally {
            second.close();
        }
    }

    @Disabled("W1 (CommitRing, anchor): enable when the ring exists; a replica that followed the first history and resubscribes to the restarted cluster must be refused by its anchor and re-snapshot")
    @Test
    void resubscribingReplica_isRefusedByItsAnchor() {
        throw ReplicaSeam.failClosed(ReplicaSeam.typeExists("CommitRing"),
                                     "W1 CommitRing with k = 8 (slot, batchId) anchor pairs",
                                     "drive the restart above, resubscribe a replica that followed the first history, and assert it is refused (AHEAD or anchor mismatch) and takes a snapshot");
    }
}
