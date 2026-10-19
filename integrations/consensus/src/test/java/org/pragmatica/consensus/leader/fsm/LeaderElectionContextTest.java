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
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #1535 item 1: leader eligibility before `installVoters` must not fail open. With a configured
/// cluster, a node outside it is not electable in the window before the voter set is installed; with no
/// configured cluster (nothing to compare against) every node stays eligible until voters install.
class LeaderElectionContextTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId MEMBER = new NodeId("node-2");
    private static final NodeId OUTSIDER = new NodeId("node-9");
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private LeaderElectionContext context(List<NodeId> expectedCluster) {
        var holder = new LeaderElectionContext[1];

        FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("eligibility-test",
                                                                    fsm -> dormant(fsm, expectedCluster, holder));
        return holder[0];
    }

    private LeaderElectionState dormant(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                        List<NodeId> expectedCluster,
                                        LeaderElectionContext[] holder) {
        holder[0] = new LeaderElectionContext(fsm,
                                              SELF,
                                              Option.none(),
                                              expectedCluster,
                                              MessageRouter.mutable(),
                                              LONG,
                                              LONG,
                                              LONG,
                                              LONG,
                                              LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD);
        return holder[0].dormant();
    }

    @Test
    void nonMemberIsNotEligibleBeforeInstallVoters() {
        var ctx = context(List.of(SELF, MEMBER));

        assertThat(ctx.isEligible(OUTSIDER)).isFalse();
    }

    @Test
    void memberIsEligibleBeforeInstallVoters() {
        var ctx = context(List.of(SELF, MEMBER));

        assertThat(ctx.isEligible(MEMBER)).isTrue();
    }

    @Test
    void anyNodeIsEligibleBeforeInstallVoters_whenNoClusterConfigured() {
        var ctx = context(List.of());

        assertThat(ctx.isEligible(OUTSIDER)).isTrue();
    }

    @Test
    void installedVotersOverrideConfiguredCluster() {
        var ctx = context(List.of(SELF, MEMBER));

        ctx.installVoters(List.of(SELF, OUTSIDER));

        assertThat(ctx.isEligible(OUTSIDER)).isTrue();
        assertThat(ctx.isEligible(MEMBER)).isFalse();
    }
}
