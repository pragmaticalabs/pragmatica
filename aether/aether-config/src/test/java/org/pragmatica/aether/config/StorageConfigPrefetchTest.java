// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-node prefetching is on for every instance a config names unless it says otherwise, through every way
/// a `StorageConfig` is built: the defaults, each earlier factory shape, and the pre-`prefetch` constructor.
class StorageConfigPrefetchTest {
    private static final long MEMORY = 1024L;
    private static final long DISK = 4096L;

    @Test
    void storageConfig_defaults_takePartInPrefetch() {
        assertThat(StorageConfig.storageConfig().prefetch()).isTrue();
    }

    @Test
    void storageConfig_earlierFactoryShapes_takePartInPrefetch() {
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5).prefetch()).isTrue();
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal").prefetch()).isTrue();
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", true).prefetch()).isTrue();
    }

    @Test
    void storageConfig_theConstructorFromBeforePrefetch_takesPartInPrefetch() {
        assertThat(new StorageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false).prefetch()).isTrue();
    }

    @Test
    void storageConfig_theShapeFromBeforePrefetch_takesPartInPrefetch_andKeepsItsPromotion() {
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false, false).prefetch()).isTrue();
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false, false).promotion()).isFalse();
        assertThat(new StorageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false, false).prefetch()).isTrue();
    }

    @Test
    void storageConfig_explicitSwitch_isKept_withoutTouchingPromotion() {
        var off = StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false, true, false);
        var on = StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false, false, true);

        assertThat(off.prefetch()).isFalse();
        assertThat(off.promotion()).isTrue();
        assertThat(on.prefetch()).isTrue();
        assertThat(on.promotion()).isFalse();
    }

    @Test
    void storageConfig_everyShapeWithoutACap_takesTheDefaultCap() {
        assertThat(StorageConfig.storageConfig().prefetchMaxInFlight()).isEqualTo(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", true, false).prefetchMaxInFlight())
            .isEqualTo(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", true, false, false).prefetchMaxInFlight())
            .isEqualTo(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
        assertThat(new StorageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false).prefetchMaxInFlight())
            .isEqualTo(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
    }

    @Test
    void storageConfig_explicitCap_isKept() {
        assertThat(StorageConfig.storageConfig(MEMORY, DISK, "d", "s", 1, "60s", 5, "wal", false, true, true, 7).prefetchMaxInFlight())
            .isEqualTo(7);
    }
}
