// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;

/// #1333: the checkpoint value grew from two longs to four (the rewind epoch). A value codec regenerated
/// without the two new components would still round-trip an equal record for a never-rewound group, which
/// is why the rewound arm is the one that discriminates. The full consensus command (`KVCommand.Put`) is
/// pinned in aether-node's `StreamCursorCheckpointPutCodecTest`, where the node codec is assembled.
class StreamCursorCheckpointValueCodecTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), KvstoreCodecsSlice.CODECS);

    @Test
    void checkpointValue_roundTrip_distinguishesTheEpoch() {
        var unrewound = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L);
        var rewound = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, RewindEpoch.rewindEpoch(1L, 1L));

        assertThat(roundTrip(unrewound).rewindEpoch()).isEqualTo(RewindEpoch.NONE);
        assertThat(roundTrip(rewound).rewindEpoch()).isEqualTo(RewindEpoch.rewindEpoch(1L, 1L));
    }

    private static StreamCursorCheckpointValue roundTrip(StreamCursorCheckpointValue value) {
        var buf = Unpooled.buffer();

        CODEC.write(buf, value);

        return CODEC.read(buf);
    }
}
