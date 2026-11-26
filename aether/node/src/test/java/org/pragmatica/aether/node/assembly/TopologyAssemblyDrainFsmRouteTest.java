// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #3754 (b1): the drain sink's FSM call. `fsmDrainRequested` is the `Consumer` the leader's drain reaches the membership state
/// machine through (`DrainSink.synchronous` calls it after the gate). The state machine is the real one; an observer counts every
/// delivery of a `DrainRequested`: the transition it causes (the first delivery moves MEMBER to DEPARTING, and the transition
/// callback does not carry the event) and any handled or ignored delivery after it, so a call made twice or not at all is seen.
class TopologyAssemblyDrainFsmRouteTest {
    private static final NodeId TARGET = nodeId("node-a").unwrap();

    private final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();

    @Test
    void fsmDrainRequested_withTheStateMachineSet_deliversOneDrainRequestedForTheTarget() {
        var fsm = MembershipFsm.membershipFsm(new CountingObserver(seen));

        fsm.onSwimHealthy(TARGET, 1L);
        assertThat(fsm.memberStates()).containsEntry(TARGET, "Member");

        TopologyAssembly.fsmDrainRequested(new AtomicReference<>(fsm))
                        .accept(TARGET);

        assertThat(fsm.memberStates()).as("the drain moved the target to DEPARTING").containsEntry(TARGET, "Departing");
        assertThat(seen).as("exactly one DrainRequested reached the state machine").containsExactly("DrainRequested");
    }

    @Test
    void fsmDrainRequested_beforeTheStateMachineExists_doesNotThrow() {
        var consumer = TopologyAssembly.fsmDrainRequested(new AtomicReference<>());

        assertThatCode(() -> consumer.accept(TARGET)).doesNotThrowAnyException();
        assertThat(seen).isEmpty();
    }

    private record CountingObserver(CopyOnWriteArrayList<String> seen) implements FsmObserver<MembershipState, MembershipEvent> {
        @Override
        public void onTransition(FsmTags tags, MembershipState from, MembershipState to) {
            if (to instanceof MembershipState.Departing) {
                seen.add("DrainRequested");
            }
        }

        @Override
        public void onCasLost(FsmTags tags, MembershipState expected, MembershipState actual) {}

        @Override
        public void onEventIgnored(FsmTags tags, MembershipState state, MembershipEvent event) {
            drainRequestedOnly(event);
        }

        @Override
        public void onHandled(FsmTags tags, MembershipState state, MembershipEvent event) {
            drainRequestedOnly(event);
        }

        private void drainRequestedOnly(MembershipEvent event) {
            if (event instanceof MembershipEvent.DrainRequested) {
                seen.add("DrainRequested");
            }
        }
    }
}
