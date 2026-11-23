// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamStorage;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #3458 M-1 (T-M2): `StreamStorage` is a wire enum whose ordinal is the encoding and whose `UNKNOWN` is the decode-only
/// sentinel. An ordinal this build does not know decodes to `UNKNOWN` instead of throwing. The processor refuses an enum that does
/// not declare `UNKNOWN` last, so the sentinel's removal is a compile error, not a test (the mutant's red is the processor's message).
class StreamStorageCodecTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void ordinals_areDense_andUnknownIsLast() {
        assertThat(StreamStorage.values()).containsExactly(StreamStorage.PERSISTENT, StreamStorage.MEMORY, StreamStorage.UNKNOWN);
    }

    @Test
    void everyConstant_roundTrips() {
        for (var storage : StreamStorage.values()) {
            assertThat((StreamStorage) CODEC.decode(CODEC.encode(storage))).isEqualTo(storage);
        }
    }

    @Test
    void anOrdinalPastTheLastConstant_decodesToUnknown() {
        var frame = CODEC.encode(StreamStorage.MEMORY);
        var last = frame.length - 1;

        assertThat(frame[last]).as("the ordinal is the last byte of the frame").isEqualTo((byte) StreamStorage.MEMORY.ordinal());
        frame[last] = (byte) (StreamStorage.UNKNOWN.ordinal() + 1);
        assertThat((StreamStorage) CODEC.decode(frame)).isEqualTo(StreamStorage.UNKNOWN);
        frame[last] = 0x7F;
        assertThat((StreamStorage) CODEC.decode(frame)).isEqualTo(StreamStorage.UNKNOWN);
    }
}
