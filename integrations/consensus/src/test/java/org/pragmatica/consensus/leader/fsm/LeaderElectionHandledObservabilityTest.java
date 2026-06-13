/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the J4 fix: side-effect-only event arms in `LeaderElectionState` (Dormant `NodeAdded`,
/// Dormant `NodeGone`, Electing/ReElecting `ElectionTick`, Electing/ReElecting `ProposalSettled`)
/// now record `handled` rather than passing through unobserved.
class LeaderElectionHandledObservabilityTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness() {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "leader-election-handled-test",
                fsm -> {
                    var ctx = new LeaderElectionContext(fsm,
                                                        SELF,
                                                        Option.<org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler>none(),
                                                        List.of(SELF, PEER_A, PEER_B),
                                                        MessageRouter.mutable(),
                                                        TimeSpan.timeSpan(500).millis(),
                                                        TimeSpan.timeSpan(2).seconds(),
                                                        TimeSpan.timeSpan(1).seconds(),
                                                        TimeSpan.timeSpan(5).seconds(),
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD);
                    return ctx.dormant();
                });
    }

    @Test
    void dormant_nodeAdded_recordedAsHandled() {
        var h = buildHarness();
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Dormant.class);

        h.dispatch(new ClusterFsmEvent.NodeAdded(PEER_A, List.of(SELF, PEER_A)));

        assertThat(h.state()).isInstanceOf(LeaderElectionState.Dormant.class);
        assertThat(h.handled()).hasSize(1);
        assertThat(h.handled().getFirst().event()).isInstanceOf(ClusterFsmEvent.NodeAdded.class);
        // No `ignored` for NodeAdded — the arm consumed it via side effect.
        assertThat(h.ignored().stream()
                    .filter(rec -> rec.event() instanceof ClusterFsmEvent.NodeAdded))
                .isEmpty();
    }

    @Test
    void dormant_nodeGone_recordedAsHandled() {
        var h = buildHarness();

        h.dispatch(new ClusterFsmEvent.NodeGone(PEER_B, List.of(SELF, PEER_A)));

        assertThat(h.state()).isInstanceOf(LeaderElectionState.Dormant.class);
        assertThat(h.handled()).hasSize(1);
        assertThat(h.handled().getFirst().event()).isInstanceOf(ClusterFsmEvent.NodeGone.class);
        assertThat(h.ignored().stream()
                    .filter(rec -> rec.event() instanceof ClusterFsmEvent.NodeGone))
                .isEmpty();
    }
}
