// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// `promotion` defaults to ON in every shape of [StorageConfig] that predates the key, so a caller or a config that
/// never heard of it keeps promoting; only the ten-argument factory can switch it off. The cadence replacement on
/// [TimeoutsConfig] keeps every other timeout.
class StorageConfigPromotionTest {
    @Test
    void storageConfig_everyShapeThatPredatesTheKey_promotes() {
        var defaults = StorageConfig.storageConfig();

        assertThat(defaults.promotion()).isTrue();
        assertThat(StorageConfig.storageConfig(1, 2, "d", "s", 3, "60s", 4).promotion()).isTrue();
        assertThat(StorageConfig.storageConfig(1, 2, "d", "s", 3, "60s", 4, "w").promotion()).isTrue();
        assertThat(StorageConfig.storageConfig(1, 2, "d", "s", 3, "60s", 4, "w", true).promotion()).isTrue();
        assertThat(new StorageConfig(1, 2, "d", "s", 3, "60s", 4, "w", true).promotion()).isTrue();
    }

    @Test
    void storageConfig_tenArgumentFactory_carriesTheSwitch() {
        assertThat(StorageConfig.storageConfig(1, 2, "d", "s", 3, "60s", 4, "w", true, false).promotion()).isFalse();
        assertThat(StorageConfig.storageConfig(1, 2, "d", "s", 3, "60s", 4, "w", true, true).promotion()).isTrue();
    }

    @Test
    void timeoutsConfig_withStorageMaintenance_replacesOnlyTheCadence() {
        var base = TimeoutsConfig.timeoutsConfig();
        var cadence = new TimeoutsConfig.StorageMaintenanceTimeouts(TimeSpan.timeSpan(7).seconds());

        var replaced = base.withStorageMaintenance(cadence);

        assertThat(replaced.storageMaintenance()).isEqualTo(cadence);
        assertThat(replaced).usingRecursiveComparison().ignoringFields("storageMaintenance").isEqualTo(base);
    }
}
