// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.FrameworkCodecs;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1873: `EpochStart.coversFrom` (the oldest epoch an entry stands for) crosses the consensus wire in the ownership record. A
/// null or ZERO `coversFrom` is read as the entry's own epoch, so a codec that dropped the field would decode every entry as
/// unfolded WITHOUT failing a round trip of unfolded entries: the folded entry's `coversFrom` is what must come back unchanged.
class EpochStartCodecTest {
    private static final Epoch OLDEST_DROPPED = Epoch.epoch(1L, 2L, 3L);
    private static final Epoch OWN = Epoch.epoch(1L, 2L, 9L);

    @Test
    void aFoldedEntry_keepsItsCoversFrom_throughTheNodeCodec() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var folded = new EpochStart(OWN, 100L, OLDEST_DROPPED);
        var unfolded = new EpochStart(Epoch.epoch(1L, 2L, 10L), 120L);
        var value = new StreamPartitionOwnershipValue(NodeId.nodeId("node-1").unwrap(),
                                                      Epoch.epoch(1L, 2L, 10L),
                                                      10L,
                                                      HlcTimestamp.ZERO,
                                                      List.of(NodeId.nodeId("node-1").unwrap()),
                                                      1L,
                                                      false,
                                                      List.of(),
                                                      List.of(folded, unfolded));
        var put = new KVCommand.Put<AetherKey, AetherValue>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0), value);
        var buf = Unpooled.buffer();

        codec.write(buf, put);
        KVCommand.Put<?, ?> decoded = codec.read(buf);
        var starts = ((StreamPartitionOwnershipValue) decoded.value()).epochStarts();

        assertThat(starts.getFirst().coversFrom()).as("the folded entry stands for an older epoch than its own").isEqualTo(OLDEST_DROPPED);
        assertThat(starts.getFirst().coversFrom()).isNotEqualTo(starts.getFirst().epoch());
        assertThat(starts.getLast().coversFrom()).as("an unfolded entry stands for its own epoch").isEqualTo(starts.getLast().epoch());
        assertThat(starts).isEqualTo(List.of(folded, unfolded));
    }

    @Test
    void aNullOrZeroCoversFrom_isReadAsTheEntrysOwnEpoch() {
        assertThat(new EpochStart(OWN, 5L, null).coversFrom()).isEqualTo(OWN);
        assertThat(new EpochStart(OWN, 5L, Epoch.ZERO).coversFrom()).isEqualTo(OWN);
        assertThat(new EpochStart(OWN, 5L).coversFrom()).isEqualTo(OWN);
    }
}
