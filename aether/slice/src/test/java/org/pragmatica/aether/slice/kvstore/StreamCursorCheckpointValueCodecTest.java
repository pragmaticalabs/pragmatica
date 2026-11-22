// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// #1333: the checkpoint value grew by the rewind epoch (two longs and the rewind flag) beside #1271's token. A value codec regenerated
/// without the two new components would still round-trip an equal record for a never-rewound group, which
/// is why the rewound arm is the one that discriminates. The full consensus command (`KVCommand.Put`) is
/// pinned in aether-node's `StreamCursorCheckpointPutCodecTest`, where the node codec is assembled.
class StreamCursorCheckpointValueCodecTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                  KvstoreCodecsSlice.CODECS);

    @Test
    void checkpointValue_roundTrip_distinguishesTheEpoch() {
        var token = AssignmentToken.assignmentToken(NodeId.nodeId("node-1").unwrap(), Epoch.epoch(0L, 1L, 1L));
        var unrewound = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, token);
        var rewound = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, token, RewindEpoch.rewindEpoch(0L, 1L, 1L));

        assertThat(roundTrip(unrewound).rewindEpoch()).isEqualTo(RewindEpoch.NONE);
        assertThat(roundTrip(rewound).rewindEpoch()).isEqualTo(RewindEpoch.rewindEpoch(0L, 1L, 1L));
        assertThat(roundTrip(rewound)).isEqualTo(rewound);
    }

    /// #3334 S1a-1a: `member` rides the token inside the checkpoint and both assignment records; `some` and `none` are distinct.
    @Test
    void member_roundTrips_someAndNone_onTheTokenTheAssignmentAndTheCheckpoint() {
        var node = NodeId.nodeId("node-1").unwrap();
        var epoch = Epoch.epoch(0L, 1L, 1L);

        for (var member : List.<Option<String>>of(some("m-a"), none())) {
            var token = AssignmentToken.assignmentToken(node, epoch, member);
            var assignment = ConsumerAssignmentValue.consumerAssignmentValue(node, epoch, 5L, HlcTimestamp.ZERO, member);
            var checkpoint = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, token);

            assertThat(CODEC.<AssignmentToken>read(encoded(token))).isEqualTo(token);
            assertThat(CODEC.<ConsumerAssignmentValue>read(encoded(assignment))).isEqualTo(assignment);
            assertThat(roundTrip(checkpoint)).isEqualTo(checkpoint);
            assertThat(roundTrip(checkpoint).token().member()).isEqualTo(member);
        }
        assertThat(AssignmentToken.assignmentToken(node, epoch, some("m-a"))).isNotEqualTo(AssignmentToken.assignmentToken(node, epoch, none()));
    }

    /// Callers-get: the 2-arg factory is the no-member token, equal to the 3-arg factory given `none()` and to nothing with a member.
    @Test
    void oldArityToken_isTheNoMemberToken() {
        var node = NodeId.nodeId("node-1").unwrap();
        var epoch = Epoch.epoch(0L, 1L, 1L);

        assertThat(AssignmentToken.assignmentToken(node, epoch)).isEqualTo(AssignmentToken.assignmentToken(node, epoch, none()));
        assertThat(AssignmentToken.assignmentToken(node, epoch)).isNotEqualTo(AssignmentToken.assignmentToken(node, epoch, some("")));
        assertThat(ConsumerAssignmentValue.consumerAssignmentValue(node, epoch, 5L, HlcTimestamp.ZERO).member()).isEqualTo(none());
    }

    private static ByteBuf encoded(Object value) {
        var buf = Unpooled.buffer();

        CODEC.write(buf, value);
        return buf;
    }

    private static StreamCursorCheckpointValue roundTrip(StreamCursorCheckpointValue value) {
        var buf = Unpooled.buffer();

        CODEC.write(buf, value);

        return CODEC.read(buf);
    }
}
