// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #280 R28: `MetricsConfig.recordTiming` / `recordCounts` were bound from TOML and then ignored —
/// a timer was always recorded and a counter never. The flags must select what is recorded.
class MetricsInterceptorFlagsTest {
    private static final Fn1<Promise<String>, String> METHOD = request -> Promise.success("ok-" + request);

    @Test
    void recordTimingFalse_recordsNoTimer() {
        var registry = new SimpleMeterRegistry();

        invoke(registry, config(false, true));
        assertThat(registry.find("calls.success").timer()).as("record_timing = false must record no timer").isNull();
    }

    @Test
    void recordCountsTrue_recordsACounter() {
        var registry = new SimpleMeterRegistry();

        invoke(registry, config(false, true));
        var counter = registry.find("calls.success.count").counter();

        assertThat(counter).as("record_counts = true must record a counter").isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordTimingTrue_recordCountsFalse_recordsOnlyATimer() {
        var registry = new SimpleMeterRegistry();

        invoke(registry, config(true, false));
        assertThat(registry.find("calls.success").timer()).isNotNull();
        assertThat(registry.find("calls.success.count").counter()).isNull();
    }

    private static MetricsConfig config(boolean timing, boolean counts) {
        return MetricsConfig.metricsConfig("calls", timing, counts).fold(cause -> fail("valid config: " + cause.message()),
                                                                         c -> c);
    }

    private static void invoke(SimpleMeterRegistry registry, MetricsConfig config) {
        new MetricsMethodInterceptor(config, registry, Tags.empty()).intercept(METHOD)
                                                                    .apply("x")
                                                                    .await()
                                                                    .onFailure(cause -> fail("method must succeed: " + cause.message()));
    }
}
