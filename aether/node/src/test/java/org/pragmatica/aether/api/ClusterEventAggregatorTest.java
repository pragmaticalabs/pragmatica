// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ClusterEvent.EventType;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the post-R3 KV bridge in [`ClusterEventAggregator.onNodeLifecyclePut`]:
///   - `DRAINING -> DECOMMISSIONED` (graceful drain) emits `NODE_LEFT`
///   - `ON_DUTY -> DECOMMISSIONED` (abrupt SWIM-detected loss) emits `NODE_FAILED`
///   - `unknown -> DECOMMISSIONED` (cold replay / never-observed-prior) emits `NODE_FAILED`
///   - `DRAINING` writes surface as `NODE_LIFECYCLE_CHANGED` events
///   - state cache provides idempotency on snapshot replay
///   - `ON_DUTY` writes do not emit `NODE_LEFT`/`NODE_FAILED`
class ClusterEventAggregatorTest {

    private static final NodeId NODE_A = new NodeId("node-a");

    private static final int CLUSTER_SIZE = 4;

    private ClusterEventAggregator newAggregator() {
        return ClusterEventAggregator.clusterEventAggregator(ClusterEventAggregatorConfig.defaultConfig(),
                                                             () -> CLUSTER_SIZE);
    }

    private static ValuePut<NodeLifecycleKey, NodeLifecycleValue> lifecyclePut(NodeId nodeId, NodeLifecycleState state) {
        var key = NodeLifecycleKey.nodeLifecycleKey(nodeId);
        var value = NodeLifecycleValue.nodeLifecycleValue(state);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    @Test
    void onNodeLifecyclePut_decommissionedFromUnknown_emitsNodeFailed() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = aggregator.events();
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.type()).isEqualTo(EventType.NODE_FAILED);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_decommissionedAfterOnDuty_emitsNodeFailed() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.ON_DUTY));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = aggregator.events();
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.type()).isEqualTo(EventType.NODE_FAILED);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_decommissionedAfterDraining_emitsNodeLeft() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = aggregator.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(EventType.NODE_LIFECYCLE_CHANGED);
        assertThat(events.get(0).details()).containsEntry("transition", "NONE->DRAINING");
        assertThat(events.get(1).type()).isEqualTo(EventType.NODE_LEFT);
        assertThat(events.get(1).details()).containsEntry("nodeId", NODE_A.id())
                                           .containsEntry("clusterSize", String.valueOf(CLUSTER_SIZE));
    }

    @Test
    void onNodeLifecyclePut_draining_emitsNodeLifecycleChanged() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DRAINING));

        var events = aggregator.events();
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.type()).isEqualTo(EventType.NODE_LIFECYCLE_CHANGED);
        assertThat(event.details()).containsEntry("nodeId", NODE_A.id())
                                   .containsEntry("transition", "NONE->DRAINING");
    }

    @Test
    void onNodeLifecyclePut_idempotentOnSameState_noDoubleEmit() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));
        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.DECOMMISSIONED));

        var events = aggregator.events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo(EventType.NODE_FAILED);
    }

    @Test
    void onNodeLifecyclePut_onDuty_doesNotEmitNodeLeft() {
        var aggregator = newAggregator();

        aggregator.onNodeLifecyclePut(lifecyclePut(NODE_A, NodeLifecycleState.ON_DUTY));

        assertThat(aggregator.events()).isEmpty();
    }
}
