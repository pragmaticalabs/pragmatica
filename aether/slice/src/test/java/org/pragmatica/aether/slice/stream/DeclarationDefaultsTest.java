// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionMode;

import static org.assertj.core.api.Assertions.assertThat;

/// #3334 S2a1-1: the default byte bound is `max(1 GiB, P x 2 x seal_merge_bytes)`, and the hot-window defaults are
/// `RingGeometry`'s, never a second copy.
class DeclarationDefaultsTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;

    @Test
    void defaultMaxBytes_fewPartitions_isTheOneGibFloor() {
        assertThat(DeclarationDefaults.defaultMaxBytes(16)).isEqualTo(GIB);
    }

    @Test
    void defaultMaxBytes_manyPartitions_isTwoMergedSegmentsPerPartition() {
        assertThat(DeclarationDefaults.defaultMaxBytes(200)).isEqualTo(200 * 2 * 4 * MIB);
        assertThat(DeclarationDefaults.defaultMaxBytes(129)).isEqualTo(129 * 8 * MIB);
    }

    @Test
    void hotDefaults_areRingGeometrys() {
        assertThat(DeclarationDefaults.defaultHotMaxBytes(0)).isEqualTo(RingGeometry.hotWindowBytes(0));
        assertThat(DeclarationDefaults.defaultHotMaxBytes(16 * MIB)).isEqualTo(16 * MIB);
        assertThat(DeclarationDefaults.defaultHotMaxCount()).isEqualTo(RingGeometry.HOT_WINDOW_ENTRIES);
    }

    @Test
    void retentionDefaults_areTwentyFourHoursUnderAny() {
        assertThat(DeclarationDefaults.DEFAULT_MAX_AGE_MS).isEqualTo(86_400_000L);
        assertThat(DeclarationDefaults.DEFAULT_MODE).isEqualTo(RetentionMode.ANY);
    }
}
