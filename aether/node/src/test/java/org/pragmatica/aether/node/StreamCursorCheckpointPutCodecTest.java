// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1333: the consensus wire for a cursor checkpoint is a `KVCommand.Put` of `StreamCursorCheckpointValue`
/// encoded by the NODE codec — the same registry the Rabia batch uses. Pinned here, where that codec is
/// assembled, with a rewound value under an assignment token so the #1271 and #1333 components are the
/// ones that must survive.
class StreamCursorCheckpointPutCodecTest {
    @Test
    void checkpointPut_roundTrips_throughTheNodeCodec_withTheRewindEpoch() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var key = StreamCursorCheckpointKey.streamCursorCheckpointKey("topic:ns:orders:1.0.0",
                                                                      0,
                                                                      "org.example:orders#onPlaced");
        var token = AssignmentToken.assignmentToken(NodeId.nodeId("node-1").unwrap(), Epoch.epoch(1L, 1L));
        var value = new StreamCursorCheckpointValue(42L, 1_700_000_000_500L, token, 3L, 2L, true);
        var put = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var buf = Unpooled.buffer();

        codec.write(buf, put);
        KVCommand.Put<?, ?> decoded = codec.read(buf);

        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
        assertThat(((StreamCursorCheckpointValue) decoded.value()).token()).isEqualTo(token);
        assertThat(((StreamCursorCheckpointValue) decoded.value()).rewindEpoch()).isEqualTo(RewindEpoch.rewindEpoch(3L,
                                                                                                                    2L));
    }
}
