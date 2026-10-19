/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1535 item 2: each inbound refusal counter returns its own running total, which the transport uses to
/// throttle the operator-visible WARN, so a total that stopped tracking its counter would silence or flood it.
class QuicRefusalTotalsTest {
    @Test
    void onUnboundDrop_returnsItsOwnRunningTotal() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        assertThat(metrics.onUnboundDrop()).isEqualTo(1L);
        assertThat(metrics.onUnboundDrop()).isEqualTo(2L);
        assertThat(metrics.onPolicyDrop()).as("a different gate keeps its own total").isEqualTo(1L);
    }

    @Test
    void onPolicyDrop_returnsItsOwnRunningTotal() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        assertThat(metrics.onPolicyDrop()).isEqualTo(1L);
        assertThat(metrics.onPolicyDrop()).isEqualTo(2L);
        assertThat(metrics.onUnboundDrop()).as("a different gate keeps its own total").isEqualTo(1L);
    }

    @Test
    void onFaultFilterDrop_returnsItsOwnRunningTotal() {
        var metrics = QuicTransportMetrics.quicTransportMetrics();

        assertThat(metrics.onFaultFilterDrop()).isEqualTo(1L);
        assertThat(metrics.onFaultFilterDrop()).isEqualTo(2L);
    }
}
