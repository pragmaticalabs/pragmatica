// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.storage.AppendLog.EpochStart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1596: the owner-epoch slice header in front of a sealed segment's events.
class SegmentProvenanceTest {
    private static final byte[] EVENTS = ByteBuffer.allocate(24).putLong(10).putLong(1).putInt(4).putInt(0x61626364).array();
    private static final List<EpochStart> SLICE = List.of(start("o.1.0", 0), start("o.3.0", 10));

    @Test
    void slice_roundTrips_andTheEventsFollowUntouched() {
        var split = SegmentProvenance.split(SegmentProvenance.withSlice(SLICE, EVENTS)).unwrap();

        assertThat(split.slice()).isEqualTo(Option.some(SLICE));
        assertThat(split.events()).isEqualTo(EVENTS);
    }

    /// A block sealed before the header existed starts with a record offset, never negative: all events, no slice.
    @Test
    void blockWithoutAHeader_isAllEvents() {
        var split = SegmentProvenance.split(EVENTS).unwrap();

        assertThat(split.slice()).isEqualTo(Option.none());
        assertThat(split.events()).isEqualTo(EVENTS);
    }

    @Test
    void truncatedHeader_failsTheRead() {
        var block = SegmentProvenance.withSlice(SLICE, EVENTS);

        SegmentProvenance.split(Arrays.copyOf(block, 14)).onSuccess(_ -> fail("a truncated header must not read as a slice"));
    }

    private static EpochStart start(String token, long offset) {
        return new EpochStart(EpochKey.epochKey(token).unwrap(), offset);
    }
}
