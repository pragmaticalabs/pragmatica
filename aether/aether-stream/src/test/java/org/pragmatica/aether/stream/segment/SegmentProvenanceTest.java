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

    /// NT-119 (#2258): the identity header carries the slice, or none, and the events follow untouched; it is the only form
    /// that reads as identified.
    @Test
    void identityHeader_roundTripsTheSliceOrNone_andIsIdentified() {
        var withSlice = SegmentProvenance.split(SegmentProvenance.withIdentity("orders@7", 2, 10, 12, Option.some(SLICE), EVENTS)).unwrap();
        var noSlice = SegmentProvenance.split(SegmentProvenance.withIdentity("orders@7", 2, 10, 12, Option.none(), EVENTS)).unwrap();
        var emptySlice = SegmentProvenance.split(SegmentProvenance.withIdentity("orders@7", 2, 10, 12, Option.some(List.of()), EVENTS)).unwrap();

        assertThat(withSlice.slice()).isEqualTo(Option.some(SLICE));
        assertThat(withSlice.events()).isEqualTo(EVENTS);
        assertThat(withSlice.identified()).isTrue();
        assertThat(noSlice.slice()).isEqualTo(Option.none());
        assertThat(noSlice.events()).isEqualTo(EVENTS);
        assertThat(emptySlice.slice()).isEqualTo(Option.some(List.of()));
        assertThat(SegmentProvenance.split(SegmentProvenance.withSlice(SLICE, EVENTS)).unwrap().identified()).isFalse();
        assertThat(SegmentProvenance.split(EVENTS).unwrap().identified()).isFalse();
    }

    /// NT-119 / MT-48: identical events of two partitions, or of two lives of one stream, make different blocks; only the
    /// same range of the same partition of the same life makes the same bytes.
    @Test
    void identityHeader_separatesPartitionsAndLives_butNotHoldersOfOneSegment() {
        var p0 = SegmentProvenance.withIdentity("orders", 0, 10, 12, Option.none(), EVENTS);

        assertThat(p0).isNotEqualTo(SegmentProvenance.withIdentity("orders", 1, 10, 12, Option.none(), EVENTS));
        assertThat(p0).isNotEqualTo(SegmentProvenance.withIdentity("orders@7", 0, 10, 12, Option.none(), EVENTS));
        assertThat(p0).isEqualTo(SegmentProvenance.withIdentity("orders", 0, 10, 12, Option.none(), EVENTS));
    }

    @Test
    void isIdentified_readsTheStoredBytes() {
        assertThat(SegmentProvenance.isIdentified(SegmentProvenance.withIdentity("orders", 0, 0, 0, Option.none(), EVENTS))).isTrue();
        assertThat(SegmentProvenance.isIdentified(SegmentProvenance.withSlice(SLICE, EVENTS))).isFalse();
        assertThat(SegmentProvenance.isIdentified(EVENTS)).isFalse();
        assertThat(SegmentProvenance.isIdentified(new byte[3])).isFalse();
    }

    @Test
    void truncatedIdentityHeader_failsTheRead() {
        var block = SegmentProvenance.withIdentity("orders", 0, 10, 12, Option.some(SLICE), EVENTS);

        SegmentProvenance.split(Arrays.copyOf(block, 20)).onSuccess(_ -> fail("a truncated identity header must not read"));
    }

    private static EpochStart start(String token, long offset) {
        return new EpochStart(EpochKey.epochKey(token).unwrap(), offset);
    }
}
