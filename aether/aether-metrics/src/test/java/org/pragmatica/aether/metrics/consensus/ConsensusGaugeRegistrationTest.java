// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.consensus;

import org.pragmatica.aether.metrics.observability.ObservabilityRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1117: the two new consensus gauges are registered on the Prometheus surface under their documented names and read
/// the collector's live values. A name registered nowhere would leave the gauge absent while `counterMap()` still
/// carried the key, which is the drift `RabiaMetricsCollectorTest` cannot see.
class ConsensusGaugeRegistrationTest {
    private static final NodeId NODE = new NodeId("node-1");

    @Test
    void pendingAgeAndApplyTimeouts_areRegisteredAsGauges_readingTheLiveValues() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();
        var age = new java.util.concurrent.atomic.AtomicLong(1_234L);
        var registry = ObservabilityRegistry.prometheus();

        collector.registerOldestPendingBatchAgeProbe(NODE, age::get);
        collector.recordApplyTimeout(NODE);
        registry.registerConsensusMetrics(() -> collector.snapshot().counterMap());

        assertThat(registry.registry().find("consensus_oldest_pending_batch_age_ms").gauge())
            .as("registered under the documented name")
            .isNotNull();
        assertThat(registry.registry().find("consensus_oldest_pending_batch_age_ms").gauge().value()).isEqualTo(1_234.0);
        assertThat(registry.registry().find("consensus_apply_timeouts_total").gauge()).isNotNull();
        assertThat(registry.registry().find("consensus_apply_timeouts_total").gauge().value()).isEqualTo(1.0);

        age.set(9_999L);

        assertThat(registry.registry().find("consensus_oldest_pending_batch_age_ms").gauge().value())
            .as("a level: read at scrape time")
            .isEqualTo(9_999.0);
    }

    @Test
    void consecutiveEmptySlots_isRegisteredAsAGauge_readingTheLiveLevel() {
        var collector = RabiaMetricsCollector.rabiaMetricsCollector();
        var emptySlots = new java.util.concurrent.atomic.AtomicLong(7L);
        var registry = ObservabilityRegistry.prometheus();

        collector.registerProposalConvergenceProbes(NODE, emptySlots::get, Option::none);
        registry.registerConsensusMetrics(() -> collector.snapshot().counterMap());

        assertThat(registry.registry().find("consensus_consecutive_empty_slots_with_pending").gauge())
            .as("registered under the documented name")
            .isNotNull();
        assertThat(registry.registry().find("consensus_consecutive_empty_slots_with_pending").gauge().value()).isEqualTo(7.0);

        emptySlots.set(1_867L);

        assertThat(registry.registry().find("consensus_consecutive_empty_slots_with_pending").gauge().value())
            .as("a level: read at scrape time")
            .isEqualTo(1_867.0);
    }
}
