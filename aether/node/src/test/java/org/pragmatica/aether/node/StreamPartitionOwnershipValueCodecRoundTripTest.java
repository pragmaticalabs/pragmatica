// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1883: the ownership record's `fenced` set and `failoverRefusalSeq` survive the real node codec (the bytes a
/// committed Put is replicated, snapshotted and restored as), so a restart neither unfences a member nor rewinds the
/// refusal count (which would repeat a failover event id a reader de-duplicates by).
class StreamPartitionOwnershipValueCodecRoundTripTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void fencedAndRefusalSeq_surviveTheNodeCodec() {
        var owner = new NodeId("node-a");
        var gone = new NodeId("node-x");
        var refusedTwice = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                                       Epoch.epoch(1L, 2L, 3L),
                                                                                       3L,
                                                                                       HlcTimestamp.ZERO,
                                                                                       List.of(owner, new NodeId("node-b")),
                                                                                       7L,
                                                                                       List.of(gone))
                                                         .withFailoverRefused(true)
                                                         .withFailoverRefused(false)
                                                         .withFailoverRefused(true);

        assertThat(refusedTwice.failoverRefusalSeq()).as("fixture premise").isEqualTo(2L);
        assertThat(refusedTwice.fenced()).as("fixture premise").containsExactly(gone);

        var decoded = (StreamPartitionOwnershipValue) CODEC.decode(CODEC.encode(refusedTwice));

        assertThat(decoded.failoverRefusalSeq()).as("refusal count after the codec").isEqualTo(2L);
        assertThat(decoded.fenced()).as("fenced set after the codec").containsExactly(gone);
        assertThat(decoded).isEqualTo(refusedTwice);
    }
}
