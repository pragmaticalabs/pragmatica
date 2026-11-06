// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2734: `StreamTripwireSupport.awaitIsr` must not release a test to publish through the owner while the owner's own store has
/// not applied the committed ownership record. `EmberColdRestartAckedLossTripwireTest.runLateReturn` published at once after ONE
/// node showed the record and met an owner whose store answered none: `StaleEpochAppend(presented 0:0:0, current 1:1:1)`
/// (rc5 merge queue, run 42197318338). The apply lag between nodes is not controllable in a live cluster, so the gate is pinned
/// on its own predicate with the owner's view as input.
class StreamTripwireSupportOwnerAppliedTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId REPLICA = new NodeId("replica");
    private static final StreamPartitionOwnershipValue COMMITTED = record(Epoch.epoch(1L, 1L, 1));
    private static final StreamPartitionOwnershipValue OLDER = record(Epoch.epoch(0L, 1L, 1));

    @Test
    void ownerApplied_isFalse_whileTheOwnersOwnStoreAnswersNone() {
        assertThat(StreamTripwireSupport.ownerApplied(Option.some("owner-node"), _ -> Option.none(), COMMITTED)).isFalse();
    }

    @Test
    void ownerApplied_isFalse_whileTheOwnersStoreStillHoldsAnOlderRecord() {
        assertThat(StreamTripwireSupport.ownerApplied(Option.some("owner-node"), _ -> Option.some(OLDER), COMMITTED)).isFalse();
    }

    @Test
    void ownerApplied_isTrue_onceTheOwnersStoreHoldsTheCommittedRecord() {
        assertThat(StreamTripwireSupport.ownerApplied(Option.some("owner-node"), _ -> Option.some(COMMITTED), COMMITTED)).isTrue();
    }

    @Test
    void ownerApplied_isTrue_forAnOwnerThatIsNotRunning() {
        assertThat(StreamTripwireSupport.ownerApplied(Option.<String> none(), _ -> Option.none(), COMMITTED)).isTrue();
    }

    private static StreamPartitionOwnershipValue record(Epoch epoch) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER, epoch, 3L, HlcTimestamp.ZERO, List.of(OWNER, REPLICA), 5L);
    }
}
