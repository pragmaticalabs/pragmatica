// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class NodeReportedStateTest {
    /// HELD is a wire value of its own, and a draining one. Mutation: map "HELD" to SYNCING in `fromWire`.
    @Test
    void heldParsesAndIsDraining() {
        assertThat(NodeReportedState.fromWire("HELD")).isEqualTo(NodeReportedState.HELD);
        assertThat(NodeReportedState.HELD.name()).isEqualTo("HELD");
        assertThat(NodeReportedState.HELD.isDraining()).isTrue();
        assertThat(NodeReportedState.DRAINING.isDraining()).isTrue();
    }

    @Test
    void nonDrainingStates_areNotDraining() {
        assertThat(NodeReportedState.READY.isDraining()).isFalse();
        assertThat(NodeReportedState.SYNCING.isDraining()).isFalse();
    }
}
