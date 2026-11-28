// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3041 (F5) wiring pin for the per-record reclaim trigger. The route is wired inline inside `AetherNode`'s node assembly,
/// which no unit test can construct, and `ConsumerGroupStreamLifeReclaimTest` hand-wires its own router, so removing the
/// route leaves every behavioural test green. Following `ClusterEventRedeliveryWiringTest`, this pin reads the assembly
/// source with comments stripped and whitespace removed.
class ConsumerGroupReclaimWiringTest {
    /// A committed or replayed consumer-group record reaches the coordinator, which reclaims it on arrival when the record's
    /// life is not the stream's committed one and this node is the leader (`ConsumerGroupCoordinator.onConsumerGroupPut`).
    @Test
    void assembly_routesConsumerGroupPutsToTheCoordinator() {
        assertThat(AssemblySource.stripped()).containsOnlyOnce("kvRouterBuilder.onPut(AetherKey.ConsumerGroupKey.class,consumerGroupCoordinator::onConsumerGroupPut);");
    }
}
