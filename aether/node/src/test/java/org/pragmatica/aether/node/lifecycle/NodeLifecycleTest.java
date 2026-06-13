// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class NodeLifecycleTest {
    private NodeLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = NodeLifecycle.nodeLifecycle();
    }

    @Nested class StateTransitions {
        @Test
        void nodeLifecycle_initialState_isStarting() {
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.STARTING);
        }

        @Test
        void nodeLifecycle_advancesToJoining_whenSubsystemsReady() {
            lifecycle.subsystemsReady();
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.JOINING);
        }

        @Test
        void nodeLifecycle_advancesToActive_whenSelfReadySignal() {
            lifecycle.subsystemsReady();
            lifecycle.signalReady();
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.ACTIVE);
        }

        @Test
        void nodeLifecycle_signalReady_isNoOp_whenNotInJoining() {
            lifecycle.signalReady();
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.STARTING);
        }

        @Test
        void nodeLifecycle_subsystemsReady_isIdempotent() {
            lifecycle.subsystemsReady();
            lifecycle.subsystemsReady();
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.JOINING);
        }

        @Test
        void nodeLifecycle_drainAdvances_throughDrainingToStopped() {
            lifecycle.subsystemsReady();
            lifecycle.signalReady();
            lifecycle.drain().onSuccess(_ -> {}).onFailure(c -> {throw new AssertionError(c.message());});
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.STOPPED);
        }

        @Test
        void nodeLifecycle_drain_failsFromStarting() {
            lifecycle.drain().onSuccess(_ -> {throw new AssertionError("drain should fail from STARTING");})
                                  .onFailureRun(() -> {});
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.STARTING);
        }
    }

    @Nested class HealthEndpoints {
        @Test
        void healthLive_isLive_inAllStatesExceptStopped() {
            assertThat(lifecycle.currentState().isLive()).as("STARTING is live").isTrue();
            lifecycle.subsystemsReady();
            assertThat(lifecycle.currentState().isLive()).as("JOINING is live").isTrue();
            lifecycle.signalReady();
            assertThat(lifecycle.currentState().isLive()).as("ACTIVE is live").isTrue();
            lifecycle.drain();
            assertThat(lifecycle.currentState()).isEqualTo(NodeState.STOPPED);
            assertThat(lifecycle.currentState().isLive()).as("STOPPED is NOT live").isFalse();
        }

        @Test
        void healthReady_isReady_onlyInActive() {
            assertThat(lifecycle.currentState().isReady()).as("STARTING is NOT ready").isFalse();
            lifecycle.subsystemsReady();
            assertThat(lifecycle.currentState().isReady()).as("JOINING is NOT ready").isFalse();
            lifecycle.signalReady();
            assertThat(lifecycle.currentState().isReady()).as("ACTIVE IS ready").isTrue();
            lifecycle.drain();
            assertThat(lifecycle.currentState().isReady()).as("STOPPED is NOT ready").isFalse();
        }
    }

    @Nested class StateListener {
        @Test
        void addStateListener_replaysCurrentStateImmediately() {
            var observed = new ArrayList<NodeStateChanged>();
            lifecycle.addStateListener(observed::add);
            assertThat(observed).hasSize(1);
            assertThat(observed.get(0).previous()).isEqualTo(NodeState.STARTING);
            assertThat(observed.get(0).current()).isEqualTo(NodeState.STARTING);
        }

        @Test
        void addStateListener_receivesAllSubsequentTransitions() {
            var observed = new ArrayList<NodeStateChanged>();
            lifecycle.addStateListener(observed::add);
            lifecycle.subsystemsReady();
            lifecycle.signalReady();
            assertThat(observed).extracting(NodeStateChanged::current)
                                .containsExactly(NodeState.STARTING, NodeState.JOINING, NodeState.ACTIVE);
        }

        @Test
        void addStateListener_swallowsListenerExceptions() {
            List<NodeStateChanged> reliableObserved = new ArrayList<>();
            lifecycle.addStateListener(_ -> {throw new RuntimeException("boom");});
            lifecycle.addStateListener(reliableObserved::add);
            lifecycle.subsystemsReady();
            assertThat(reliableObserved).extracting(NodeStateChanged::current)
                                              .contains(NodeState.JOINING);
        }
    }
}
