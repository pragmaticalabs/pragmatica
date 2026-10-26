// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/// A0b: the gauge a degraded instance is seen through. Reads the registered meter, not the source that registers it.
class StorageDiskDegradedGaugeTest {
    private static double gaugeOf(ObservabilityRegistry observability, String instance) {
        var gauge = observability.registry().find("aether.storage.disk.degraded").tag("instance", instance).gauge();

        assertThat(gauge).as("gauge registered for instance " + instance).isNotNull();
        return gauge.value();
    }

    @Test
    void gauge_isRegisteredPerInstance_andReadsOneExactlyWhileTheDiskTierIsUnavailable() {
        var observability = ObservabilityRegistry.prometheus();
        var degraded = DegradeReporter.degradeReporter("artifacts");
        var healthy = DegradeReporter.degradeReporter("content");

        ManagementServerImpl.registerDiskDegradedGauge(observability, "artifacts", degraded);
        ManagementServerImpl.registerDiskDegradedGauge(observability, "content", healthy);

        assertThat(gaugeOf(observability, "artifacts")).isZero();

        degraded.degraded(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "disk tier {} unavailable", "artifacts");

        assertThat(gaugeOf(observability, "artifacts")).isEqualTo(1.0);
        assertThat(gaugeOf(observability, "content")).as("another instance is unaffected").isZero();
    }
}
