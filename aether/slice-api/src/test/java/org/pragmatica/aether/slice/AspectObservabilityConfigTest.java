// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.AspectObservabilityConfig.aspectObservabilityConfig;

class AspectObservabilityConfigTest {

    @Test
    void off_constant_isAllDisabledAtZeroDepth() {
        var off = AspectObservabilityConfig.OFF;

        assertThat(off.logging()).isFalse();
        assertThat(off.metrics()).isFalse();
        assertThat(off.spans()).isFalse();
        assertThat(off.tracing()).isFalse();
        assertThat(off.depth()).isZero();
        assertThat(off.allOff()).isTrue();
    }

    @Test
    void allOff_whenAllFacetsDisabled_returnsTrue() {
        var cfg = aspectObservabilityConfig(false, false, false, false, 0);

        assertThat(cfg.allOff()).isTrue();
    }

    @Test
    void allOff_whenLoggingEnabled_returnsFalse() {
        var cfg = aspectObservabilityConfig(true, false, false, false, 1);

        assertThat(cfg.allOff()).isFalse();
    }

    @Test
    void allOff_whenMetricsEnabled_returnsFalse() {
        var cfg = aspectObservabilityConfig(false, true, false, false, 1);

        assertThat(cfg.allOff()).isFalse();
    }

    @Test
    void allOff_whenSpansEnabled_returnsFalse() {
        var cfg = aspectObservabilityConfig(false, false, true, false, 1);

        assertThat(cfg.allOff()).isFalse();
    }

    @Test
    void allOff_whenTracingEnabled_returnsFalse() {
        var cfg = aspectObservabilityConfig(false, false, false, true, 1);

        assertThat(cfg.allOff()).isFalse();
    }

    @Test
    void allOff_whenDepthSetButFacetsDisabled_returnsTrue() {
        var cfg = aspectObservabilityConfig(false, false, false, false, 5);

        assertThat(cfg.allOff()).isTrue();
    }

    @Test
    void aspectObservabilityConfig_factory_buildsSnapshotWithAllFields() {
        var cfg = aspectObservabilityConfig(true, true, true, true, 3);

        assertThat(cfg.logging()).isTrue();
        assertThat(cfg.metrics()).isTrue();
        assertThat(cfg.spans()).isTrue();
        assertThat(cfg.tracing()).isTrue();
        assertThat(cfg.depth()).isEqualTo(3);
        assertThat(cfg.allOff()).isFalse();
    }
}
