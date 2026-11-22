// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RingGeometryTest {
    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;

    @Test
    void firstSegmentBytes_isTheMinOfTheRegionAndTheLargerOfOneUnitAndTheEvent() {
        assertThat(RingGeometry.firstSegmentBytes(4 * MIB, MIB)).isEqualTo(MIB);
        assertThat(RingGeometry.firstSegmentBytes(4 * MIB, 64 * KIB)).isEqualTo(256 * KIB);
        assertThat(RingGeometry.firstSegmentBytes(4 * MIB, 0)).isEqualTo(256 * KIB);
        assertThat(RingGeometry.firstSegmentBytes(128 * KIB, 64 * KIB)).isEqualTo(128 * KIB);
        assertThat(RingGeometry.firstSegmentBytes(512 * KIB, 8 * MIB)).isEqualTo(512 * KIB);
    }

    @Test
    void firstSegmentBytes_betweenUnits_roundsUpToAWholeUnitClampedByTheRegion() {
        assertThat(RingGeometry.firstSegmentBytes(4 * MIB, 300_000)).isEqualTo(512 * KIB);
        assertThat(RingGeometry.firstSegmentBytes(300_000, 300_000)).isEqualTo(300_000);
        assertThat(RingGeometry.firstSegmentBytes(400_000, 300_000)).isEqualTo(400_000);
    }

    @Test
    void firstRegion_isDefaultSizeSegmentsWithTheLastClamped() {
        assertThat(RingGeometry.firstSegmentCount(MIB)).isEqualTo(4);
        assertThat(RingGeometry.firstSegmentCount(300_000)).isEqualTo(2);
        assertThat(RingGeometry.firstSegmentCount(128 * KIB)).isEqualTo(1);
        assertThat(RingGeometry.firstSegmentSize(300_000, 0)).isEqualTo(256 * KIB);
        assertThat(RingGeometry.firstSegmentSize(300_000, 1)).isEqualTo(300_000 - 256 * KIB);
    }

    /// T-218 at the geometry level.
    @Test
    void floorBytes_holdsOneMaximalEvent() {
        assertThat(RingGeometry.floorBytes(16_384, 4 * MIB, MIB)).isEqualTo(1_441_856L);
        assertThat(RingGeometry.floorBytes(16_384, 4 * MIB, 64 * KIB)).isEqualTo(655_424L);
        assertThat(RingGeometry.floorBytes(16_384, 128 * KIB, 64 * KIB)).isEqualTo(524_352L);
    }

    /// T-267 at the geometry level.
    @Test
    void maxCapacity_movesWithTheFirstSegment() {
        assertThat(RingGeometry.maxCapacity(256 * KIB)).isEqualTo(384_307_168_202_271_399L);
        assertThat(RingGeometry.maxCapacity(MIB)).isEqualTo(384_307_168_202_238_631L);
        assertThat(RingGeometry.indexable(RingGeometry.maxCapacity(MIB), MIB)).isTrue();
        assertThat(RingGeometry.indexable(RingGeometry.maxCapacity(256 * KIB), MIB)).isFalse();
    }

    @Test
    void hotWindow_isNeverSmallerThanOneEvent() {
        assertThat(RingGeometry.HOT_WINDOW_ENTRIES).isEqualTo(16_384L);
        assertThat(RingGeometry.hotWindowBytes(64 * KIB)).isEqualTo(4 * MIB);
        assertThat(RingGeometry.hotWindowBytes(8 * MIB)).isEqualTo(8 * MIB);
    }
}
