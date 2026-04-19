// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


class CommunityGenerationSnapshotTest {
    @Test
    void communityGenerationSnapshot_allFieldsSet_populatesRecord() {
        var governor = NodeId.nodeId("gov-1").unwrap();
        var member = NodeId.nodeId("worker-1").unwrap();
        var commEpoch = Epoch.epoch(1L, 3L);
        var coreEpoch = Epoch.epoch(7L, 42L);
        var hlc = new HlcTimestamp(1000L, "gov-1");

        var snapshot = CommunityGenerationSnapshot.communityGenerationSnapshot("pool-a",
                                                                                1L,
                                                                                commEpoch,
                                                                                governor,
                                                                                List.of(member),
                                                                                coreEpoch,
                                                                                Set.of("p1"),
                                                                                hlc);

        assertThat(snapshot.communityId()).isEqualTo("pool-a");
        assertThat(snapshot.communityTerm()).isEqualTo(1L);
        assertThat(snapshot.communityEpoch()).isEqualTo(commEpoch);
        assertThat(snapshot.governorNodeId()).isEqualTo(governor);
        assertThat(snapshot.members()).containsExactly(member);
        assertThat(snapshot.observedCoreEpoch()).isEqualTo(coreEpoch);
        assertThat(snapshot.partitionsHeld()).containsExactly("p1");
        assertThat(snapshot.committedAt()).isEqualTo(hlc);
    }

    @Test
    void construct_nullMembers_normalizesToEmpty() {
        var governor = NodeId.nodeId("gov-1").unwrap();

        var snapshot = new CommunityGenerationSnapshot("pool-a",
                                                       0L,
                                                       Epoch.ZERO,
                                                       governor,
                                                       null,
                                                       Epoch.ZERO,
                                                       Set.of(),
                                                       HlcTimestamp.ZERO);

        assertThat(snapshot.members()).isEmpty();
    }

    @Test
    void construct_nullPartitions_normalizesToEmpty() {
        var governor = NodeId.nodeId("gov-1").unwrap();

        var snapshot = new CommunityGenerationSnapshot("pool-a",
                                                       0L,
                                                       Epoch.ZERO,
                                                       governor,
                                                       List.of(),
                                                       Epoch.ZERO,
                                                       null,
                                                       HlcTimestamp.ZERO);

        assertThat(snapshot.partitionsHeld()).isEmpty();
    }

    @Test
    void empty_producesZeroTermAndEpoch() {
        var governor = NodeId.nodeId("gov-1").unwrap();

        var empty = CommunityGenerationSnapshot.empty("pool-a", governor);

        assertThat(empty.communityTerm()).isZero();
        assertThat(empty.communityEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(empty.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(empty.members()).isEmpty();
    }
}
